package com.myclick.touch

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import com.myclick.core.Trace
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.InputStream

/**
 * 提权执行通道。
 *
 * 「Shizuku 模式」需要以 `shell` 用户身份读 `/dev/input/event*` —— 该节点属于
 * `root:input`，而 shell 用户正好在 `input` 组里，所以只要有 shell 身份就够了，
 * 不需要 root。
 *
 * 两条通道，按优先级：
 *  1. **Shizuku**：`IShizukuService.newProcess()` 直接给一个以 shell 运行的进程。
 *     注意 `Shizuku.newProcess()` 在 13.x 已改为 private，所以这里直接拿 binder 调接口。
 *  2. **su**：设备已 root 时直接 `su -c`，零配置。
 *
 * 都不可用时返回 NONE，由上层明确提示，不做静默降级。
 */
object PrivilegedShell {

    enum class Backend { SHIZUKU, ROOT, NONE }

    /** 统一的进程句柄：Shizuku 的远端进程和本地 su 进程都能包成它 */
    class Handle(
        val input: InputStream,
        /** 进程 stdin；为 null 表示这条通道拿不到写端 */
        val output: java.io.OutputStream? = null,
        private val closer: () -> Unit,
        private val waiter: () -> Int
    ) {
        /** 往子进程写一行命令；失败时返回 false，由调用方决定是否重启 */
        fun send(line: String): Boolean = runCatching {
            val out = output ?: return false
            out.write((line + "\n").toByteArray())
            out.flush()
            true
        }.getOrDefault(false)

        fun destroy() {
            runCatching { closer() }
        }

        fun waitFor(): Int = runCatching { waiter() }.getOrDefault(-1)
    }

    @Volatile
    private var rootChecked = false

    @Volatile
    private var rootAvailable = false

    fun shizukuReady(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Shizuku 在跑但还没授权给本应用 */
    fun shizukuNeedsGrant(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** su 是否可用；只探测一次，避免每次点击都 fork */
    fun rootReady(): Boolean {
        if (rootChecked) return rootAvailable
        rootChecked = true
        rootAvailable = runCatching {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            out.contains("uid=0")
        }.getOrDefault(false)
        return rootAvailable
    }

    fun backend(): Backend = when {
        shizukuReady() -> Backend.SHIZUKU
        rootReady() -> Backend.ROOT
        else -> Backend.NONE
    }

    fun describe(): String = when {
        shizukuReady() -> "Shizuku 已授权"
        shizukuNeedsGrant() -> "Shizuku 未授权（点击申请）"
        rootReady() -> "Root 可用"
        else -> "不可用（需要 Shizuku 或 root）"
    }

    fun requestShizukuPermission(requestCode: Int = 4201): Boolean = runCatching {
        Shizuku.requestPermission(requestCode)
        true
    }.getOrDefault(false)

    /** 以 shell / root 身份启动进程；命令用数组传递，不经 shell 拼接 */
    fun open(context: Context?, vararg cmd: String): Handle? {
        return try {
            when (backend()) {
                Backend.SHIZUKU -> {
                    val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
                        ?: return null
                    val rp = service.newProcess(arrayOf(*cmd), null, null) ?: return null
                    val stream = ParcelFileDescriptor.AutoCloseInputStream(rp.getInputStream())
                    val sink = runCatching {
                        rp.outputStream?.let { ParcelFileDescriptor.AutoCloseOutputStream(it) }
                    }.getOrNull()
                    Trace.log(context, "shell", "shizuku open ${cmd.joinToString(" ")}")
                    Handle(stream, sink, { rp.destroy() }, { rp.waitFor() })
                }
                Backend.ROOT -> {
                    val p = ProcessBuilder("su", "-c", cmd.joinToString(" "))
                        .redirectErrorStream(false)
                        .start()
                    Trace.log(context, "shell", "root open ${cmd.joinToString(" ")}")
                    Handle(p.inputStream, p.outputStream, { p.destroy() }, { p.waitFor() })
                }
                Backend.NONE -> {
                    Trace.log(context, "shell", "no backend for ${cmd.joinToString(" ")}")
                    null
                }
            }
        } catch (t: Throwable) {
            Trace.log(context, "shell", "open fail $t")
            null
        }
    }

    /** 执行一条命令并取回完整输出（探测设备表用，不用于长驻读流） */
    fun exec(context: Context?, vararg cmd: String): String? {
        val h = open(context, *cmd) ?: return null
        return try {
            h.input.bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            Trace.log(context, "shell", "exec fail $t")
            null
        } finally {
            h.destroy()
        }
    }

    /**
     * 以 shell 身份运行一段脚本（`sh -c`），用于常驻轮询。
     *
     * 脚本里不要出现单引号：su 通道要把整段脚本包进单引号里传给 `su -c`，
     * 含单引号会破坏转义。Shizuku 通道直接按数组传参，不受影响。
     */
    fun openScript(context: Context?, script: String): Handle? {
        return try {
            when (backend()) {
                Backend.SHIZUKU -> {
                    val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
                        ?: return null
                    val rp = service.newProcess(arrayOf("sh", "-c", script), null, null) ?: return null
                    val stream = ParcelFileDescriptor.AutoCloseInputStream(rp.getInputStream())
                    val sink = runCatching {
                        rp.outputStream?.let { ParcelFileDescriptor.AutoCloseOutputStream(it) }
                    }.getOrNull()
                    Handle(stream, sink, { rp.destroy() }, { rp.waitFor() })
                }
                Backend.ROOT -> {
                    val quoted = script.replace("'", "'\\''")
                    val p = ProcessBuilder("su", "-c", "sh -c '$quoted'")
                        .redirectErrorStream(false)
                        .start()
                    Handle(p.inputStream, p.outputStream, { p.destroy() }, { p.waitFor() })
                }
                Backend.NONE -> null
            }
        } catch (t: Throwable) {
            Trace.log(context, "shell", "openScript fail $t")
            null
        }
    }
}
