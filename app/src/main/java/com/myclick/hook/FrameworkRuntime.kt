package com.myclick.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.myclick.bridge.HookBridge
import com.myclick.bridge.Phase
import com.myclick.core.ConfigStore
import com.myclick.core.Contexts
import com.myclick.core.TouchBus
import com.myclick.overlay.OverlayController

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

    @Volatile
    private var globalOverlay: OverlayController? = null

    @Volatile
    private var overlaySetup = false

    @Synchronized
    fun install(bridge: HookBridge, classLoader: ClassLoader?) {
        if (installed) return
        installed = true
        ConfigStore.refreshAsync(Contexts.any())
        tryRegister(Contexts.any())
        setupGlobalOverlay(Contexts.any(), bridge)

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

        val hook = InputEventHook(bridge, "framework", true, classLoader, globalAuthority = true)
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
        setupGlobalOverlay(context, bridge)
    }

    /**
     * 「全局生效」的宿主直接放在 system_server 里：它永远在线、拥有系统 UID（不需要
     * 悬浮窗授权），而且框架输入 Hook 就在同一进程，ACTION_DOWN 可以直接交给
     * TouchBus，无跨进程广播、无后台启动限制。
     *
     * 真机上 ColorOS 会拦截从 system_server 发给普通 App 静态接收器的广播，
     * 所以模块 App 进程那条路径只作为兜底保留。
     */
    private fun setupGlobalOverlay(context: Context?, bridge: HookBridge) {
        val ctx = context ?: return
        if (overlaySetup) return
        overlaySetup = true
        Handler(Looper.getMainLooper()).post {
            try {
                val ctrl = OverlayController(ctx, globalHost = true)
                globalOverlay = ctrl
                TouchBus.sink = { ev -> ctrl.onTouch(ev) }
                ConfigStore.onUpdated = { cfg -> ctrl.updateConfig(cfg) }
                ctrl.updateConfig(ConfigStore.value)
                bridge.log("[android] 全局生效宿主已挂载（system_server）")
            } catch (t: Throwable) {
                overlaySetup = false
                bridge.log("[android] 全局生效宿主挂载失败: $t")
            }
        }
    }

    private fun tryRegister(context: Context?) {
        if (context == null) return
        ConfigPushRegistrar.ensure(context)
    }

    /**
     * 全局宿主是否已经真的挂上窗口。
     *
     * 只有窗口就绪时才敢告诉录屏进程「这一击归我画」；否则会出现两边都以为
     * 对方在画、或者两边同时画的情况。`attached` 是 volatile，输入线程可直接读。
     */
    fun globalOverlayReady(): Boolean = globalOverlay?.isAttached() == true

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
