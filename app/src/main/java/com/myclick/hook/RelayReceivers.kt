package com.myclick.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.myclick.core.Config
import com.myclick.core.ConfigStore
import com.myclick.core.TouchBus
import com.myclick.core.TouchRelay

object RelayReceivers {
    /**
     * 必须用 RECEIVER_EXPORTED：事件来自 system_server 或别的 App（不同 UID），
     * Android 13+ 默认 NOT_EXPORTED 会把跨进程广播丢掉。安全性由 nonce 校验兜底。
     */
    fun register(context: Context, receiver: BroadcastReceiver, vararg actions: String): Boolean {
        val filter = IntentFilter()
        actions.forEach { filter.addAction(it) }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
            true
        } catch (t: Throwable) {
            false
        }
    }
}

/** system_server 里接收设置页推送的配置快照 */
class ConfigPushReceiver(private val onConfig: ((Config) -> Unit)? = null) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val config = TouchRelay.parseConfig(intent) ?: return
        ConfigStore.applyPush(config)
        onConfig?.invoke(config)
    }
}

/** 录屏进程里接收 ACTION_DOWN 坐标 / 配置推送 / 本地测试点击 */
class TouchRelayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            TouchRelay.ACTION_TOUCH -> {
                val expected = ConfigStore.value.nonce
                val got = intent.getStringExtra(TouchRelay.EX_NONCE)
                val event = TouchRelay.parseTouch(intent, expected)
                com.myclick.core.Diag.log(
                    "relay",
                    "touch recv nonce=$got expected=$expected parsed=$event " +
                        "sink=${TouchBus.sink != null}"
                )
                if (event != null) TouchBus.dispatch(event)
            }
            TouchRelay.ACTION_CONFIG_PUSH -> {
                val config = TouchRelay.parseConfig(intent) ?: return
                ConfigStore.applyPush(config)
                RecorderRuntime.onConfig(config)
            }
            TouchRelay.ACTION_TEST_TOUCH -> {
                // 设置页「预览」按钮：在录屏进程里触发一次居中特效
                val x = intent.getFloatExtra(TouchRelay.EX_X, -1f)
                val y = intent.getFloatExtra(TouchRelay.EX_Y, -1f)
                if (x >= 0f && y >= 0f) {
                    TouchBus.dispatch(
                        com.myclick.core.TouchEvent(x, y, 0, android.os.SystemClock.uptimeMillis())
                    )
                }
            }
        }
    }
}
