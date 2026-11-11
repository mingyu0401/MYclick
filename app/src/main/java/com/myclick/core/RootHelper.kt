package com.myclick.core

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 可选 root 辅助。完全不依赖 root 也能工作（纯 Xposed 路径）：
 * 录屏 App 是系统签名应用，本身已声明 SYSTEM_ALERT_WINDOW；root 只用来在
 * AppOps 被 OEM 策略拒绝时兜底放行。
 */
object RootHelper {

    data class Result(val ok: Boolean, val log: String)

    fun isRootAvailable(): Boolean = su("id")?.contains("uid=0") == true

    /** 放行悬浮窗权限；不同 ColorOS 版本 AppOps 名称略有差异，逐个尝试。 */
    fun grantOverlay(packageName: String): Result {
        val sb = StringBuilder()
        var ok = false
        val commands = listOf(
            "appops set $packageName SYSTEM_ALERT_WINDOW allow",
            "appops set $packageName android:system_alert_window allow",
            "cmd appops set $packageName SYSTEM_ALERT_WINDOW allow",
            "pm grant $packageName android.permission.SYSTEM_ALERT_WINDOW"
        )
        for (cmd in commands) {
            val out = su(cmd)
            sb.append("$ ").append(cmd).append('\n')
            if (!out.isNullOrBlank()) sb.append(out.trim()).append('\n')
            if (out == null || (!out.contains("Error", true) && !out.contains("Exception", true))) ok = true
        }
        return Result(ok, sb.toString())
    }

    /** 可选：把原生「显示点按反馈」强制关掉（我们自己的特效取代它） */
    fun disableNativeTouchDot(): Result {
        val out = su("settings put system show_touches 0")
        return Result(out != null, out ?: "su unavailable")
    }

    fun su(command: String): String? {
        var reader: BufferedReader? = null
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            reader = BufferedReader(InputStreamReader(p.inputStream))
            val text = reader.readText().trim()
            runCatching { p.waitFor() }
            p.destroy()
            text
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { reader?.close() }
        }
    }
}
