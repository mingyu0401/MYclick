package com.myclick.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 模块自身进程里的配置服务台。
 *
 * 被 Hook 进程（录屏 App / system_server）因为 Android 11+ 包可见性限制，
 * 无法解析 com.myclick.config ContentProvider，因此启动时发一条显式
 * CONFIG_REQUEST 广播到这里；这里读出 SharedPreferences 并回推 CONFIG_PUSH。
 */
class ConfigRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TouchRelay.ACTION_CONFIG_REQUEST) return
        try {
            TouchRelay.sendConfig(context, Prefs.read(context))
        } catch (t: Throwable) {
            // 静态接收器里绝不能抛异常
        }
    }
}
