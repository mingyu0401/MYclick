package com.myclick.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.SparseArray
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.myclick.core.Config
import com.myclick.core.ConfigStore
import com.myclick.core.GlobalOwner
import com.myclick.core.RecordingState
import com.myclick.core.TouchEvent
import com.myclick.fx.EffectSurfaceView
import kotlin.math.abs

/**
 * 在**录屏进程内**管理特效覆盖层。
 *
 * 关键点：
 *  - 用 TYPE_APPLICATION_OVERLAY 但绝不加 FLAG_SECURE；窗口会被 SurfaceFlinger 合成，
 *    MediaProjection / DisplayCapture 能完整录到。
 *  - FLAG_NOT_TOUCHABLE + NOT_FOCUSABLE：只观察、不拦截、不消费原始触摸事件，
 *    无障碍服务与其它 App 完全不受影响。
 *  - 每个 displayId 用 createDisplayContext(display) 拿独立 WindowManager，
 *    处理外接显示器 / 折叠屏切换；坐标用该 display 的 density 换算成 dp。
 *  - 没有 SYSTEM_ALERT_WINDOW 时回退到往 Activity DecorView 上 addView。
 */
class OverlayController(
    private val baseContext: Context,
    /** true = 模块自身进程的「全局生效」宿主：只认总开关，不走录屏状态门控 */
    private val globalHost: Boolean = false
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val displayManager = baseContext.getSystemService(DisplayManager::class.java)
    private val windowHosts = SparseArray<DisplayHost>()
    private val decorHosts = SparseArray<DisplayHost>()

    @Volatile
    private var attached = false

    @Volatile
    private var overlayAvailable = true

    private var listenerRegistered = false

    private var lastEventTime = 0L
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var lastDisplayId = Int.MIN_VALUE
    private var lastDisplayChangeMs = 0L

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            // 绝不因为「新显示器出现」就建窗口：录屏一开始会新建一个
            // MediaProjection 虚拟显示器，往它上面 addView 会让 AUTO_MIRROR 失效。
        }
        override fun onDisplayRemoved(displayId: Int) {
            mainHandler.post { removeHost(displayId) }
        }
        override fun onDisplayChanged(displayId: Int) {
            val d = displayManager?.getDisplay(displayId) ?: return
            if (isCaptureDisplay(d)) {
                // 虚拟录屏屏：不建窗口；顺手清掉旧版本可能遗留的宿主
                mainHandler.post { removeHost(displayId) }
                return
            }
            // 折叠屏 / 刷新率切换会连发 onDisplayChanged；限流，避免窗口被反复拆建
            val now = SystemClock.uptimeMillis()
            if (now - lastDisplayChangeMs < 250L) return
            lastDisplayChangeMs = now
            mainHandler.post { refreshHost(displayId) }
        }
    }

    /**
     * 判断一个 Display 是否为「投屏 / 截屏用的虚拟显示器」。
     *
     * Android 的 AUTO_MIRROR VirtualDisplay 只要被 addView 了任意窗口，就会认为
     * 「这块屏有自己的内容」而停止镜像主屏 —— 此时录屏编码器只拿到最初几帧，
     * 之后画面全黑 / 静止（音频仍在录）。ColorOS 17 实测：
     *   OPLUSScreenRecording / uniqueId=virtual:... / 1080x2354 /
     *   type VIRTUAL / touch NONE / FLAG_SECURE|FLAG_PRIVATE|FLAG_PRESENTATION
     * 之前 onDisplayChanged 无差别地为它建了第二个 MYclick-FX 窗口，正是黑屏根因。
     *
     * 主判据只用公开 API（Display.getFlags() / getName()）；uniqueId 是可选的隐藏 API 兜底，
     * 反射失败也不影响判断。
     */
    private fun isCaptureDisplay(d: Display): Boolean {
        // FLAG_PRIVATE 是 MediaProjection / 投屏虚拟屏的标准标志，物理屏不会有
        if (d.flags and Display.FLAG_PRIVATE != 0) return true
        // uniqueId 形如 virtual:com.oplus.screenrecorder,10141,OPLUSScreenRecording,8；
        // 该 getter 在部分 SDK 里是 hidden，反射调用并优雅降级即可
        val unique = runCatching {
            d.javaClass.getMethod("getUniqueId").invoke(d) as? String
        }.getOrNull().orEmpty()
        if (unique.startsWith("virtual:", ignoreCase = true)) return true
        val name = runCatching { d.name }.getOrDefault("") ?: ""
        return name.contains("screenrecord", ignoreCase = true) ||
            name.contains("screencapture", ignoreCase = true) ||
            name.contains("录屏") || name.contains("屏幕录制")
    }

    init {
        RecordingState.onChange = { mainHandler.post { reconcile() } }
    }

    /**
     * 窗口生命周期只由总开关 / 全局归属决定。
     *
     * 真机验证发现：把窗口与「是否正在录制」绑定后，录屏 App 启动阶段
     * show_touches / MediaProjection 信号会短暂来回翻转，导致 WindowManager
     * 在 100ms 内反复 addView/removeView，最终留下多个泄漏的 MYclick-FX 窗口。
     * 覆盖层是透明 NOT_TOUCHABLE 且无粒子时渲染循环自动停止，常驻没有性能代价；
     * 「只在录制时出特效」由 onTouch() 里的 RecordingState.isActive() 判断即可。
     */
    fun reconcile() {
        mainHandler.post {
            val cfg = ConfigStore.value
            val global = cfg.globalEffect || cfg.gateMode == Config.GATE_ALWAYS
            // system_server 里的全局宿主只在「全局生效」打开时才挂窗口，
            // 关闭时立刻撤掉，避免系统进程常驻一个全屏透明窗口。
            //
            // 录屏进程的宿主：全局模式下只要全局宿主已经确认接管（GlobalOwner），
            // 就撤掉自己的窗口，物理上保证不可能再叠一份；全局宿主还没接管
            // （刚开机 / 正在挂窗口）时先留着，随时接住这一击。
            val want = if (globalHost) {
                cfg.enabled && global
            } else {
                cfg.enabled && (!global || !GlobalOwner.owned)
            }
            if (want) attach(cfg) else detach()
        }
    }

    /** 全局宿主是否已挂上窗口（输入线程读取，用于判断这一击归谁画） */
    fun isAttached(): Boolean = attached

    fun updateConfig(cfg: Config) {
        mainHandler.post {
            forEachHost { host ->
                val uiMode = host.context.resources.configuration.uiMode
                host.view.applyConfig(cfg, uiMode)
            }
            reconcile()
        }
    }

    /** 触摸入口：xPx/yPx 是 display 像素坐标（MotionEvent.rawX/rawY）。 */
    fun onTouch(event: TouchEvent) {
        val cfg = ConfigStore.value
        com.myclick.core.Diag.log(
            "overlay",
            "onTouch x=${event.xPx} y=${event.yPx} enabled=${cfg.enabled} " +
                "globalHost=$globalHost gate=${cfg.gateMode} " +
                "active=${RecordingState.isActive()} attached=$attached"
        )
        // 全局宿主只认总开关 + 全局模式；
        // 录屏进程内的宿主用三重门控：总开关、事件级归属标记、是否真的在录屏。
        // 这里刻意不读 cfg.globalEffect：录屏 App 常驻后台时该值可能是旧快照，
        // 而归属标记跟着每一次点击的事件走，才是唯一权威。
        val global = cfg.globalEffect || cfg.gateMode == Config.GATE_ALWAYS
        val allow = if (globalHost) {
            cfg.enabled && global
        } else {
            // 全局模式且归属未交还 → 一定不画；
            // 归属交还（全局宿主还没挂好）时即使配置写着全局也要接住这一击。
            cfg.enabled && !GlobalOwner.owned && RecordingState.isActive() &&
                (!global || GlobalOwner.handedBack)
        }
        if (!allow) return
        // AUTO 模式下可能同时收到 framework / app-scoped / 多 monitor 通道的多份，
        // 40ms + 16px 去重，避免同一次点击画两遍特效。
        if (event.displayId == lastDisplayId &&
            abs(event.eventTime - lastEventTime) < 40L &&
            abs(event.xPx - lastX) < 16f && abs(event.yPx - lastY) < 16f
        ) return
        lastEventTime = event.eventTime
        lastX = event.xPx
        lastY = event.yPx
        lastDisplayId = event.displayId

        mainHandler.post {
            if (!attached) return@post
            var host = windowHosts.get(event.displayId)
                ?: decorHosts.get(event.displayId)
                ?: windowHosts.get(Display.DEFAULT_DISPLAY)
                ?: decorHosts.get(Display.DEFAULT_DISPLAY)
            if (host == null) {
                // 多屏 / 折叠屏：只在真正收到该物理屏的触摸时才为它建宿主，
                // 避免 DisplayListener 提前给虚拟投屏建窗口。
                val d = displayManager?.getDisplay(event.displayId)
                if (d != null && !isCaptureDisplay(d)) {
                    runCatching { createWindowHost(d, cfg) }
                    host = windowHosts.get(event.displayId)
                }
            }
            if (host == null) return@post
            // 触摸坐标是 display 绝对像素，而 Canvas 是 view 局部坐标。
            // OEM（ColorOS 17 实测）会给 TYPE_APPLICATION_OVERLAY 应用状态栏 inset，
            // 把窗口内容整体下移约 150px；用窗口实际屏幕位置做补偿，跨 ROM 都准确。
            host.view.getLocationOnScreen(host.location)
            val density = host.density.coerceAtLeast(0.5f)
            host.view.emit(
                (event.xPx - host.location[0]) / density,
                (event.yPx - host.location[1]) / density
            )
        }
    }

    // ---------------- 主路径：系统悬浮窗 ----------------

    /** 主线程内执行；幂等，重复调用只会刷新配置，不会重复 addView */
    private fun attach(cfg: Config) {
        forEachHost { host ->
            val uiMode = host.context.resources.configuration.uiMode
            host.view.applyConfig(cfg, uiMode)
        }
        if (attached) return
        try {
            val displays = displayManager?.displays ?: emptyArray()
            val target = displays.firstOrNull {
                it.displayId == Display.DEFAULT_DISPLAY && !isCaptureDisplay(it)
            } ?: displays.firstOrNull { !isCaptureDisplay(it) }
            if (target != null) createWindowHost(target, cfg)
            if (!listenerRegistered) {
                displayManager?.registerDisplayListener(displayListener, mainHandler)
                listenerRegistered = true
            }
            attached = true
        } catch (t: Throwable) {
            overlayAvailable = false
            android.util.Log.e("MYclickGlobal", "attach failed: $t")
        }
    }

    private fun createWindowHost(display: Display, cfg: Config) {
        // 硬闸：任何路径都不允许在 MediaProjection 虚拟显示器上挂窗口
        if (isCaptureDisplay(display)) return
        if (windowHosts.get(display.displayId) != null) return
        val displayCtx = baseContext.createDisplayContext(display)
        val wm = displayCtx.getSystemService(WindowManager::class.java) ?: return
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val view = EffectSurfaceView(displayCtx, metrics.density)
        val uiMode = displayCtx.resources.configuration.uiMode
        view.applyConfig(cfg, uiMode)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            setTitle("MYclick-FX")
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            // Android 11+ 默认会对悬浮窗应用状态栏/导航栏 inset，窗口原点被下移，
            // 特效就会偏离真实点击点。关掉 inset 适配，让窗口真正覆盖整个 display。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsTypes(0)
                setFitInsetsSides(0)
            }
            // 明确不设置 FLAG_SECURE，否则 MediaProjection 会录成黑屏
        }
        wm.addView(view, lp)
        windowHosts.put(display.displayId, DisplayHost(display.displayId, displayCtx, wm, view, metrics.density, lp))
    }

    /** 无悬浮窗权限时：把同一套 SurfaceView 挂到录屏 App 的 DecorView */
    fun attachToDecor(decor: View) {
        if (overlayAvailable || decor !is ViewGroup) return
        val display = decor.display ?: return
        if (isCaptureDisplay(display)) return
        val id = display.displayId
        if (decorHosts.get(id) != null) return
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val cfg = ConfigStore.value
        // 全局模式下录屏进程不再挂任何宿主（含 DecorView 兜底），绘制权在 system_server
        if (cfg.globalEffect || cfg.gateMode == Config.GATE_ALWAYS) return
        val view = EffectSurfaceView(decor.context, metrics.density)
        view.applyConfig(cfg, decor.context.resources.configuration.uiMode)
        try {
            decor.addView(view, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            decorHosts.put(id, DisplayHost(id, decor.context, null, view, metrics.density, null))
        } catch (t: Throwable) {
            // ignore
        }
    }

    fun overlayPermissionLooksBlocked(): Boolean = !overlayAvailable

    // ---------------- 生命周期 ----------------

    private fun refreshHost(displayId: Int) {
        val cfg = ConfigStore.value
        // 只为「本来就持有窗口」的物理显示器重建窗口。
        // 若允许给任意新 display 凭空建宿主，录屏启动时的虚拟显示器就会中招。
        val owned = windowHosts.get(displayId) != null || decorHosts.get(displayId) != null
        if (!owned) return
        removeHost(displayId)
        if (!attached || !overlayAvailable) return
        val d = displayManager?.getDisplay(displayId) ?: return
        if (isCaptureDisplay(d)) return
        runCatching { createWindowHost(d, cfg) }
    }

    private fun removeHost(displayId: Int) {
        windowHosts.get(displayId)?.let { host ->
            runCatching { host.wm?.removeViewImmediate(host.view) }
            windowHosts.remove(displayId)
        }
        decorHosts.get(displayId)?.let { host ->
            runCatching { (host.view.parent as? ViewGroup)?.removeView(host.view) }
            decorHosts.remove(displayId)
        }
    }

    /** 调用方已在主线程；与 attach() 串行，避免 detach 的异步 post 和 attach 交错 */
    private fun detach() {
        attached = false
        val ids = mutableListOf<Int>()
        for (i in 0 until windowHosts.size()) ids.add(windowHosts.keyAt(i))
        for (i in 0 until decorHosts.size()) ids.add(decorHosts.keyAt(i))
        ids.distinct().forEach { removeHost(it) }
        if (listenerRegistered) {
            runCatching { displayManager?.unregisterDisplayListener(displayListener) }
            listenerRegistered = false
        }
    }

    private inline fun forEachHost(block: (DisplayHost) -> Unit) {
        for (i in 0 until windowHosts.size()) block(windowHosts.valueAt(i))
        for (i in 0 until decorHosts.size()) block(decorHosts.valueAt(i))
    }

    private class DisplayHost(
        val displayId: Int,
        val context: Context,
        val wm: WindowManager?,
        val view: EffectSurfaceView,
        val density: Float,
        val lp: WindowManager.LayoutParams?,
        // 复用数组，渲染热路径零分配
        val location: IntArray = IntArray(2)
    )

    private companion object {
        const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
