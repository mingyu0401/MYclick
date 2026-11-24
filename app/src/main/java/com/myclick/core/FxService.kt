package com.myclick.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.myclick.access.ShellFxHost
import com.myclick.ui.SettingsActivity

/**
 * 保活用的前台服务。
 *
 * 为什么需要它：Shizuku 的 `newProcess()` 会把子进程和调用方绑定 ——
 * 实测 App 被 ColorOS 回收（几秒）后，绘制进程会跟着被 Shizuku 杀掉，
 * 用户「开好特效 → 退出 App → 去录屏」就完全没用了。
 * 起一个前台服务能把 App 进程的优先级提到「前台」，系统不会随手清掉它，
 * 配合 `START_STICKY` 即使被清也会被拉回来（回来时 [ShellFxHost] 会顺便清掉残留进程）。
 *
 * 通知常驻是这类工具的代价，所以做得尽量轻：低优先级、无声音、点一下回设置页。
 */
class FxService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFY_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ShellFxHost.stop(this)
            stopSelf()
            return START_NOT_STICKY
        }
        val cfg = runCatching { Prefs.read(this) }.getOrNull()
        if (cfg != null && cfg.shizukuMode && cfg.effectOn()) {
            ShellFxHost.sync(this, cfg)
        } else {
            // 开关已经关了：别留一个空转的前台服务
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "点击特效",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持点击特效在后台运行"
                setShowBadge(false)
            }
            nm?.createNotificationChannel(ch)
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, FxService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("MYclick 正在运行")
            .setContentText("录屏时会在点击处绘制特效")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(null, "停止", stop).build()
            )
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "myclick_fx"
        private const val NOTIFY_ID = 0x4D43
        private const val ACTION_STOP = "com.myclick.STOP_FX"

        /** 按当前配置启停前台服务；幂等 */
        fun sync(context: Context) {
            val cfg = runCatching { Prefs.read(context) }.getOrNull() ?: return
            val want = cfg.effectOn() && cfg.shizukuMode
            val intent = Intent(context, FxService::class.java)
            if (want) {
                runCatching { context.startForegroundService(intent) }
            } else {
                runCatching { context.stopService(intent) }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FxService::class.java)) }
        }
    }
}
