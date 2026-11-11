package com.myclick.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机 / 模块升级后把配置主动推给已经 Hook 好的 system_server 与录屏进程。 */
class BootPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.LOCKED_BOOT_COMPLETED" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            TouchRelay.sendConfig(context, Prefs.read(context))
        }
    }
}
