package com.myclick.core

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Hook 失败时的降级探测：找出设备上所有「像屏幕录制」的系统包。
 * 不依赖 root；`pm list packages -s` 在普通应用进程里也能执行。
 */
object PackageDetector {

    private val KEYWORDS = listOf("screenrecorder", "screenrecord", "screen_record", "screenrecording", "screencap")

    fun detect(context: Context?): List<String> {
        val out = LinkedHashSet<String>(Config.KNOWN_TARGETS)
        collectFromPackageManager(context, out)
        collectFromPm(out)
        return out.filter { looksLikeRecorder(it) }.distinct()
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
