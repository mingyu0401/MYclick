package com.myclick.core

import android.content.Context
import android.provider.Settings
import com.myclick.overlay.OverlayController

/**
 * 模块自身进程里的「全局生效」覆盖层宿主。
 *
 * 为什么需要它：原架构的覆盖层只挂在录屏 App 进程里，而该进程随时可能没在跑
 * （force-stop、内存回收、用户从没启动过录屏），此时即使打开「全局生效」也收不到
 * 系统框架发来的 ACTION_TOUCH，特效完全不出现。
 *
 * 全局模式改为由 system_server 额外发一条显式广播到 GlobalTouchReceiver，
 * 由本进程统一绘制。窗口挂上后系统会提升进程优先级，之后每次点击都是热投递。
 * 这里复用同一套 OverlayController / EffectSurfaceView，视觉与录屏内完全一致，
 * 同样受 isCaptureDisplay() 保护，不会往 MediaProjection 虚拟屏上加窗口。
 */
object GlobalFxHost {

    @Volatile
    private var controller: OverlayController? = null

    @Volatile
    private var lastConfig: Config? = null

    @Volatile
    private var permissionAttempted = false

    @Synchronized
    fun dispatch(app: Context, cfg: Config, ev: TouchEvent) {
        val ctrl = controller ?: OverlayController(app, globalHost = true).also { controller = it }

        // onTouch 内部读 ConfigStore.value 判断门控，必须先同步成最新快照
        if (cfg != lastConfig) {
            ConfigStore.applyPush(cfg)
            lastConfig = cfg
        }

        if (!Settings.canDrawOverlays(app) && !permissionAttempted) {
            permissionAttempted = true
            // LSPosed 环境一般有 root / Shizuku；静默放行一次。
            // 若失败，attach() 会捕获异常并在下一次点击自动重试。
            Thread({ RootHelper.grantOverlay(app.packageName) }, "MYclick-GrantOverlay").start()
        }

        // updateConfig 会 post 到主线程执行 reconcile → attach（幂等）；
        // 与 onTouch 的 post 在同一 Handler 上排队，保证先有窗口再画粒子。
        ctrl.updateConfig(cfg)
        ctrl.onTouch(ev)
    }
}
