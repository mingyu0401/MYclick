package com.myclick.access

import android.content.Context
import android.view.Display
import com.myclick.core.Config
import com.myclick.core.TouchEvent
import com.myclick.overlay.OverlayController

/**
 * 「纯悬浮窗模式」的绘制宿主。
 *
 * 跑在模块 App 自己的进程里，窗口类型仍是 TYPE_APPLICATION_OVERLAY，只是事件来源换成
 * 无障碍服务的点击回调 —— 不需要 root、不需要 LSPosed、不需要勾系统框架作用域。
 *
 * 代价（也是它默认关闭的原因）：
 *  - 无障碍只能拿到「被点控件的 bounds」，拿不到原始触摸坐标，所以特效画在控件中心，
 *    点击空白区域 / 自绘界面（游戏、视频全屏控件）可能没有特效；
 *  - 事件比框架通道晚一帧左右，快速连点时观感略逊。
 * 对普通用户来说这是唯一可行的免 root 方案。
 */
object AccessibilityFxHost {

    @Volatile
    private var controller: OverlayController? = null

    /** 只在开关打开时挂窗口；关闭时立刻撤掉，避免常驻一个全屏图层 */
    @Synchronized
    fun sync(context: Context, cfg: Config) {
        val app = context.applicationContext ?: context
        val ctrl = controller ?: OverlayController(app, overlayOnlyHost = true).also {
            controller = it
        }
        ctrl.updateConfig(cfg)
    }

    @Synchronized
    fun emit(context: Context, cfg: Config, x: Float, y: Float, eventTime: Long) {
        val app = context.applicationContext ?: context
        val ctrl = controller ?: OverlayController(app, overlayOnlyHost = true).also {
            controller = it
        }
        // updateConfig 内部对相同配置去重，只做一次 reconcile；窗口还没挂上时
        // 会在同一 Handler 队列里先 attach 再绘制。
        ctrl.updateConfig(cfg)
        ctrl.onTouch(TouchEvent(x, y, Display.DEFAULT_DISPLAY, eventTime))
    }
}
