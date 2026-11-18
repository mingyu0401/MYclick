package com.myclick.touch

import android.content.Context
import com.myclick.core.PackageDetector
import com.myclick.core.RecordingState
import com.myclick.core.Trace

/**
 * 「是否正在录屏」的外部信号。
 *
 * 框架模式靠被 Hook 的录屏进程自己上报，Shizuku 模式没有这条通道
 * （免 root 场景根本没有 LSPosed，录屏进程里没有我们的代码），
 * 于是模块进程拿不到录制状态，之前只能一直画特效 —— 表现为关了「全局生效」
 * 之后日常使用照样满屏出特效。
 *
 * 这里用已有的 shell 通道自己问系统：录屏一开始，ColorOS 会新建一块归属录屏包的
 * 虚拟屏（`OPLUSScreenRecording`），停录即销毁；`cmd display get-displays` 的输出里
 * 直接带 `owner <包名>`，按配置里的录屏包名匹配即可。
 *
 * 注意不能简单地「有激活投屏就算在录屏」：ColorOS 的 `com.oplus.cast`
 * （手机投屏）会长期占着一路会话，而录屏那一路在 `dumpsys media_projection`
 * 里压根不登记 —— 只有显示器列表能反映真实状态。
 *
 * 实现上只起**一个**常驻 shell 进程，在设备侧循环取状态、只在变化时输出一行，
 * 应用侧读流即可，避免每次轮询都 fork 一个进程。
 */
object RecorderWatcher {

    /** 轮询间隔：录制开始到特效可用之间最多差这么多，太长会漏掉开机头的点击 */
    private const val POLL_INTERVAL = "0.7"

    @Volatile
    private var running = false

    private var handle: PrivilegedShell.Handle? = null

    private var worker: Thread? = null

    fun isRunning(): Boolean = running

    @Synchronized
    fun start(context: Context, targetPackage: String) {
        if (running) return
        val pkg = targetPackage.takeIf { it.isNotBlank() }
            ?: runCatching { PackageDetector.detectBest(context) }.getOrNull().orEmpty()
        if (pkg.isBlank()) {
            Trace.log(context, "watch", "没有可用的录屏包名，无法判定录制状态")
            return
        }
        running = true
        Trace.log(context, "watch", "启动录制状态监听 pkg=$pkg")
        worker = Thread({ loop(context.applicationContext ?: context, pkg) }, "MYclick-RecWatch").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        runCatching { handle?.destroy() }
        handle = null
        RecordingState.setActive(false)
    }

    private fun loop(context: Context, pkg: String) {
        while (running) {
            val h = PrivilegedShell.openScript(context, buildScript(pkg))
            if (h == null) {
                Trace.log(context, "watch", "无法启动监听进程，2s 后重试")
                sleepQuietly(2000)
                continue
            }
            handle = h
            try {
                h.input.bufferedReader().use { reader ->
                    while (running) {
                        val line = reader.readLine() ?: break
                        when (line.trim()) {
                            "R" -> {
                                RecordingState.setActive(true)
                                Trace.log(context, "watch", "录制开始，特效放行")
                            }
                            "I" -> {
                                RecordingState.setActive(false)
                                Trace.log(context, "watch", "录制结束，特效停止")
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Trace.log(context, "watch", "读取中断 $t")
            } finally {
                runCatching { h.destroy() }
                handle = null
            }
            // 流断了（进程被杀 / Shizuku 重启）：状态归零后重连，避免卡在「录制中」
            RecordingState.setActive(false)
            if (!running) break
            sleepQuietly(1500)
        }
        RecordingState.setActive(false)
    }

    /**
     * 设备侧循环：只在状态翻转时输出 R / I。
     *
     * 判据是「系统里存在一块归属录屏包的虚拟屏」。真机实测（ColorOS 17）：
     * 点开始录制后立即出现
     *   DisplayInfo{"OPLUSScreenRecording", displayId 17, ... owner com.oplus.screenrecorder}
     * 停止录制后立刻消失；而 `dumpsys media_projection` 里**根本没有**
     * 这一路会话（ColorOS 的录屏不走标准 MediaProjection 登记），所以不能按
     * 投屏会话判断，否则会一直判成「没在录」。
     *
     * `cmd display get-displays` 一次约 40ms，比 `dumpsys display`（80ms+）轻一半，
     * 且输出里直接带 owner，按包名匹配即可，也不会把长期占着的 com.oplus.cast 算进来。
     *
     * 整段脚本只用双引号，不含单引号：su 通道要把脚本塞进单引号里传给 `su -c`。
     */
    private fun buildScript(pkg: String): String {
        val pattern = "\"owner $pkg\""
        val q = "\""
        return buildString {
            append("last=; ")
            append("while true; do ")
            append("if cmd display get-displays 2>/dev/null | grep -q ").append(pattern)
            append("; then cur=R; else cur=I; fi; ")
            append("[ ").append(q).append("\$cur").append(q)
            append(" != ").append(q).append("\$last").append(q).append(" ] && echo ")
            append(q).append("\$cur").append(q)
            append(" && last=").append(q).append("\$cur").append(q).append("; ")
            append("sleep ").append(POLL_INTERVAL).append("; ")
            append("done")
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }
}
