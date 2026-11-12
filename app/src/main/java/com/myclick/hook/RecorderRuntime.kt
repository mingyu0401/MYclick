package com.myclick.hook

import android.content.Context
import android.view.View
import com.myclick.bridge.HookBridge
import com.myclick.core.Config
import com.myclick.core.ConfigStore
import com.myclick.core.Contexts
import com.myclick.core.RecordingState
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

        // 录制刚开始时重新拉一次配置：录屏 App 常驻后台时可能错过 CONFIG_PUSH，
        // 快照里「全局生效」还是旧值，一开录就会和全局窗口叠成两份。
        // 刷新完成后会经 ConfigStore.onUpdated → onConfig → reconcile 立即生效。
        RecordingState.onStart = { ConfigStore.refreshAsync(app) }

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

    /** 归属标记翻转时重建 / 撤掉本进程窗口（走同一套 reconcile 逻辑） */
    fun refreshOverlay() {
        controller?.updateConfig(ConfigStore.value)
    }

    fun attachDecor(view: View) {
        controller?.attachToDecor(view)
    }

    fun overlayBlocked(): Boolean = controller?.overlayPermissionLooksBlocked() == true
}
