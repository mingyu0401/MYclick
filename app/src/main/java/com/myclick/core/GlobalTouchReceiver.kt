package com.myclick.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 「全局生效」的静态入口，默认在 Manifest 里 disabled。
 *
 * system_server 的框架 Hook 每次 ACTION_DOWN 都会显式投递一条 GLOBAL_TOUCH；
 * 组件禁用时系统直接丢弃广播，不会为了日常点击唤醒模块进程。设置页打开
 * 「全局生效」时才启用组件，因此关闭状态下零额外开销。
 *
 * 是否绘制完全由本进程自己读 SharedPreferences 决定，不依赖被 Hook 进程里
 * 可能已经过期的配置快照；nonce 校验挡住其它 App 伪造的广播。
 */
class GlobalTouchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            val cfg = Prefs.read(context)
            if (!cfg.enabled) return
            if (!cfg.globalEffect && cfg.gateMode != Config.GATE_ALWAYS) return
            val event = TouchRelay.parseTouch(intent, cfg.nonce) ?: return
            val app = context.applicationContext ?: context
            GlobalFxHost.dispatch(app, cfg, event)
        } catch (t: Throwable) {
            // 静态接收器里绝不能抛异常，否则会拖垮广播队列
        }
    }
}
