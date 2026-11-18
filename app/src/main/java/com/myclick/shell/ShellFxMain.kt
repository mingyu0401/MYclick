package com.myclick.shell

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.myclick.core.Config
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 「Shizuku 模式」的绘制进程入口（以 shell 身份运行的 app_process）。
 *
 * 为什么要有这个进程：ColorOS 会为第三方 App 的 `TYPE_APPLICATION_OVERLAY`
 * 窗口弹「正在当前页面悬浮显示」提示，且 `app_process` 起的进程没在 AMS 注册，
 * `openSession()` 直接失败，根本无法用窗口。改用 shell 身份 + SurfaceControl
 * root 图层后，既没有窗口也没有 AppOps 操作，系统不会再提示任何悬浮窗信息。
 *
 * 进程职责只有三件：建图层、按点击坐标画特效、自己问系统是否在录屏。
 * 配置与坐标由 App 进程通过 stdin 的文本行推进来（协议见 [ShellFxProto]）。
 *
 * 启动方式（App 侧 [com.myclick.access.ShellFxHost] 用 Shizuku 起）：
 * ```
 * app_process -Djava.class.path=<base.apk> /system/bin com.myclick.shell.ShellFxMain
 * ```
 */
object ShellFxMain {

    @JvmStatic
    fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        // 渲染节拍跑在 main looper 上：提到 DISPLAY 优先级（-4），
        // 否则会被同进程里读触摸 / 轮询录屏的普通线程抢占，表现为偶发掉帧。
        // shell / root 身份才有 CAP_SYS_NICE，普通环境下设置失败也不影响功能。
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
        // 解 hidden API 限制（SurfaceControl 的 setLayerStack / setMatrix 都是 @hide）。
        // 放到独立线程：万一 VMRuntime 那边卡住，也不能拖住主流程起图层。
        Thread({ HiddenApi.unlock() }, "fx-hiddenapi").apply {
            isDaemon = true
            start()
        }
        val handler = Handler(Looper.getMainLooper())

        val context = systemContext()
        if (context == null) {
            println("FX ERR no-system-context")
            return
        }
        initAppSharedMemory()

        val uiMode = context.resources.configuration.uiMode
        val renderer = ShellFxRenderer(context)
        val recorder = ShellRecorderState(context) { on -> handler.post { renderer.setRecording(on) } }
        // 触摸源也跑在这里：App 进程被 ColorOS 回收后特效仍要继续工作
        val touch = ShellTouchSource(context) { x, y -> handler.post { renderer.emit(x, y) } }
        touch.start()
        ShellPidFile.write()

        // 可选启动参数：pkg=<录屏包名> cfg=<配置行>，方便 adb 手工调试
        args.forEach { arg ->
            when {
                arg.startsWith("pkg=") -> recorder.start(arg.removePrefix("pkg="))
                arg.startsWith("cfg=") -> ShellFxProto.decodeConfig(arg.removePrefix("cfg="))
                    ?.let { renderer.applyConfig(it, uiMode) }
            }
        }

        Thread({ stdinLoop(handler, renderer, recorder, uiMode) }, "fx-stdin").apply {
            isDaemon = true
            start()
        }
        println("FX READY uid=${Process.myUid()} touch=${touch.currentDevice()?.path ?: "探测中"}")
        Looper.loop()
    }

    private fun stdinLoop(
        handler: Handler,
        renderer: ShellFxRenderer,
        recorder: ShellRecorderState,
        uiMode: Int
    ) {
        var lines = 0
        try {
            BufferedReader(InputStreamReader(System.`in`)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    lines++
                    if (!dispatch(handler, renderer, recorder, uiMode, line)) break
                }
            }
        } catch (t: Throwable) {
            println("FX stdin err: $t")
        }
        println("FX stdin eof after $lines lines")
        // 关键：App 被系统回收时写端会 EOF，但这里**不能退出** ——
        // 用户经常是「打开 App 开好特效 → 退出 App → 去录屏」，退出即失效就等于不能用。
        // 触摸源、录制判定、绘制都在本进程里，继续跑即可；真正停止只认 App 发来的 quit。
        println("FX 进入独立运行模式（等待新的 App 连接或 quit）")
    }

    /** @return false 表示收到 quit，需要结束读取循环 */
    private fun dispatch(
        handler: Handler,
        renderer: ShellFxRenderer,
        recorder: ShellRecorderState,
        uiMode: Int,
        raw: String
    ): Boolean {
        val line = raw.trim()
        if (line.isEmpty()) return true
        if (line == ShellFxProto.CMD_QUIT) {
            handler.post {
                renderer.release()
                ShellPidFile.clear()
                Looper.getMainLooper().quitSafely()
            }
            return false
        }

        when {
            line.startsWith("cfg ") -> {
                val cfg = ShellFxProto.decodeConfig(line.substring(4))
                if (cfg == null) {
                    println("FX bad cfg: $line")
                } else {
                    handler.post { renderer.applyConfig(cfg, uiMode) }
                }
            }
            line.startsWith("pkg ") -> recorder.start(line.substring(4))
            line.startsWith("tap ") -> {
                val parts = line.substring(4).split(' ')
                val x = parts.getOrNull(0)?.toFloatOrNull() ?: return true
                val y = parts.getOrNull(1)?.toFloatOrNull() ?: return true
                handler.post { renderer.emit(x, y) }
            }
            else -> println("FX unknown: $line")
        }
        return true
    }

    /**
     * 拿到 system context（package = android，uid = shell）。
     *
     * `ActivityThread.systemMain()` 会顺带完成 instrumention / SystemContext 初始化，
     * 是 app_process 里最省事的入口；它不 attach 到 AMS，所以不会触发
     * 「Unknown pid」那类校验 —— 我们本来也不需要窗口会话。
     */
    private fun systemContext(): Context? = runCatching {
        val cls = Class.forName("android.app.ActivityThread")
        val thread = cls.getMethod("systemMain").invoke(null)
        cls.getMethod("getSystemContext").invoke(thread) as Context
    }.onFailure { println("FX ctx fail: $it") }.getOrNull()

    /**
     * ColorOS 在 View / 资源初始化路径上会取 `ApplicationSharedMemory`，
     * app_process 里没人给它赋值就会抛 `ApplicationSharedMemory not initialized`。
     * 我们不用 View，但相邻的 framework 调用仍可能踩到，这里补一个自建实例兜底。
     */
    private fun initAppSharedMemory() {
        runCatching {
            val cls = Class.forName("com.android.internal.os.ApplicationSharedMemory")
            val inst = cls.getMethod("create").invoke(null)
            cls.getDeclaredMethod("setInstance", cls).invoke(null, inst)
        }.onFailure { println("FX asm skip: $it") }
    }
}
