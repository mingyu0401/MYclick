package com.myclick.touch

import android.content.Context
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import com.myclick.access.ShellFxHost
import com.myclick.core.Config
import com.myclick.core.Prefs
import com.myclick.core.Trace

/**
 * 「Shizuku 模式」的触摸事件源：常驻读触摸屏设备节点。
 *
 * 这条通道拿到的就是**手指按下的原始坐标**，不是控件中心，所以：
 *  - 点空白处、游戏、视频全屏控件都有特效；
 *  - 位置不会偏移；
 *  - 不需要无障碍，也就没有「正在读取屏幕内容」那类系统提示。
 *
 * 实现要点：
 *  - `getevent -lt <dev>` 的输出是纯文本事件流，按 `BTN_TOUCH DOWN` + `SYN_REPORT`
 *    组合判定一次按下，避免同一次按下被多个 SYN 重复触发；
 *  - 原始坐标是触摸面板分辨率（本机 20351x44351），必须按量程线性映射到屏幕像素；
 *  - 事件坐标固定在物理方向，屏幕旋转后要做一次变换，否则横屏时特效会跑到别处；
 *  - 读流阻塞，进程被 Shizuku 重启 / 被系统杀掉时会立刻感知并自动重连。
 *
 * **线程约定**：所有可能阻塞的提权探测（`su` 会等授权弹窗、Shizuku 要 binder 往返）
 * 一律放在工作线程，绝不放在主线程 —— 否则一次未授权的 `su` 就足以触发 ANR。
 */
object EvdevTouchSource {

    /** 用户期望的状态（开关） */
    @Volatile
    private var wanted = false

    /** 工作线程真的在收事件 */
    @Volatile
    private var running = false

    @Volatile
    private var cfg: Config? = null

    @Volatile
    private var device: TouchDevice? = null

    private var worker: Thread? = null
    private var handle: PrivilegedShell.Handle? = null

    fun isRunning(): Boolean = running

    fun currentDevice(): TouchDevice? = device

    /** 幂等；不阻塞调用方 */
    @Synchronized
    fun start(context: Context, config: Config) {
        cfg = config
        if (!config.enabled || !config.shizukuMode) {
            stop()
            return
        }
        wanted = true
        if (worker != null) return
        val app = context.applicationContext ?: context
        Trace.log(context, "evdev", "start 请求，转到后台探测提权")
        // 绘制端在 shell 进程里，这里先把进程拉起来并同步一次配置
        ShellFxHost.sync(app, config)
        worker = Thread({ boot(app) }, "MYclick-Evdev").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        wanted = false
        runCatching { handle?.destroy() }
        handle = null
        worker?.interrupt()
        worker = null
        running = false
        // 录制状态由 shell 进程自己轮询，这里不需要再管

    }

    fun onConfigChanged(context: Context, config: Config) {
        cfg = config
        if (config.enabled && config.shizukuMode) start(context, config) else stop()
    }

    /** 工作线程入口：先探测提权（可能阻塞），再进入读流循环 */
    private fun boot(context: Context) {
        val backend = PrivilegedShell.backend()
        Trace.log(context, "evdev", "探测结果 backend=${PrivilegedShell.describe()}")
        if (backend == PrivilegedShell.Backend.NONE) {
            // Shizuku 在跑但没授权给本应用：主动发起授权请求（Shizuku 会弹授权框）。
            // 用户同意后由设置页的监听器重新拉起，这里不阻塞等待。
            if (PrivilegedShell.shizukuNeedsGrant()) {
                Trace.log(context, "evdev", "Shizuku 未授权，发起授权请求")
                PrivilegedShell.requestShizukuPermission()
            }
            Trace.log(context, "evdev", "提权不可用，不启动（Shizuku 未运行且无 root）")
            synchronized(this) { worker = null }
            return
        }
        running = true
        try {
            loop(context)
        } finally {
            running = false
            synchronized(this) { worker = null }
            Trace.log(context, "evdev", "事件源已退出")
        }
    }

