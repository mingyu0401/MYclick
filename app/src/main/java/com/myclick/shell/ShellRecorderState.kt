package com.myclick.shell

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * shell 进程内自己判定「是否正在录屏」。
 *
 * 判据：系统里存在一块**归属录屏 App 的私有虚拟屏**。ColorOS 开始录屏会新建
 * `DisplayInfo{"OPLUSScreenRecording", flags=FLAG_PRIVATE|FLAG_SECURE, type VIRTUAL}`，
 * 停录即销毁。
 *
 * 这里直接问 DisplayManager，而不是像旧版那样 fork `cmd display get-displays`：
 * app_process 起的进程 SELinux 域受限，`cmd display` 这种 shell 命令拿不到输出
 * （实测始终返回空 → 永远判成「没在录」，关掉全局生效后特效再也不出）。
 * DisplayManager 是本地服务，只要 uid 有 `INTERACT_ACROSS_USERS`（shell/root 都有）就能看到。
 *
 * 注意不能按「有虚拟屏就算在录」：ColorOS 的 `com.oplus.cast`（手机投屏）会长期
 * 占着一块 1272x2772 的私有虚屏，必须按名字里的 recording 标识区分。
 */
class ShellRecorderState(
    context: Context,
    private val onState: (Boolean) -> Unit
) {

    private val displayManager: DisplayManager? = context.getSystemService(DisplayManager::class.java)

    @Volatile
    private var running = false

    private var thread: Thread? = null

    @Volatile
    private var recording = false

    @Synchronized
    fun start(pkg: String) {
        if (running) return
        running = true
        thread = Thread({ loop(pkg) }, "fx-rec").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun loop(pkg: String) {
        while (running) {
            val now = isRecording(pkg)
            if (now != recording) {
                recording = now
                println(if (now) "FX rec on" else "FX rec off")
                onState(now)
            }
            sleep(400)
        }
    }

    private fun isRecording(pkg: String): Boolean {
        val dm = displayManager ?: return recording
        val displays = runCatching { dm.displays }.getOrNull() ?: return recording
        for (d in displays) {
            if (d.displayId == Display.DEFAULT_DISPLAY) continue
            val name = runCatching { d.name }.getOrDefault("").orEmpty()
            val private = d.flags and Display.FLAG_PRIVATE != 0
            if (!private && d.flags and Display.FLAG_PRESENTATION == 0) continue
            // 只认录屏那一块：名字里必须带 recording / screenrecord，且不能是投屏
            val lower = name.lowercase()
            if (lower.contains("cast")) continue
            if (lower.contains("screenrecord") || lower.contains("recording")) return true
            // 兜底：唯一 ID 里带录屏包名
            val unique = runCatching {
                d.javaClass.getMethod("getUniqueId").invoke(d) as? String
            }.getOrNull().orEmpty()
            if (unique.contains(pkg, ignoreCase = true)) return true
        }
        return false
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }
}
