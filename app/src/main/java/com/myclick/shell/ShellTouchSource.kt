package com.myclick.shell

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import com.myclick.touch.TouchDevice
import com.myclick.touch.TouchDeviceProbe

/** Shizuku 触摸源：native 线程直接读取 evdev 二进制协议。 */
class ShellTouchSource(
    private val context: Context,
    private val onTouch: (Float, Float) -> Unit
) {

    private val displayManager: DisplayManager? = context.getSystemService(DisplayManager::class.java)

    @Volatile private var running = false
    @Volatile private var device: TouchDevice? = null
    @Volatile private var nativeHandle = 0L
    private var worker: Thread? = null

    fun currentDevice(): TouchDevice? = device
    fun isRunning(): Boolean = running

    @Synchronized
    fun start() {
        if (running) return
        running = true
        worker = Thread({ loop() }, "fx-touch-native").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        val thread = worker
        worker = null
        thread?.interrupt()
        thread?.join(1200)
    }

    private fun loop() {
        while (running) {
            val dev = device ?: probe()?.also { device = it }
            if (dev == null) {
                println("FX native touch: 找不到触摸屏，2s 重试")
                sleep(2000)
                continue
            }
            val listener = object : NativeTouchMonitor.Listener {
                override fun onTouchStart(rawX: Int, rawY: Int) {
                    mapToScreen(dev, rawX, rawY)?.let { onTouch(it.first, it.second) }
                }
            }
            val handle = runCatching {
                NativeTouchMonitor.nativeStart(dev.path, listener)
            }.getOrDefault(0L)
            if (handle == 0L) {
                println("FX native touch: 打开 ${dev.path} 失败")
                sleep(1500)
                continue
            }
            nativeHandle = handle
            println(
                "FX native touch: 直接读取 ${dev.path} (${dev.name}) " +
                    "${dev.maxX}x${dev.maxY}"
            )
            while (running && NativeTouchMonitor.nativeRunning(handle)) sleep(300)
            if (nativeHandle == handle) nativeHandle = 0L
            runCatching { NativeTouchMonitor.nativeStop(handle) }
            if (running) sleep(500)
        }
    }

    private fun probe(): TouchDevice? = runCatching {
        val p = ProcessBuilder("/system/bin/getevent", "-pl").redirectErrorStream(true).start()
        val raw = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        val list = TouchDeviceProbe.parse(raw)
        list.firstOrNull {
            val name = it.name.lowercase()
            name.contains("touch") || name.contains("touchpanel") || name == "tp"
        } ?: list.firstOrNull()
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun mapToScreen(dev: TouchDevice, rawX: Int, rawY: Int): Pair<Float, Float>? {
        val display: Display = runCatching { displayManager?.getDisplay(Display.DEFAULT_DISPLAY) }
            .getOrNull() ?: return null
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        if (width <= 0f || height <= 0f) return null
        val nx = (rawX.toFloat() / dev.maxX).coerceIn(0f, 1f)
        val ny = (rawY.toFloat() / dev.maxY).coerceIn(0f, 1f)
        return when (display.rotation) {
            Surface.ROTATION_90 -> (ny * width) to ((1f - nx) * height)
            Surface.ROTATION_180 -> ((1f - nx) * width) to ((1f - ny) * height)
            Surface.ROTATION_270 -> ((1f - ny) * width) to (nx * height)
            else -> (nx * width) to (ny * height)
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

}
