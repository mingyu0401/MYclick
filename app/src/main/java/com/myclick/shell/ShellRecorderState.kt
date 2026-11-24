package com.myclick.shell

import android.content.Context
import android.graphics.Point
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
    private val onState: (Boolean) -> Unit,
    /**
     * 录屏虚拟屏集合（停录时为空）。
     *
     * 绘制端的 root 图层只挂在主屏 layerStack 上，而录屏虚拟屏只镜像主屏
     * `DisplayContent` 那棵树（SurfaceFlinger 里的 `MirrorRoot`），所以特效默认录不进去；
     * 拿到虚拟屏的 layerStack 和尺寸后，绘制端会把特效图层镜像一份过去并按同样比例缩放。
     */
    private val onCaptureTargets: (Set<CaptureTarget>) -> Unit = {}
) {

    /**
     * 一块需要镜像特效的录屏虚拟屏。
     *
     * [srcWidth]/[srcHeight] 是主屏**当前方向**的尺寸：主屏一旋转这两个值就变，
     * 轮询发现变化会重新回调，绘制端据此重算缩放。
     */
    data class CaptureTarget(
        val layerStack: Int,
        val width: Int,
        val height: Int,
        val srcWidth: Int,
        val srcHeight: Int
    )

    private val displayManager: DisplayManager? = context.getSystemService(DisplayManager::class.java)

    @Volatile
    private var running = false

    private var thread: Thread? = null

    @Volatile
    private var recording = false

    private var targets: Set<CaptureTarget> = emptySet()

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
            val found = recordingDisplays(pkg)
            if (found != null) {
                val now = found.isNotEmpty()
                if (now != recording) {
                    recording = now
                    println(if (now) "FX rec on" else "FX rec off")
                    onState(now)
                }
                val src = realSize(displayManager?.getDisplay(Display.DEFAULT_DISPLAY))
                val nextTargets = found.map { d ->
                    val size = realSize(d)
                    CaptureTarget(layerStackOf(d), size.x, size.y, src.x, src.y)
                }.toSet()
                if (nextTargets != targets) {
                    targets = nextTargets
                    println("FX rec targets=$nextTargets")
                    onCaptureTargets(nextTargets)
                }
            }
            sleep(400)
        }
    }

    /** 当前所有录屏虚拟屏；拿不到 DisplayManager 时返回 null（保持上一次状态） */
    private fun recordingDisplays(pkg: String): List<Display>? {
        val dm = displayManager ?: return null
        val displays = runCatching { dm.displays }.getOrNull() ?: return null
        return displays.filter { isRecordingDisplay(it, pkg) }
    }

    private fun isRecordingDisplay(d: Display, pkg: String): Boolean {
        if (d.displayId == Display.DEFAULT_DISPLAY) return false
        val name = runCatching { d.name }.getOrDefault("").orEmpty()
        val private = d.flags and Display.FLAG_PRIVATE != 0
        if (!private && d.flags and Display.FLAG_PRESENTATION == 0) return false
        // 只认录屏那一块：名字里必须带 recording / screenrecord，且不能是投屏
        val lower = name.lowercase()
        if (lower.contains("cast")) return false
        if (lower.contains("screenrecord") || lower.contains("recording")) return true
        // 兜底：唯一 ID 里带录屏包名
        val unique = runCatching {
            d.javaClass.getMethod("getUniqueId").invoke(d) as? String
        }.getOrNull().orEmpty()
        return unique.contains(pkg, ignoreCase = true)
    }

    /**
     * 虚拟屏在 SurfaceFlinger 里的 layerStack。
     *
     * `DisplayInfo.layerStack` 是 @hide 字段（进程入口已解 hidden API 限制）；
     * 读不到时退回 displayId —— 虚拟屏的 layerStack 由 DisplayManager 按 displayId 分配，
     * 实测投屏虚拟屏 displayId 10000 对应 layerStack 10000。
     */
    /** 显示屏当前方向的物理像素尺寸；拿不到时为 0x0（绘制端会退回不缩放） */
    private fun realSize(d: Display?): Point {
        val p = Point()
        if (d != null) {
            @Suppress("DEPRECATION")
            runCatching { d.getRealSize(p) }
        }
        return p
    }

    private fun layerStackOf(d: Display): Int = runCatching {
        val infoCls = Class.forName("android.view.DisplayInfo")
        val info = infoCls.getConstructor().newInstance()
        Display::class.java.getMethod("getDisplayInfo", infoCls).invoke(d, info)
        infoCls.getField("layerStack").getInt(info)
    }.getOrDefault(d.displayId)

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }
}
