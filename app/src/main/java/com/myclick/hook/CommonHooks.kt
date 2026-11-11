package com.myclick.hook

import android.content.Context
import com.myclick.bridge.HookBridge
import com.myclick.bridge.Phase
import com.myclick.core.ConfigStore
import com.myclick.core.Contexts
import com.myclick.core.TouchRelay

/** 所有被 Hook 的进程都要：尽早拿 Context + 接收配置推送。 */
class CommonHooks(private val bridge: HookBridge) {
    fun install() {
        val wrapper = runCatching { Class.forName("android.content.ContextWrapper") }.getOrNull()
            ?: return
        bridge.hookAll(wrapper, "attachBaseContext", Phase.AFTER) { ctx ->
            val c = ctx.self as? Context ?: return@hookAll
            Contexts.offer(c)
            ConfigPushRegistrar.ensure(c)
            ConfigStore.refreshAsync(c)
        }
    }
}

object ConfigPushRegistrar {
    @Volatile
    private var registered = false

    @Synchronized
    fun ensure(context: Context) {
        if (registered) return
        // 注册失败（例如 system_server 早期 Context 还不可用）时不置位，后续仍会重试
        registered = RelayReceivers.register(
            context, ConfigPushReceiver(), TouchRelay.ACTION_CONFIG_PUSH
        )
    }
}
