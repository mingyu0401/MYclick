package com.myclick.access

import android.content.Context
import com.myclick.core.Config
import com.myclick.core.Trace
import com.myclick.shell.ShellFxProto
import com.myclick.shell.ShellPidFile
import com.myclick.touch.PrivilegedShell

/**
 * 「Shizuku 模式」宿主：管理那个以 shell 身份运行的绘制进程。
 *
 * 之前的做法是在 App 进程里挂 `TYPE_APPLICATION_OVERLAY` 窗口，ColorOS 会每次
 * 都弹「正在当前页面悬浮显示」。现在把绘制搬到 shell 身份的子进程里，
 * 用 `SurfaceControl` 建 root 图层 —— 没有窗口、没有 AppOps，系统不再提示。
 *
 * 这个对象只做三件事：
 *  1. 用 Shizuku（或 root 兜底）启动 `app_process ... com.myclick.shell.ShellFxMain`；
 *  2. 把配置 / 点击坐标按行写进它的 stdin；
 *  3. 进程退出后清理，下次需要时重新拉起。
 */
object ShellFxHost {

    /** 当前子进程；非空且写成功就视为可用 */
    private var handle: PrivilegedShell.Handle? = null

    private var starting = false

    @Volatile
    private var lastConfig: Config? = null

    /** 日志用（Trace 需要 context 才会落盘）；sync 时缓存一次 */
    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var generation = 0

    fun isRunning(): Boolean = handle != null

    /** 设置页状态行 */
    fun statusText(): String = when {
        handle != null -> "运行中（后台独立进程）"
        starting -> "启动中…"
        else -> "未运行"
    }

    /**
     * 同步当前配置。总开关或 Shizuku 模式关掉时立刻收进程；
     * 需要启动时把耗时操作（Shizuku binder 往返）放到工作线程。
     */
    @Synchronized
    fun sync(context: Context, cfg: Config) {
        appContext = context.applicationContext ?: context
        lastConfig = cfg
        if (!cfg.enabled || !cfg.shizukuMode) {
            stopLocked(context, "config off")
            return
        }
        val h = handle
        if (h != null) {
            pushConfig(h, cfg)
            return
        }
        if (starting) return
        starting = true
        val gen = generation
        val app = context.applicationContext ?: context
        Thread({ boot(app, gen) }, "MYclick-ShellFx").apply {
            isDaemon = true
            start()
        }
    }

    private fun log(msg: String) = Trace.log(appContext, "host", msg)

    @Synchronized
    fun stop(context: Context?) {
        stopLocked(context, "stop")
    }

    private fun stopLocked(context: Context?, reason: String) {
        generation++
        starting = false
        val h = handle
        handle = null
        if (h != null) {
            Trace.log(appContext ?: context, "host", "停止绘制进程（$reason）")
            runCatching { h.send(ShellFxProto.CMD_QUIT) }
            runCatching { h.destroy() }
        } else if (context != null) {
            // App 曾在后台独立运行时被杀过，这里没有 handle，只能按 pid 文件去杀
            val app = context.applicationContext ?: context
            Thread({ killStale(app) }, "MYclick-ShellFx-stop").apply {
                isDaemon = true
                start()
            }
        }
    }

    // ---------------- 内部 ----------------

    private fun boot(context: Context, gen: Int) {
        val apk = runCatching { context.applicationInfo.sourceDir }.getOrNull()
        if (apk.isNullOrBlank()) {
            Trace.log(context, "host", "拿不到 APK 路径，无法启动绘制进程")
            synchronized(this) { starting = false }
            return
        }
        killStale(context)
        Trace.log(context, "host", "启动绘制进程 classpath=$apk")
        val cmd = arrayOf(
            "/system/bin/app_process",
            "-Djava.class.path=$apk",
            "/system/bin",
            MAIN_CLASS
        )
        val h = PrivilegedShell.open(context, *cmd)
        if (h == null) {
            Trace.log(context, "host", "提权通道不可用（Shizuku 未授权 / 无 root）")
            synchronized(this) {
                starting = false
            }
            return
        }
        // 进程的 stdout 必须有人读：写满管道会把它卡死
        Thread({ drain(h) }, "MYclick-ShellFx-out").apply {
            isDaemon = true
            start()
        }
        synchronized(this) {
            if (gen != generation) {
                runCatching { h.destroy() }
                starting = false
                return
            }
            handle = h
            starting = false
        }
        val cfg = lastConfig
        if (cfg != null && cfg.enabled && cfg.shizukuMode) {
            pushConfig(h, cfg)
        }
    }

    /**
     * 清掉上一次留下的绘制进程。
     *
     * 现在绘制进程在 App 退出后会独立运行（用户要的就是「退出 App 照样有特效」），
     * 所以 App 被系统杀掉再重新打开时，旧进程可能还活着。不先杀掉就会出现两个
     * 进程同时画、特效叠成两份。
     */
    private fun killStale(context: Context) {
        val pidText = runCatching {
            PrivilegedShell.exec(context, "/system/bin/cat", ShellPidFile.PATH)
        }.getOrNull()?.trim()
        val pid = pidText?.toIntOrNull() ?: return
        if (pid <= 0 || pid == android.os.Process.myPid()) return
        Trace.log(context, "host", "清理残留绘制进程 pid=$pid")
        PrivilegedShell.exec(context, "/system/bin/kill", pid.toString())
        Thread.sleep(250)
        // 绘制进程被强杀时，它拉起的 getevent 会变成孤儿继续占着触摸屏，顺手清掉
        runCatching { PrivilegedShell.exec(context, "/system/bin/pkill", "-f", "getevent -lt") }
    }

    private fun pushConfig(h: PrivilegedShell.Handle, cfg: Config) {
        val cfgLine = ShellFxProto.encodeConfig(cfg)
        val ok = h.send(cfgLine) && h.send("pkg ${cfg.targetPackage}")
        if (!ok) {
            log("配置下发失败")
            handle = null
            return
        }
        log(
            "配置已下发 scale=${cfg.scale} speed=${cfg.speed} theme=${cfg.themeMode} " +
                "global=${cfg.globalEffect} pkg=${cfg.targetPackage}"
        )
    }

    private fun drain(h: PrivilegedShell.Handle) {
        runCatching {
            h.input.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    log("sfx> $line")
                }
            }
        }
        log("绘制进程输出结束")
        // 进程退出：下次 sync / emit 时重新拉起
        if (handle === h) handle = null
    }

    /** app_process 的启动类名；只能用字面量，混淆后反射名字会变 */
    private const val MAIN_CLASS = "com.myclick.shell.ShellFxMain"
}
