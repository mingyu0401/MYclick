package com.myclick.shell

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import android.view.SurfaceControl
import android.view.WindowManager
import com.myclick.core.Config
import com.myclick.core.NightMode
import com.myclick.fx.EffectEngine
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * shell 进程里的覆盖层渲染器。
 *
 * **为什么不用 WindowManager**：ColorOS 对 `TYPE_APPLICATION_OVERLAY` 窗口会弹
 * 「正在当前页面悬浮显示」提示；而 `app_process` 起的进程没有在 AMS 注册，
 * `IWindowManager.openSession()` 会直接抛 `Unknown pid / uid`，连窗口都建不出来
 * （真机 Android 16 / ColorOS 17 实测）。
 *
 * **为什么 SurfaceControl 可以**：它绕开窗口系统，直接向 SurfaceFlinger 要一个
 * root 图层。shell 身份持有 `ACCESS_SURFACE_FLINGER`，建层、设 z、show 全部通过；
 * 因为没有窗口、没有 AppOps 操作，系统不会提示任何「悬浮窗」信息。
 *
 * **为什么要用一堆小图层而不是一张全屏图层**：一次点击的可见范围只有点击点周围
 * 约 90dp（弧最远到 ~58dp，火花飞出去 ~60dp），但全屏 buffer 在 1272×2772 上
 * 每帧要清 13MB、上传 13MB，SurfaceFlinger 还要整块合成一遍；录屏时那块虚拟屏
 * 再合成一次，等于「点一下之后 0.4 秒里系统带宽被吃掉一大块」—— 表现就是
 * **点完马上滑动会发涩**。改成每帧只提交 ~600×600（约 1.4MB）后合成面积降到
 * 十分之一。
 *
 * **为什么用 setPosition 而不是缩小 buffer 再 setScale**：`setScale` 的缩放锚点
 * 不是左上角（实测把 buffer 缩到 1584×727 再放大 1.75 倍后，圆整体偏到别处），
 * 而 `setPosition` 是明确的左上角偏移，位置精确。
 *
 * 坐标系：每个 slot 的 buffer 是屏幕物理像素的一块矩形，canvas 按 density 缩放，
 * 于是绘制单位仍是 dp，和 App 内 [com.myclick.fx.EffectSurfaceView] 完全一致。
 */
