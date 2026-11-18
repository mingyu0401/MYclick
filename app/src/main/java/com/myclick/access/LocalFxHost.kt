package com.myclick.access

import android.content.Context
import android.view.Display
import com.myclick.core.Config
import com.myclick.core.TouchEvent
import com.myclick.core.Trace
import com.myclick.overlay.OverlayController

/**
 * 「本进程悬浮窗宿主」—— Shizuku 模式的绘制端。
 *
 * 跑在模块 App 自己的进程里，窗口类型是 TYPE_APPLICATION_OVERLAY，
 * 不需要 root、不需要 LSPosed、不需要勾系统框架作用域。事件由
 * [com.myclick.touch.EvdevTouchSource] 读取触摸屏设备节点后喂进来，
 * 拿到的是**真实触摸坐标**，全场景都有且位置准确。
 *
 * 绘制走 [com.myclick.overlay.OverlayController] 的 `localHost` 通道。
 */
object LocalFxHost {

    @Volatile
    private var controller: OverlayController? = null

    private fun ensure(context: Context): OverlayController {
        val app = context.applicationContext ?: context
        val ctrl = controller ?: OverlayController(app, localHost = true).also {
            controller = it
        }
        return ctrl
    }

    /** 只在开关打开时挂窗口；关闭时立刻撤掉，避免常驻一个全屏图层 */
    @Synchronized
    fun sync(context: Context, cfg: Config) {
        val ctrl = ensure(context)
        Trace.log(
            context, "host",
            "sync enabled=${cfg.enabled} shizuku=${cfg.shizukuMode}"
        )
        ctrl.updateConfig(cfg)
    }

    @Synchronized
    fun emit(context: Context, cfg: Config, x: Float, y: Float, eventTime: Long) {
        val ctrl = ensure(context)
        ctrl.updateConfig(cfg)
        ctrl.onTouch(TouchEvent(x, y, Display.DEFAULT_DISPLAY, eventTime))
        Trace.log(context, "host", "emit x=$x y=$y attached=${ctrl.isAttached()}")
    }
}
