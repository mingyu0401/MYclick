package com.myclick.access

import android.content.Context
import android.view.Display
import com.myclick.core.Config
import com.myclick.core.TouchEvent
import com.myclick.core.Trace
import com.myclick.overlay.OverlayController

/**
 * 「本进程悬浮窗宿主」—— 两种免 root 模式共用的绘制端。
 *
 * 跑在模块 App 自己的进程里，窗口类型是 TYPE_APPLICATION_OVERLAY，
 * 不需要 root、不需要 LSPosed、不需要勾系统框架作用域。事件来源有两种：
 *
 *  - **无障碍**（[Config.overlayOnly]）：只能拿到「被点控件的 bounds」，
 *    所以特效画在控件中心；点击空白 / 自绘界面（游戏、视频全屏控件）可能没有特效，
 *    位置也会有偏差，而且 ColorOS 会持续弹「正在读取屏幕内容」的提示。
 *  - **Shizuku**（[Config.shizukuMode]）：以 shell 身份读触摸屏设备节点，
 *    拿到的是**真实触摸坐标**，全场景都有且位置准确 —— 免 root 方案里唯一能做到这点的。
 *
 * 两者都靠 [com.myclick.overlay.OverlayController] 的 `overlayOnlyHost` 通道绘制，
 * 只是喂事件的一方不同，互斥使用。
 */
object LocalFxHost {

    @Volatile
    private var controller: OverlayController? = null

    private fun ensure(context: Context): OverlayController {
        val app = context.applicationContext ?: context
        val ctrl = controller ?: OverlayController(app, overlayOnlyHost = true).also {
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
            "sync enabled=${cfg.enabled} overlayOnly=${cfg.overlayOnly} shizuku=${cfg.shizukuMode}"
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