    private fun loop(context: Context) {
        while (wanted) {
            val dev = device ?: TouchDeviceProbe.probe(context)?.also { device = it }
            if (dev == null) {
                Trace.log(context, "evdev", "找不到触摸屏设备，2s 后重试")
                sleepQuietly(2000)
                continue
            }
            val h = PrivilegedShell.open(context, "getevent", "-lt", dev.path)
            if (h == null) {
                Trace.log(context, "evdev", "无法打开 ${dev.path}，2s 后重试")
                sleepQuietly(2000)
                continue
            }
            handle = h
            Trace.log(context, "evdev", "开始监听 ${dev.path} (${dev.name}) ${dev.maxX}x${dev.maxY}")
            try {
                readLoop(context, h, dev)
            } catch (t: Throwable) {
                Trace.log(context, "evdev", "读取中断: $t")
            } finally {
                runCatching { h.destroy() }
                handle = null
            }
            if (wanted) {
                Trace.log(context, "evdev", "事件流结束，800ms 后重连")
                sleepQuietly(800)
            }
        }
    }

    private fun readLoop(context: Context, h: PrivilegedShell.Handle, dev: TouchDevice) {
        h.input.bufferedReader().use { reader ->
            var curX = -1
            var curY = -1
            var pendingDown = false
            while (wanted) {
                val line = reader.readLine() ?: return
                when {
                    line.contains("ABS_MT_POSITION_X") -> curX = hexTail(line)
                    line.contains("ABS_MT_POSITION_Y") -> curY = hexTail(line)
                    line.contains("ABS_MT_TRACKING_ID") -> {
                        // 0xffffffff 表示这一指抬起
                        if (hexTail(line) == -1) pendingDown = false
                    }
                    line.contains("BTN_TOUCH") -> {
                        val down = line.contains("DOWN") || line.trimEnd().endsWith(" 1")
                        pendingDown = down
                    }
                    line.contains("SYN_REPORT") && pendingDown -> {
                        pendingDown = false
                        if (curX >= 0 && curY >= 0) emit(context, dev, curX, curY)
                    }
                }
            }
        }
    }

    private fun emit(context: Context, dev: TouchDevice, rawX: Int, rawY: Int) {
        val c = cfg ?: runCatching { Prefs.read(context) }.getOrNull() ?: return
        if (!c.enabled || !c.shizukuMode) return
        val mapped = mapToScreen(context, dev, rawX, rawY) ?: return
        // 「是否在录屏 / 是否全局生效」由 shell 绘制进程自己判断，这里只管把坐标送过去
        ShellFxHost.emit(mapped.first, mapped.second)
        Trace.log(context, "evdev", "tap raw=($rawX,$rawY) -> (${mapped.first},${mapped.second})")
    }

    /**
     * 原始坐标 → 屏幕像素。
     *
     * 触摸面板坐标系固定在物理方向，屏幕旋转 90/270 时要交换并翻转一个轴。
     * 本机自然方向为竖屏，实测 `input tap 640 1400` 对应 raw(10176,22176)，
     * 映射后为 (636,1386)，误差在一像素级。
     */
    @Suppress("DEPRECATION")
    private fun mapToScreen(context: Context, dev: TouchDevice, rawX: Int, rawY: Int): Pair<Float, Float>? {
        val wm = context.getSystemService(WindowManager::class.java) ?: return null
        val display = wm.defaultDisplay ?: return null
        val m = DisplayMetrics()
        display.getRealMetrics(m)
        val w = m.widthPixels.toFloat()
        val h = m.heightPixels.toFloat()
        if (w <= 0f || h <= 0f) return null
        val nx = (rawX.toFloat() / dev.maxX).coerceIn(0f, 1f)
        val ny = (rawY.toFloat() / dev.maxY).coerceIn(0f, 1f)
        return when (display.rotation) {
            Surface.ROTATION_90 -> (ny * w) to ((1f - nx) * h)
            Surface.ROTATION_180 -> ((1f - nx) * w) to ((1f - ny) * h)
            Surface.ROTATION_270 -> ((1f - ny) * w) to (nx * h)
            else -> (nx * w) to (ny * h)
        }
    }

    /** 取行尾最后一段十六进制数；`-lt` 输出形如 `EV_ABS  ABS_MT_POSITION_X  000027c0` */
    private fun hexTail(line: String): Int {
        val token = line.trimEnd().substringAfterLast(' ')
        return token.toLongOrNull(16)?.toInt() ?: -1
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** 供设置页显示当前状态 */
    fun statusText(): String = when {
        running && device != null -> "运行中 · ${device?.name}"
        running -> "启动中…"
        wanted -> "等待提权…"
        else -> "未启用"
    }
}
