package com.myclick.access

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import com.myclick.core.Prefs

/**
 * 「纯悬浮窗模式」的事件来源：监听点击 / 长按点击，把被点控件的中心坐标交给覆盖层。
 *
 * 不读 text、不遍历窗口树、不做任何注入，只取一次 `getBoundsInScreen()`。
 * 服务是否工作完全由设置页的「纯悬浮窗模式」开关决定；开关关闭时直接返回，
 * 不产生任何绘制或常驻窗口。
 */
class ClickAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        // 服务被系统拉起 / 用户重新授权时同步一次，让窗口与开关状态一致
        runCatching {
            val cfg = Prefs.read(this)
            if (cfg.enabled && cfg.overlayOnly) AccessibilityFxHost.sync(this, cfg)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            type != AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
        ) return

        val cfg = try {
            Prefs.read(this)
        } catch (t: Throwable) {
            return
        }
        if (!cfg.enabled || !cfg.overlayOnly) return

        val source = event.source ?: return
        val bounds = Rect()
        try {
            source.getBoundsInScreen(bounds)
        } catch (t: Throwable) {
            return
        } finally {
            @Suppress("DEPRECATION")
            runCatching { source.recycle() }
        }
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        // 少数节点会返回整屏 bounds，这种没有定位意义，直接跳过
        if (bounds.width() >= resources.displayMetrics.widthPixels &&
            bounds.height() >= resources.displayMetrics.heightPixels
        ) return

        runCatching {
            AccessibilityFxHost.emit(
                this,
                cfg,
                bounds.exactCenterX(),
                bounds.exactCenterY(),
                event.eventTime
            )
        }
    }

    override fun onInterrupt() {
        // 系统临时中断服务，不需要处理
    }
}
