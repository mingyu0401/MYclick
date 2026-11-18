package com.myclick.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import com.myclick.core.Prefs
import com.myclick.core.Trace

/**
 * 「纯悬浮窗模式」的事件来源：监听点击 / 长按点击，把被点控件的中心坐标交给覆盖层。
 *
 * 不读 text、不遍历窗口树、不做任何注入，只取一次 `getBoundsInScreen()`。
 * 服务是否工作完全由设置页的「纯悬浮窗模式」开关决定；开关关闭时直接返回，
 * 不产生任何绘制或常驻窗口。
 */
class ClickAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        // 运行期再设一次 flags：部分 ROM 会忽略 xml 里的 flagRetrieveInteractiveWindows，
        // 缺了它 `event.source` 会一直是 null（实测 ColorOS 17 上点击事件能收到但没有 source，
        // 拿不到控件 bounds 就完全无法定位特效）。
        runCatching {
            serviceInfo = serviceInfo.apply {
                eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
                flags = flags or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                notificationTimeout = 0
            }
        }
        val cfg = runCatching { Prefs.read(this) }.getOrNull()
        Trace.log(
            this, "acc",
            "connected flags=${serviceInfo?.flags} " +
                "cfg=${cfg?.let { "enabled=${it.enabled} overlayOnly=${it.overlayOnly}" }}"
        )
        if (cfg != null && cfg.enabled && cfg.overlayOnly) AccessibilityFxHost.sync(this, cfg)
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
            Trace.log(this, "acc", "prefs fail $t")
            return
        }
        if (!cfg.enabled || !cfg.overlayOnly) {
            Trace.log(this, "acc", "skip cfg enabled=${cfg.enabled} overlayOnly=${cfg.overlayOnly}")
            return
        }

        val source = event.source
        if (source == null) {
            Trace.log(this, "acc", "source=null pkg=${event.packageName} cls=${event.className}")
            return
        }
        val bounds = Rect()
        try {
            source.getBoundsInScreen(bounds)
        } catch (t: Throwable) {
            Trace.log(this, "acc", "bounds fail $t")
            return
        } finally {
            @Suppress("DEPRECATION")
            runCatching { source.recycle() }
        }
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            Trace.log(this, "acc", "bounds empty pkg=${event.packageName}")
            return
        }
        // 少数节点会返回整屏 bounds，这种没有定位意义，直接跳过
        if (bounds.width() >= resources.displayMetrics.widthPixels &&
            bounds.height() >= resources.displayMetrics.heightPixels
        ) {
            Trace.log(this, "acc", "bounds fullscreen pkg=${event.packageName} $bounds")
            return
        }

        Trace.log(
            this, "acc",
            "click pkg=${event.packageName} cls=${event.className} " +
                "x=${bounds.exactCenterX()} y=${bounds.exactCenterY()} $bounds"
        )
        runCatching {
            AccessibilityFxHost.emit(
                this,
                cfg,
                bounds.exactCenterX(),
                bounds.exactCenterY(),
                event.eventTime
            )
        }.onFailure { Trace.log(this, "acc", "emit fail $it") }
    }

    override fun onInterrupt() {
        // 系统临时中断服务，不需要处理
    }
}
