package com.myclick.core

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 运行时探测本机真实安装的「系统屏幕录制」应用包名。
 *
 * 优先级：PackageManager 已安装列表 → `pm list packages -s`。两者都只返回这台设备上
 * 实际存在的包；只有完全找不到时才退回 [Config.KNOWN_TARGETS] 作为兜底，
 * 保证设置页里的「目标包名」始终自动选定为用户手机上的那个录屏 App。
 * 不依赖 root；`pm list packages -s` 在普通应用进程里也能执行。
 */
object PackageDetector {

    private val KEYWORDS = listOf(
        "screenrecorder", "screenrecord", "screen_record", "screenrecording", "screencap"
    )

    /** 本机真实安装的录屏包；按已知包名优先级排序，保证结果稳定。 */
    fun detect(context: Context?): List<String> {
        val installed = LinkedHashSet<String>()
        collectFromPackageManager(context, installed)
        collectFromPm(installed)
        val found = installed.filter { looksLikeRecorder(it) }.distinct()
        if (found.isEmpty()) return Config.KNOWN_TARGETS.filter { looksLikeRecorder(it) }

        val preferred = Config.KNOWN_TARGETS.filter { it in found }
        return (preferred + (found - preferred.toSet())).distinct()
    }

    /** 自动选择的目标包名：优先已知 OEM 包名，否则取探测到的第一个。 */
    fun detectBest(context: Context?): String? = detect(context).firstOrNull()

    /** 该包名是否真实安装在本机。 */
    fun isInstalled(context: Context?, pkg: String): Boolean {
        if (context == null || pkg.isBlank()) return false
        return runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(pkg, 0) != null
        }.getOrDefault(false)
    }

    private fun looksLikeRecorder(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return KEYWORDS.any { lower.contains(it) }
    }

    private fun collectFromPackageManager(context: Context?, out: MutableSet<String>) {
        if (context == null) return
        try {
            @Suppress("DEPRECATION")
            val list = context.packageManager.getInstalledPackages(0)
            list.forEach { if (looksLikeRecorder(it.packageName)) out.add(it.packageName) }
        } catch (t: Throwable) {
            // ignore
        }
    }

    private fun collectFromPm(out: MutableSet<String>) {
        var reader: BufferedReader? = null
        try {
            val process = Runtime.getRuntime().exec(arrayOf("pm", "list", "packages", "-s"))
            reader = BufferedReader(InputStreamReader(process.inputStream))
            reader.forEachLine { line ->
                val pkg = line.removePrefix("package:").trim()
                if (looksLikeRecorder(pkg)) out.add(pkg)
            }
            runCatching { process.waitFor() }
            process.destroy()
        } catch (t: Throwable) {
            // ignore
        } finally {
            runCatching { reader?.close() }
        }
    }
}