class ShellFxRenderer(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val windowManager = context.getSystemService(WindowManager::class.java)

    /**
     * 图层槽位。一次点击占一个槽，独立引擎、独立 buffer、独立位置。
     *
     * 做成「多槽」而不是「单图层跟着最新点击移动」：连点时前一次点击的粒子还在飞，
     * 移动图层会让它们画到错误的位置。
     */
    private inner class Slot(val id: Int) {
        val engine = EffectEngine()
        var control: SurfaceControl? = null
        var surface: Surface? = null

        /** buffer 边长（物理像素，正方形） */
        var size = 0
        var originX = 0
        var originY = 0
        var shown = false

        /** 这个槽当前是否有粒子在播 */
        var active = false

        /** 最近一次使用的时间戳，槽不够用时踢掉最老的 */
        var lastUsed = 0L
    }

    private val slots = Array(SLOT_COUNT) { Slot(it) }

    /**
     * 所有槽位的父容器，挂在主屏 layerStack 上。
     *
     * 槽位不再各自做 root 图层：录屏虚拟屏在 SurfaceFlinger 里只镜像主屏的
     * `DisplayContent` 那棵树（`MirrorRoot → Display 0`），游离在 ROOT 下的图层
     * 不在其中，于是 Shizuku 模式的特效屏幕上看得见、录屏里却没有（Root 模式
     * 走的是 WMS 窗口，本来就在那棵树里，所以录得到）。
     * 有了统一的父容器，就能用 [SurfaceControl] 的 mirror 一次性把全部槽位
     * 镜像到录屏虚拟屏上，见 [setCaptureStacks]。
     */
    private var root: SurfaceControl? = null

    /** 录屏虚拟屏 layerStack → 挂在那块屏上的镜像图层 */
    private val mirrors = HashMap<Int, CaptureMirror>()

    private var screenW = 0
    private var screenH = 0
    private var density = 1f

    private var config: Config = Config.default()

    /** 上一次画出来的深浅色，用来在日志里记一次切换（排查「跟随系统」是否生效） */
    private var lastDark: Boolean? = null

    /** 是否正在录屏（由 [ShellRecorderState] 写入） */
    @Volatile
    private var recording = false

    private var running = false
    private var lastFrameNanos = 0L
    private var speed = 1f
    private var frameCount = 0
    private var inputModeLogged = false

    /**
     * 下一帧的触发时刻（`SystemClock.uptimeMillis()` 基准，用 Double 累加）。
     *
     * 不能再用 `postDelayed(16)`：那是「执行完再等 16ms」，每帧的绘制耗时都会叠进
     * 间隔里，实际间隔变成 16 + 绘制 + 调度 ≈ 18~22ms，跑起来只有 45~55fps，
     * 而且抖动越积越大（越到动画后段越明显）。
     */
    private var nextFrameAt = 0.0

    /** 面板报告的理想节拍（1000/刷新率） */
    private var basePeriodMs = 1000.0 / 60.0

    /** 用户选定的目标节拍：自动时等于面板节拍，手动时不快过面板（见 [resolvePeriod]） */
    private var goalPeriodMs = 0.0

    /**
     * 实际使用的节拍周期，从 [goalPeriodMs] 起，按实测能力自动降级。
     *
     * 面板可能报 120Hz，但 shell 身份创建的 root 图层不一定参与系统的帧率投票。
     * 这里不向 SurfaceFlinger 请求切换刷新率，避免一次点击影响前台页面的显示节奏；
     * 动画只按自身节拍提交小图层。
     */
    private var framePeriodMs = 1000.0 / 60.0

    /** 本轮动画的起始时刻，用来算真实 fps */
    private var loopStartNanos = 0L

    /** 单帧绘制耗时累计（微秒），空闲时打印，用来判断卡顿是否来自这里 */
    private var frameCostUs = 0L

    /**
     * 渲染节拍用 Handler 而不是 Choreographer。
     *
     * app_process 起的进程没有走 ActivityThread 的正常绑定流程，Choreographer 的
     * vsync 回调在这里收不到（真机实测：postFrameCallback 永远不回调，图层一片空白）。
     * 固定 16ms 的 Handler 节拍不依赖 vsync，效果一样且更可控。
     */
    private val frameRunnable = Runnable { tick() }

    // ---------------- 对外入口（都必须跑在主线程） ----------------

    fun applyConfig(cfg: Config) {
        config = cfg
        // 深浅色在每次点击时重新解析：shell 进程收不到系统的配置变更广播，
        // 在这里缓存 uiMode 会让「跟随系统」永远停在进程启动时的值。
        NightMode.invalidate()
        val dark = NightMode.isDark(context)
        speed = EffectEngine.RenderConfig.from(cfg, dark).speed
        resolvePeriod()
        for (s in slots) s.engine.update(cfg, dark)
        if (!cfg.effectOn()) {
            clearAll()
            hideIdle()
        }
    }

    fun setRecording(on: Boolean) {
        recording = on
        if (!on && !config.globalEffect && config.gateMode != Config.GATE_ALWAYS) {
            // 停录后立刻收尾，避免残留粒子在桌面上飘
            clearAll()
            hideIdle()
        }
    }

    fun emit(xPx: Float, yPx: Float) {
        if (!config.effectOn()) {
            println("FX tap dropped: disabled")
            return
        }
        if (!config.globalEffect && config.gateMode != Config.GATE_ALWAYS && !recording) {
            println("FX tap dropped: idle")
            return
        }
        if (!ensureLayout()) {
            println("FX tap dropped: no metrics")
            return
        }
        val radius = radiusPx()
        val size = (radius * 2).coerceAtMost(maxOf(screenW, screenH))
        val ox = (xPx - size / 2f).roundToInt().coerceIn(0, (screenW - size).coerceAtLeast(0))
        val oy = (yPx - size / 2f).roundToInt().coerceIn(0, (screenH - size).coerceAtLeast(0))
        val slot = pickSlot()
        if (!ensureLayer(slot, size, ox, oy)) {
            println("FX tap dropped: no layer")
            return
        }
        val dark = NightMode.isDark(context)
        if (dark != lastDark) {
            lastDark = dark
            println("FX theme: ${if (dark) "dark" else "light"}（themeMode=${config.themeMode}）")
        }
        slot.engine.update(config, dark)
        val d = density.coerceAtLeast(0.5f)
        slot.engine.spawn(xPx / d, yPx / d)
        slot.active = true
        slot.lastUsed = System.nanoTime()
        println(
            "FX tap ${xPx.roundToInt()},${yPx.roundToInt()} slot=${slot.id} " +
                "layer=${size}x$size @$ox,$oy active=${slot.engine.hasActive}"
        )
        if (!running) {
            frameCount = 0
            frameCostUs = 0
            startLoop()
        }
    }

    fun release() {
        running = false
        handler.removeCallbacks(frameRunnable)
        for (s in slots) {
            s.engine.clear()
            releaseSlot(s)
        }
        setCaptureTargets(emptySet())
        runCatching { root?.release() }
        root = null
    }

    /**
     * 把特效镜像到录屏虚拟屏上（由 [ShellRecorderState] 在录制开始 / 结束、
     * 主屏旋转时调用）。
     *
     * 镜像图层是 SurfaceFlinger 侧对 [root] 整棵子树的实时克隆：槽位的显示、位置、
     * 每一帧 buffer 都会同步过去，绘制端不用多画一遍。只放在录屏那块虚拟屏的
     * layerStack 上，不影响投屏等其它虚拟屏，也不改主屏上的任何显示。
     */
    fun setCaptureTargets(targets: Set<ShellRecorderState.CaptureTarget>) {
        val wanted = targets.associateBy { it.layerStack }
        val stale = mirrors.keys - wanted.keys
        for (stack in stale) {
            val m = mirrors.remove(stack) ?: continue
            runCatching { newTxn().setVisibility(m.holder, false).apply() }
            runCatching { m.mirror.release() }
            runCatching { m.holder.release() }
            println("FX mirror off stack=$stack")
        }
        for ((stack, target) in wanted) {
            val existing = mirrors[stack]
            if (existing != null) {
                if (existing.target != target) {
                    // 主屏旋转 / 录屏尺寸变化：镜像不用重建，只重算缩放
                    val tx = newTxn()
                    applyMirrorTransform(tx, existing.mirror, target)
                    tx.apply()
                    existing.target = target
                }
                continue
            }
            val parent = ensureRoot() ?: return
            val m = buildMirror(parent, target) ?: continue
            mirrors[stack] = m
            println("FX mirror on stack=$stack ${target.width}x${target.height} <- ${target.srcWidth}x${target.srcHeight}")
        }
    }

    private class CaptureMirror(
        val holder: SurfaceControl,
        val mirror: SurfaceControl,
        var target: ShellRecorderState.CaptureTarget
    )

    /**
     * 让镜像在录屏画面里和系统镜像的主屏内容严丝合缝。
     *
     * 录屏虚拟屏的尺寸通常和主屏不同（ColorOS 录屏是 1080×2354，主屏 1272×2772）。
     * 系统镜像主屏内容时，会给它的 MirrorRoot 加「等比缩放 + 居中」：
     * `scale = min(录屏宽/主屏宽, 录屏高/主屏高)`，再把缩放后的画面居中放进录屏画面。
     * 特效镜像必须套用同一个变换，否则特效坐标仍按主屏像素算，会整体偏向右下
     * （1.0.39 实测偏移约 1272/1080 ≈ 1.18 倍，靠边的还会被裁掉）。
     */
    private fun applyMirrorTransform(
        tx: SurfaceControl.Transaction,
        mirror: SurfaceControl,
        t: ShellRecorderState.CaptureTarget
    ) {
        if (t.width <= 0 || t.height <= 0 || t.srcWidth <= 0 || t.srcHeight <= 0) {
            setMatrix(tx, mirror, 1f)
            tx.setPosition(mirror, 0f, 0f)
            return
        }
        val scale = min(t.width.toFloat() / t.srcWidth, t.height.toFloat() / t.srcHeight)
        val scaledW = (t.srcWidth * scale).roundToInt()
        val scaledH = (t.srcHeight * scale).roundToInt()
        val dx = ((t.width - scaledW) / 2).toFloat()
        val dy = ((t.height - scaledH) / 2).toFloat()
        setMatrix(tx, mirror, scale)
        tx.setPosition(mirror, dx, dy)
    }

    /**
     * `Transaction.setMatrix(SurfaceControl, dsdx, dtdx, dtdy, dsdy)` 是 @hide；
     * 系统镜像主屏时用的就是它。只在 shell 进程执行，入口已解 hidden API 限制。
     */
    @SuppressLint("BlockedPrivateApi")
    private fun setMatrix(tx: SurfaceControl.Transaction, c: SurfaceControl, scale: Float) {
        val f = Float::class.javaPrimitiveType
        runCatching {
            SurfaceControl.Transaction::class.java
                .getMethod("setMatrix", SurfaceControl::class.java, f, f, f, f)
                .invoke(tx, c, scale, 0f, 0f, scale)
        }.onFailure { println("FX mirror setMatrix failed: $it") }
    }

    /**
     * 在 [stack] 上建一个容器，再把 [parent] 的镜像挂到容器下面。
     *
     * 不能直接给镜像图层设 layerStack：SurfaceFlinger 建镜像时不会把它挂到图层树的
     * 根上（`addToRoot = false`，系统放大镜也是建完镜像再 reparent 到自己的图层下），
     * 不挂父图层就一直处于离屏状态、永远不参与合成 —— 1.0.38 录不到特效就是这个原因。
     * 容器是我们自己用 Builder 建的普通 root 图层，会正常加到根上。
     */
    private fun buildMirror(parent: SurfaceControl, target: ShellRecorderState.CaptureTarget): CaptureMirror? {
        val stack = target.layerStack
        val holder = try {
            val b = SurfaceControl.Builder().setName("$LAYER_NAME-capture-$stack")
            runCatching { SurfaceControl.Builder::class.java.getMethod("setContainerLayer").invoke(b) }
            b.build()
        } catch (t: Throwable) {
            println("FX capture holder failed stack=$stack: $t")
            return null
        }
        val mirror = mirrorOf(parent)
        if (mirror == null) {
            println("FX mirror unavailable, effects won't reach recording on stack=$stack")
            runCatching { holder.release() }
            return null
        }
        val tx = newTxn()
            .setLayer(holder, LAYER_Z)
            .setVisibility(holder, true)
            .reparent(mirror, holder)
            .setVisibility(mirror, true)
        applyMirrorTransform(tx, mirror, target)
        if (!setLayerStack(tx, holder, stack)) {
            println("FX mirror setLayerStack failed stack=$stack")
            runCatching { tx.close() }
            runCatching { mirror.release() }
            runCatching { holder.release() }
            return null
        }
        tx.apply()
        return CaptureMirror(holder, mirror, target)
    }

    /**
     * `SurfaceControl.mirrorSurface(SurfaceControl)` 是 @hide 静态方法。
     *
     * lint 的 BlockedPrivateApi 针对的是 App 进程；这段只在 shell 身份的
     * app_process 里执行，进程入口已经用 [HiddenApi.unlock] 解除了限制。
     */
    @SuppressLint("BlockedPrivateApi")
    private fun mirrorOf(target: SurfaceControl): SurfaceControl? = runCatching {
        SurfaceControl::class.java
            .getMethod("mirrorSurface", SurfaceControl::class.java)
            .invoke(null, target) as? SurfaceControl
    }.recoverCatching {
        SurfaceControl::class.java
            .getDeclaredMethod("mirrorSurface", SurfaceControl::class.java)
            .apply { isAccessible = true }
            .invoke(null, target) as? SurfaceControl
    }.onFailure { println("FX mirrorSurface failed: $it") }.getOrNull()

    /** setLayerStack 是 @hide，反射调用（进程入口已解 hidden API 限制） */
    private fun setLayerStack(tx: SurfaceControl.Transaction, c: SurfaceControl, stack: Int): Boolean =
        runCatching {
            SurfaceControl.Transaction::class.java
                .getMethod("setLayerStack", SurfaceControl::class.java, Int::class.javaPrimitiveType)
                .invoke(tx, c, stack)
        }.isSuccess

    /** 槽位的父容器：没有 buffer、只负责层级，常驻可见（显隐由各槽位自己控制） */
    private fun ensureRoot(): SurfaceControl? {
        root?.let { return it }
        return try {
            val b = SurfaceControl.Builder().setName(LAYER_NAME)
            // @hide；拿不到就是普通无 buffer 图层，作父容器同样可用
            runCatching { SurfaceControl.Builder::class.java.getMethod("setContainerLayer").invoke(b) }
            val c = b.build()
            val tx = newTxn()
                .setLayer(c, LAYER_Z)
                .setVisibility(c, true)
            setLayerStack(tx, c, Display.DEFAULT_DISPLAY)
            markTrustedOverlay(tx, c)
            tx.apply()
            root = c
            c
        } catch (t: Throwable) {
            println("FX build root failed: $t")
            null
        }
    }

    // ---------------- 屏幕尺寸 ----------------

    /** 屏幕尺寸 / 密度变了（旋转、分辨率切换）就整体重建 */
    private fun ensureLayout(): Boolean {
        val metrics = currentMetrics() ?: return false
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        if (w <= 0 || h <= 0) return false
        if (w == screenW && h == screenH && density == metrics.density) return true
        // 尺寸变化：旧图层的 buffer 与位置全部作废
        running = false
        for (s in slots) {
            s.engine.clear()
            releaseSlot(s)
        }
        screenW = w
        screenH = h
        density = metrics.density
        basePeriodMs = 1000.0 / displayRefreshRate()
        resolvePeriod()
        println("FX layout ${w}x$h d=$density panel=${"%.0f".format(1000.0 / basePeriodMs)}Hz")
        return true
    }

    /**
     * 按用户选的特效帧率算目标节拍：自动（0）= 面板节拍；手动指定时不超过面板刷新率。
     *
     * root 图层这条通道实测可能拿不到满速（真机曾见 8.33ms 排帧实测 9.9ms/帧），
     * 跑不动的设备由 calibratePeriod 在一轮之后往下调。只有目标变了才重置实际节拍，
     * 避免每次配置刷新都把已经校准过的值冲掉。
     */
    private fun resolvePeriod() {
        val goal = if (config.fxFps <= 0) basePeriodMs else maxOf(basePeriodMs, 1000.0 / config.fxFps)
        if (goal == goalPeriodMs) return
        goalPeriodMs = goal
        framePeriodMs = goal
    }

    /** 面板刷新率，钳到 [50, 120]；动画节拍跟它对齐，避免每帧落在两次 vsync 之间 */
    private fun displayRefreshRate(): Double {
        val d = runCatching { displayManager?.getDisplay(Display.DEFAULT_DISPLAY) }.getOrNull()
            ?: return 60.0
        @Suppress("DEPRECATION")
        val hz = runCatching { d.refreshRate }.getOrDefault(60f)
        return hz.toDouble().coerceIn(50.0, 120.0)
    }

    private fun currentMetrics(): DisplayMetrics? {
        val display = runCatching { displayManager?.getDisplay(Display.DEFAULT_DISPLAY) }.getOrNull()
            ?: runCatching { windowManager?.defaultDisplay }.getOrNull()
            ?: return null
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(m)
        return m
    }

    /**
     * 一次点击需要的可见半径（物理像素）。
     *
     * 引擎里可见部分的最远距离：弧半径最大约 `26*scale + 2*scale*16`（再往后描边宽度
     * 趋零、看不见），火花 20 帧后 6.8*scale 的初速衰减到位移 ~60*scale。合计取
     * [EFFECT_RADIUS_DP] dp 留余量 —— 裁掉的只是几乎透明的最外圈。
     */
    private fun radiusPx(): Int {
        val s = config.scale.coerceIn(0.2f, 6f)
        val r = (EFFECT_RADIUS_DP * s * density).roundToInt()
        val cap = maxOf(screenW, screenH) / 2
        return r.coerceIn(64, cap.coerceAtLeast(64))
    }

    // ---------------- 图层生命周期 ----------------

    /** 优先空闲槽；都在播就踢最老的（连点超过 [SLOT_COUNT] 次时） */
    private fun pickSlot(): Slot {
        val idle = slots.firstOrNull { !it.active }
        if (idle != null) return idle
        return slots.minBy { it.lastUsed }
    }

    private fun ensureLayer(slot: Slot, size: Int, ox: Int, oy: Int): Boolean {
        if (slot.control == null || slot.size != size) {
            releaseSlot(slot)
            if (!buildLayer(slot, size)) return false
        }
        if (!slot.shown) {
            runCatching { newTxn().setVisibility(slot.control!!, true).apply() }
            slot.shown = true
        }
        if (slot.originX != ox || slot.originY != oy) {
            runCatching { newTxn().setPosition(slot.control!!, ox.toFloat(), oy.toFloat()).apply() }
            slot.originX = ox
            slot.originY = oy
        }
        // 不必在这里清屏：上一轮播完时 tick 已经 clear 过，且首帧自己会先 CLEAR 整块。
        // 每次点击多锁一次 canvas 是纯浪费（实测占单次点击渲染开销的 1/4）。
        return true
    }

    private fun buildLayer(slot: Slot, size: Int): Boolean {
        try {
            val parent = ensureRoot()
            val builder = SurfaceControl.Builder()
                .setName("$LAYER_NAME-${slot.id}")
                .setBufferSize(size, size)
                .setFormat(PixelFormat.TRANSLUCENT)
            if (parent != null) builder.setParent(parent)
            val c = builder.build()
            val s = Surface(c)
            val tx = newTxn()
                .setVisibility(c, false)
                .setPosition(c, 0f, 0f)
            if (parent != null) {
                // 挂在容器下：z 只需在容器内部排序，layerStack 跟随容器
                tx.setLayer(c, slot.id)
            } else {
                // 容器建不出来时退回旧做法：每个槽位自己做主屏上的 root 图层
                tx.setLayer(c, LAYER_Z + slot.id)
                setLayerStack(tx, c, Display.DEFAULT_DISPLAY)
            }
            markTrustedOverlay(tx, c)
            tx.apply()
            slot.control = c
            slot.surface = s
            slot.size = size
            slot.originX = 0
            slot.originY = 0
            slot.shown = false
            // 新 buffer 内容未定义，先清一遍，避免首次显示闪出脏像素
            clearSlot(slot)
            return true
        } catch (t: Throwable) {
            println("FX build layer failed: $t")
            return false
        }
    }

    /**
     * 把特效图层标记为「受信任覆盖层」。
     *
     * 图层本身没有输入通道（SurfaceFlinger 给它的 InputWindowHandle 是
     * `NO_INPUT_CHANNEL`，touchableRegion 也是空的），所以它不是触摸目标；
     * 但 Android 12 起的「阻止不可信触摸」策略仍会把不同 UID 的可见覆盖层
     * 当作不可信遮挡：手指落在特效范围内时，InputDispatcher 会把整段手势
     * 丢掉，表现就是单点后 0.5 秒内同位置滑不动、必须重新抬手。
     *
     * 参考小白录屏的做法，用 `SurfaceControl.Transaction#setTrustedOverlay`
     * 声明这是信任的装饰层，让触摸直接落到下面的 App。只影响输入归属，
     * 不改变图层的显示层级与绘制内容。
     */
    private fun markTrustedOverlay(tx: SurfaceControl.Transaction, c: SurfaceControl) {
        val result = runCatching {
            SurfaceControl.Transaction::class.java
                .getMethod(
                    "setTrustedOverlay",
                    SurfaceControl::class.java,
                    Boolean::class.javaPrimitiveType
                )
                .invoke(tx, c, true)
        }.recoverCatching {
            // 某些 OEM 把该方法从 public 降成 declared，兜底再找一次。
            SurfaceControl.Transaction::class.java
                .getDeclaredMethod(
                    "setTrustedOverlay",
                    SurfaceControl::class.java,
                    Boolean::class.javaPrimitiveType
                )
                .apply { isAccessible = true }
                .invoke(tx, c, true)
        }
        if (!inputModeLogged) {
            inputModeLogged = true
            if (result.isSuccess) {
                println("FX input: effect layer marked trusted overlay")
            } else {
                println("FX input: setTrustedOverlay failed: ${result.exceptionOrNull()}")
            }
        }
    }

    private fun releaseSlot(slot: Slot) {
        runCatching { slot.control?.let { newTxn().setVisibility(it, false).apply() } }
        runCatching { slot.surface?.release() }
        runCatching { slot.control?.release() }
        slot.surface = null
        slot.control = null
        slot.shown = false
        slot.size = 0
        slot.active = false
    }

    private fun clearAll() {
        for (s in slots) {
            s.engine.clear()
            s.active = false
            clearSlot(s)
        }
    }

    private fun hideIdle() {
        if (running) return
        for (s in slots) {
            if (!s.shown) continue
            runCatching {
                s.control?.let { newTxn().setVisibility(it, false).apply() }
            }
            s.shown = false
        }
    }

    private fun newTxn() = SurfaceControl.Transaction()

    // ---------------- 渲染循环 ----------------

    private fun startLoop() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        loopStartNanos = System.nanoTime()
        nextFrameAt = SystemClock.uptimeMillis().toDouble()
        handler.postAtTime(frameRunnable, FRAME_TOKEN, nextFrameAt.toLong())
    }

    private fun tick() {
        if (!running) return
        val now = System.nanoTime()
        val dtMs = if (lastFrameNanos == 0L) FRAME_MS else (now - lastFrameNanos) / 1_000_000f
        lastFrameNanos = now
        val step = (dtMs.coerceIn(0f, MAX_DT_MS) / FRAME_MS) * speed

        var any = false
        val t0 = System.nanoTime()
        for (s in slots) {
            if (!s.active) continue
            s.engine.advance(step)
            if (s.engine.hasActive) {
                renderSlot(s)
                any = true
            } else {
                // 这一槽播完了：立刻收掉，别让它参与合成
                s.active = false
                clearSlot(s)
                hideSlot(s)
            }
        }
        frameCostUs += (System.nanoTime() - t0) / 1000
        frameCount++

        if (!any) {
            running = false
            val avg = if (frameCount > 0) frameCostUs / frameCount else 0
            val elapsedMs = (System.nanoTime() - loopStartNanos) / 1_000_000f
            val fps = if (elapsedMs > 0f) frameCount * 1000f / elapsedMs else 0f
            println(
                "FX idle after $frameCount frames in ${elapsedMs.toInt()}ms " +
                    "(${fps.toInt()}fps target=${"%.0f".format(1000.0 / framePeriodMs)}), " +
                    "draw avg=${avg}us"
            )
            calibratePeriod(frameCount, elapsedMs)
            frameCostUs = 0
            return
        }
        // 固定节拍：按起始时刻累加，绘制耗时不再叠加进间隔（这是掉到 45fps 的根因）。
        // 落后太多（系统抖动 / 被抢占）就把基准重置到这里，避免连续补帧把主线程打满。
        nextFrameAt += framePeriodMs
        val nowMs = SystemClock.uptimeMillis().toDouble()
        if (nextFrameAt < nowMs) nextFrameAt = nowMs + framePeriodMs
        handler.postAtTime(frameRunnable, FRAME_TOKEN, nextFrameAt.toLong())
    }

    /**
     * 按实测帧率校准节拍。
     *
     * 跑一轮就知道了：实际 fps 明显低于目标，说明这个通道拿不到那么高的合成率，
     * 把节拍放到实测值附近（留 3% 余量），下一轮就与 vsync 对齐、不再丢帧。
     * 只会往「更慢」方向调整，避免在边界上来回震荡。
     */
    private fun calibratePeriod(frames: Int, elapsedMs: Float) {
        if (frames < 8 || elapsedMs <= 0f) return
        val actualFps = frames * 1000.0 / elapsedMs
        val targetFps = 1000.0 / framePeriodMs
        if (actualFps >= targetFps * 0.9) return
        // 留 1.5% 余量：周期比实测略长一点点，才不会每隔几帧就错过一次 vsync
        val next = (1000.0 / actualFps * 1.015).coerceAtLeast(goalPeriodMs)
        if (next > framePeriodMs + 0.2) {
            framePeriodMs = next
            println(
                "FX 节拍校准：目标 ${"%.0f".format(targetFps)}fps 拿不到（实测 ${"%.0f".format(actualFps)}fps），" +
                    "改用 ${"%.1f".format(next)}ms/帧"
            )
        }
    }

    private fun renderSlot(slot: Slot) {
        val s = slot.surface ?: return
        var canvas: Canvas? = null
        try {
            canvas = s.lockCanvas(null) ?: return
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.save()
            canvas.scale(density, density)
            // 粒子坐标是屏幕绝对 dp，减掉本槽原点后落到 buffer 内
            canvas.translate(-slot.originX / density, -slot.originY / density)
            slot.engine.draw(canvas)
            canvas.restore()
        } catch (t: Throwable) {
            // 单帧失败忽略，下一帧继续
        } finally {
            if (canvas != null) runCatching { s.unlockCanvasAndPost(canvas) }
        }
    }

    private fun clearSlot(slot: Slot) {
        val s = slot.surface ?: return
        var canvas: Canvas? = null
        try {
            canvas = s.lockCanvas(null) ?: return
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        } catch (t: Throwable) {
            // ignore
        } finally {
            if (canvas != null) runCatching { s.unlockCanvasAndPost(canvas) }
        }
    }

    private fun hideSlot(slot: Slot) {
        if (!slot.shown) return
        runCatching {
            slot.control?.let { newTxn().setVisibility(it, false).apply() }
        }
        slot.shown = false
    }

    /** 供录制状态轮询线程切回主线程用 */
    fun post(block: () -> Unit) {
        handler.post(block)
    }

    private companion object {
        const val LAYER_NAME = "MYclick-ShellFx"

        /** 覆盖在应用之上、SystemUI 之下的一个足够高的 z（实测 2e6 可盖住桌面与全屏应用） */
        const val LAYER_Z = 2_000_000

        /**
         * 一次点击需要的可见半径（dp，scale=1 时）。
         *
         * 实测可见内容集中在 60dp 内，90dp 已能完整覆盖弧与火花；再大只是白白增加
         * buffer 与合成面积。
         */
        const val EFFECT_RADIUS_DP = 90f

        /**
         * 同时可播的点击数量；超过就复用最老的槽。
         *
         * 一次特效约 0.4 秒，人手连点最快约 10 次/秒，所以 5 个槽足够覆盖；每个槽只
         * 是一个 630×630 的小图层，5 个加起来（约 2M 像素）仍比原来一张全屏图层
         * （3.5M 像素）少，多开几个不会让合成变重。
         */
        const val SLOT_COUNT = 5

        /** 动画时间基准：引擎内部以 60fps 一帧为单位，与实际节拍解耦 */
        const val FRAME_MS = 1000f / 60f

        /** 单帧最大推进量：超过这么多的时间视为卡顿，钳住以免动画「跳一大段」 */
        const val MAX_DT_MS = 33f

        /** postAtTime 的 token，用于 removeCallbacks */
        val FRAME_TOKEN = Any()

    }
}
