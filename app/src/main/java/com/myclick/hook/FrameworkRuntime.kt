package com.myclick.hook

import android.content.Context
import com.myclick.bridge.HookBridge
import com.myclick.bridge.Phase
import com.myclick.core.ConfigStore
import com.myclick.core.Contexts

/**
 * system_server（LSPosed 作用域里的 "android" / 系统框架）里的运行时。
 *
 * 为什么必须有它：录屏 App 进程收不到别的窗口的 MotionEvent。
 * 全局输入事件在 system_server 由 InputManagerService.monitorInput() 建的输入通道
 * （PointerEventDispatcher extends InputEventReceiver）拿到，我们在那里读取
 * ACTION_DOWN 的 rawX / rawY / displayId，再用广播投递给录屏进程。
 */
object FrameworkRuntime {

    @Volatile
    private var installed = false

    @Synchronized
    fun install(bridge: HookBridge, classLoader: ClassLoader?) {
        if (installed) return
        installed = true
        ConfigStore.refreshAsync(Contexts.any())
        tryRegister(Contexts.any())

        // system_server 没有 Application，attachBaseContext 不会触发；
        // 用 AMS.systemReady / SystemServer.startBootstrapServices 抓 mSystemContext。
        val ams = bridge.findClass("com.android.server.am.ActivityManagerService", classLoader)
        if (ams != null) {
            bridge.hookAll(ams, "systemReady", Phase.AFTER) { ctx ->
                val c = readContext(ctx.self)
                if (c != null) onContext(bridge, c)
            }
        }
        val server = bridge.findClass("com.android.server.SystemServer", classLoader)
        if (server != null) {
            bridge.hookAll(server, "startBootstrapServices", Phase.AFTER) { ctx ->
                val c = readContext(ctx.self)
                if (c != null) onContext(bridge, c)
            }
        }

        val hook = InputEventHook(bridge, "framework", true, classLoader)
        hook.install()
        // 兼容不同 AOSP 分支：监视通道的 InputEventReceiver 子类可能直接覆写 onInputEvent。
        // 与 dispatchInputEvent 共享同一份去重状态，不会重复触发。
        hook.installExtra("com.android.server.wm.PointerEventDispatcher", "onInputEvent")
        hook.installExtra("com.android.server.input.PointerEventDispatcher", "onInputEvent")
        bridge.log("[android] 系统框架触摸源已安装")
    }

    private fun onContext(bridge: HookBridge, context: Context) {
        Contexts.offer(context)
        tryRegister(context)
        ConfigStore.refreshAsync(context)
    }

    private fun tryRegister(context: Context?) {
        if (context == null) return
        ConfigPushRegistrar.ensure(context)
    }

    /** 反射读取对象（含父类）里的 mContext / mSystemContext */
    private fun readContext(self: Any?): Context? {
        self ?: return null
        val names = listOf("mContext", "mSystemContext", "mUiContext")
        var clazz: Class<*>? = self.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (name in names) {
                try {
                    val f = clazz.getDeclaredField(name)
                    f.isAccessible = true
                    val value = f.get(self)
                    if (value is Context) return value
                } catch (t: Throwable) {
                    // 继续找
                }
            }
            clazz = clazz.superclass
        }
        return null
    }
}
