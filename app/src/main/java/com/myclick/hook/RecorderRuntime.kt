package com.myclick.hook

import android.content.Context
import android.view.View
import com.myclick.bridge.HookBridge
import com.myclick.core.Config
import com.myclick.core.ConfigStore
import com.myclick.core.Contexts
import com.myclick.core.TouchBus
import com.myclick.core.TouchRelay
import com.myclick.overlay.OverlayController

/** 录屏进程内的运行时单例：持有覆盖层、注册跨进程接收器、串起 TouchBus。 */
object RecorderRuntime {

    @Volatile
    private var controller: OverlayController? = null

    @Volatile
    private var started = false

    @Synchronized
    fun start(context: Context, bridge: HookBridge) {
        val app = try {
            context.applicationContext ?: context
        } catch (t: Throwable) {
            context
        }
        Contexts.offer(app)

        val ctrl = controller ?: OverlayController(app).also { controller = it }
        TouchBus.sink = { event -> ctrl.onTouch(event) }

        if (started) return
        started = true
        ConfigStore.onUpdated = { cfg -> onConfig(cfg) }
        RelayReceivers.register(
            app,
            TouchRelayReceiver(),
            TouchRelay.ACTION_TOUCH,
            TouchRelay.ACTION_CONFIG_PUSH,
            TouchRelay.ACTION_TEST_TOUCH
        )
        ConfigStore.refreshAsync(app)
        ctrl.updateConfig(ConfigStore.value)
        bridge.log("[recorder] 运行时已启动，接收器已注册")
    }

    fun onConfig(config: Config) {
        controller?.updateConfig(config)
    }

    fun attachDecor(view: View) {
        controller?.attachToDecor(view)
    }

    fun overlayBlocked(): Boolean = controller?.overlayPermissionLooksBlocked() == true
}
