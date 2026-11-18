package com.myclick.shell

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import com.myclick.touch.TouchDevice
import com.myclick.touch.TouchDeviceProbe
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 绘制进程内的触摸事件源。
 *
 * 之前这条链路跑在 App 进程里（App 用 Shizuku 读事件再推给绘制进程），结果是
 * **退出 App 几秒后全停** —— ColorOS 很快回收后台进程。现在绘制进程自己读：
 * 它就是 `shell` 身份、属于 `input` 组，直接 fork `getevent` 即可，不用再经过 Shizuku。
 *
 * 解析规则：`ABS_MT_POSITION_X/Y` 记坐标、`BTN_TOUCH DOWN` + `SYN_REPORT` 判定一次按下，
 * 再按面板量程线性映射到屏幕像素并做旋转补偿。
 * （试过直接 open `/dev/input/event*` 读二进制，真机上读不到数据，只能继续用 getevent。）
 */
class ShellTouchSource(
    private val context: Context,
    private val onTap: (Float, Float) -> Unit
) {

    private val displayManager: DisplayManager? = context.getSystemService(DisplayManager::class.java)

    @Volatile
    private var running = false

    @Volatile
    private var device: TouchDevice? = null

    private var worker: Thread? = null
    private var process: Process? = null

    fun currentDevice(): TouchDevice? = device

    fun isRunning(): Boolean = running

    @Synchronized
    fun start() {
        if (running) return
        running = true
        worker = Thread({ loop() }, "fx-touch").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { process?.destroy() }
        process = null
        worker?.interrupt()
        worker = null
    }

    private fun loop() {
        while (running) {
            val dev = device ?: probe()?.also { device = it }
            if (dev == null) {
                println("FX touch: 找不到触摸屏，2s 重试")
                sleep(2000)
                continue
            }
            val p = try {
                ProcessBuilder("/system/bin/getevent", "-lt", dev.path)
                    .redirectErrorStream(true)
                    .start()
            } catch (t: Throwable) {
                println("FX touch spawn fail: $t")
                sleep(2000)
                continue
            }
            process = p
            println("FX touch: 监听 ${dev.path} (${dev.name}) ${dev.maxX}x${dev.maxY}")
            try {
                readLoop(p, dev)
            } catch (t: Throwable) {
                if (running) println("FX touch read fail: $t")
            } finally {
                runCatching { p.destroy() }
                process = null
            }
            if (running) sleep(800)
        }
    }

    private fun readLoop(p: Process, dev: TouchDevice) {
        var curX = -1
        var curY = -1
        var pendingDown = false
        BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
            while (running) {
                val line = reader.readLine() ?: return
                when {
                    line.contains("ABS_MT_POSITION_X") -> curX = hexTail(line)
                    line.contains("ABS_MT_POSITION_Y") -> curY = hexTail(line)
                    line.contains("ABS_MT_TRACKING_ID") -> {
                        if (hexTail(line) == -1) pendingDown = false
                    }
                    line.contains("BTN_TOUCH") -> {
                        pendingDown = line.contains("DOWN") || line.trimEnd().endsWith(" 1")
                    }
                    line.contains("SYN_REPORT") && pendingDown -> {
                        pendingDown = false
                        if (curX >= 0 && curY >= 0) {
                            val mapped = mapToScreen(dev, curX, curY) ?: continue
                            onTap(mapped.first, mapped.second)
                        }
                    }
                }
            }
        }
    }

    private fun probe(): TouchDevice? = runCatching {
        val p = ProcessBuilder("/system/bin/getevent", "-pl").redirectErrorStream(true).start()
        val raw = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        val list = TouchDeviceProbe.parse(raw)
        if (list.isEmpty()) return@runCatching null
        list.firstOrNull {
            val n = it.name.lowercase()
            n.contains("touch") || n.contains("touchpanel") || n == "tp"
        } ?: list.first()
    }.getOrNull()

    /** 原始坐标 → 屏幕像素，规则与 App 侧旧实现一致 */
    @Suppress("DEPRECATION")
    private fun mapToScreen(dev: TouchDevice, rawX: Int, rawY: Int): Pair<Float, Float>? {
        val display: Display = runCatching {
            displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        }.getOrNull() ?: return null
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

    /** `-lt` 输出形如 `EV_ABS ABS_MT_POSITION_X 000027c0`，取行尾十六进制 */
    private fun hexTail(line: String): Int {
        val token = line.trimEnd().substringAfterLast(' ')
        return token.toLongOrNull(16)?.toInt() ?: -1
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }
}
