package com.myclick.hook

import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import com.myclick.bridge.HookBridge
import com.myclick.bridge.Phase
import com.myclick.core.Config
import com.myclick.core.ConfigStore
import com.myclick.core.Contexts
import com.myclick.core.GlobalOwner
import com.myclick.core.TouchBus
import com.myclick.core.TouchEvent
import com.myclick.core.TouchRelay
import kotlin.math.abs

/**
 * 在任意进程里观察 ACTION_DOWN。
 *
 * Hook 点：android.view.InputEventReceiver#dispatchInputEvent(int, InputEvent, int)
 *   —— 私有方法，由 native InputDispatcher 回调，是输入分发链最靠前的一环。
 *   —— 第三个参数就是 displayId，天然支持多显示器 / 折叠屏坐标归属。
 *   —— 只在 before 阶段读取，不 setResult、不改参数、不消费事件，完全不影响无障碍。
 *
 * 三处复用：
 *   system_server  -> PointerEventDispatcher（InputDispatcher 的 monitor 通道）拿到全局事件
 *   普通 App 进程   -> 该 App 自己的窗口事件（勾选「所有应用」作用域时可用）
 *   录屏进程自身    -> 本地调试 / 覆盖录屏悬浮按钮的点击
 */
class InputEventHook(
    private val bridge: HookBridge,
    private val label: String,
    /** true = 通过广播投递给别的进程；false = 直接交给本进程 TouchBus */
    private val crossProcess: Boolean,
    private val classLoader: ClassLoader? = null,
    /**
     * true = 当前实例跑在 system_server 里，是「全局生效」的权威绘制方。
     * 只有它可以在全局模式下标记事件归属；其它进程的观察实例在全局模式下直接退出，
     * 否则同一次点击会被 App 作用域通道再画一遍。
     */
    private val globalAuthority: Boolean = false
) {
    private var lastTime = 0L
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var lastDisplay = Int.MIN_VALUE

    fun install() {
        val clazz = bridge.findClass("android.view.InputEventReceiver", classLoader)
            ?: runCatching { Class.forName("android.view.InputEventReceiver") }.getOrNull()
        if (clazz == null) {
            bridge.log("[$label] InputEventReceiver 未找到")
            return
        }
        bridge.hookAll(clazz, "dispatchInputEvent", Phase.BEFORE) { ctx -> handle(ctx.args) }
        bridge.log("[$label] InputEventReceiver#dispatchInputEvent 已 Hook")
    }

    /** 追加一个兼容 Hook 点（例如 system_server 里的 PointerEventDispatcher#onInputEvent） */
    fun installExtra(className: String, methodName: String) {
        val clazz = bridge.findClass(className, classLoader) ?: return
        bridge.hookAll(clazz, methodName, Phase.BEFORE) { ctx -> handle(ctx.args) }
        bridge.log("[$label] 兼容 Hook 点 $className#$methodName 已安装")
    }

    /**
     * 必须同步：真机上 InputDispatcher 的多个 monitor 通道会在不同线程同时回调
     * （实测一次点击 3 个线程），去重状态 lastTime/lastX/lastY 需要互斥访问，
     * 否则同一个 ACTION_DOWN 会漏过好几份广播。
     */
    @Synchronized
    private fun handle(args: Array<Any?>) {
        // 设置页改动开关后，5 秒节流的 XSP 刷新保证 system_server 拿到最新配置
        runCatching { ConfigStore.maybeRefresh(Contexts.any()) }
        val cfg = ConfigStore.value
        enterTrace("enter n=${args.size} enabled=${cfg.enabled} " +
            args.joinToString(",") { it?.javaClass?.simpleName ?: "null" })
        if (!cfg.enabled) return

        var eventIndex = -1
        var i = 0
        while (i < args.size) {
            if (args[i] is MotionEvent) {
                eventIndex = i
                break
            }
            i++
        }
        if (eventIndex < 0) return

        val event = args[eventIndex] as MotionEvent
        val action = event.actionMasked
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_POINTER_DOWN) return

        // 多指：直接取该 pointer 的 display 坐标（getRawX(int) 自 API 29 起公开）
        val index = event.actionIndex
        val x = event.getRawX(index)
        val y = event.getRawY(index)

        // dispatchInputEvent 的第 3 个参数是 displayId（Android 12~15 都有）；
        // 极老签名缺失时回退到反射读取 InputEvent#getDisplayId。
        val argDisplay: Int? = if (args.size > eventIndex + 1) args[eventIndex + 1] as? Int else null
        val displayId: Int = argDisplay ?: reflectiveDisplayId(event)
        val time = event.eventTime

        // 同一事件可能被 InputDispatcher 的多个 monitor 重复投递，8ms 内去重
        if (displayId == lastDisplay && abs(time - lastTime) < 8L &&
            abs(x - lastX) < 1f && abs(y - lastY) < 1f
        ) return
        lastTime = time
        lastX = x
        lastY = y
        lastDisplay = displayId

        val ev = TouchEvent(x, y, displayId, time)
        val global = cfg.globalEffect || cfg.gateMode == Config.GATE_ALWAYS
        if (crossProcess) {
            val context = Contexts.any()
            trace("ACTION_DOWN x=$x y=$y display=$displayId ctx=${context != null} " +
                "global=$global authority=$globalAuthority nonce=${cfg.nonce}")
            if (global && !globalAuthority) {
                // 全局模式由 system_server 的宿主统一绘制。
                // app-scoped 观察通道（普通 App 进程）必须静默，否则同一次点击会多画一份。
                return
            }
            if (context != null) {
                if (global) {
                    // 全局宿主已经挂上窗口 → 明确告诉录屏进程「这次归我画」，
                    // 录屏进程收到带该标记的事件会直接丢弃，从源头消除双份特效。
                    val ready = TouchBus.sink != null && FrameworkRuntime.globalOverlayReady()
                    if (ready) {
                        TouchRelay.sendTouch(
                            context, x, y, displayId, time, cfg.nonce, cfg.targetPackage,
                            globalOwned = true
                        )
                        TouchBus.dispatch(ev)
                        trace("global direct x=$x y=$y")
                    } else {
                        // 全局宿主还没挂好（刚开机 / 正在挂窗口）：这一击交给录屏进程，
                        // 自己绝不同时画 —— 宁可只有一份，也不要两份叠在一起。
                        TouchRelay.sendTouch(
                            context, x, y, displayId, time, cfg.nonce, cfg.targetPackage,
                            globalOwned = false
                        )
                        trace("global pending -> recorder x=$x y=$y")
                    }
                } else {
                    TouchRelay.sendTouch(context, x, y, displayId, time, cfg.nonce, cfg.targetPackage)
                }
            } else if (traceCount <= 3) {
                bridge.log("[$label] 无 Context，触摸广播无法发出")
            }
        } else {
            // 录屏进程内：全局模式下让位给 framework 宿主；GlobalOwner 是事件级权威标记，
            // 即使本进程的配置快照还是旧的，也能立刻停手。
            if (global || GlobalOwner.owned) return
            com.myclick.core.TouchBus.dispatch(ev)
        }
    }

    private var traceCount = 0
    private var enterCount = 0

    /** 限流诊断：确认 dispatchInputEvent 是否真的被调到（任何输入事件） */
    private fun enterTrace(msg: String) {
        if (enterCount++ > 6) return
        bridge.log("[$label] $msg")
    }

    /** 限流诊断：写入 LSPosed 模块日志，确认输入 Hook 是否真的命中 */
    private fun trace(msg: String) {
        // ACTION_DOWN 诊断保留较多样本，便于真机排查「录制期间无特效」
        if (traceCount++ > 120) return
        bridge.log("[$label] $msg")
    }

    private fun reflectiveDisplayId(event: MotionEvent): Int = try {
        val m = Class.forName("android.view.InputEvent").getMethod("getDisplayId")
        (m.invoke(event) as? Int) ?: Display.DEFAULT_DISPLAY
    } catch (t: Throwable) {
        Display.DEFAULT_DISPLAY
    }

    private companion object {
        // 保留一个显式时间源，便于将来按墙钟做去重扩展
        fun now(): Long = SystemClock.uptimeMillis()
    }
}
