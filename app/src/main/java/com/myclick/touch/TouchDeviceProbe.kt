package com.myclick.touch

import android.content.Context
import com.myclick.core.Trace

/**
 * 触摸屏设备探测。
 *
 * `getevent -pl` 的输出形如：
 * ```
 * add device 7: /dev/input/event7
 *   name:     "touchpanel"
 *   events:
 *     ABS (0003): ABS_MT_POSITION_X     : value 0, min 0, max 20351, fuzz 0, ...
 *                 ABS_MT_POSITION_Y     : value 0, min 0, max 44351, fuzz 0, ...
 * ```
 * 量程是触摸面板的原始分辨率（真机 1272x2772 对应 20351x44351，正好 16 倍），
 * 必须读出来做线性映射，不能假设等于屏幕像素。
 */
data class TouchDevice(
    val path: String,
    val name: String,
    val maxX: Int,
    val maxY: Int
)

object TouchDeviceProbe {

    /** 明显不是物理触摸屏的节点名，避免选中后一直等不到事件 */
    private val EXCLUDE = listOf("virtual", "uinput", "gpio-keys", "jack", "haptics", "magnetic")

    fun probe(context: Context?): TouchDevice? {
        val raw = PrivilegedShell.exec(context, "getevent", "-pl")
        if (raw.isNullOrBlank()) {
            Trace.log(context, "dev", "probe: 无法执行 getevent（提权不可用）")
            return null
        }
        val list = parse(raw)
        Trace.log(context, "dev", "probe 命中 ${list.size} 个候选: " + list.joinToString { "${it.name}@${it.path}(${it.maxX}x${it.maxY})" })
        if (list.isEmpty()) return null
        // 名字里带 touch / tp 的优先，这是绝大多数机型触摸屏的命名习惯
        val named = list.firstOrNull {
            val n = it.name.lowercase()
            n.contains("touch") || n.contains("touchpanel") || n == "tp"
        }
        return named ?: list.first()
    }

    fun parse(raw: String): List<TouchDevice> {
        val result = ArrayList<TouchDevice>()
        var path: String? = null
        var name = ""
        var maxX = 0
        var maxY = 0

        fun flush() {
            val p = path
            if (p != null && maxX > 0 && maxY > 0) {
                result.add(TouchDevice(p, name, maxX, maxY))
            }
            path = null
            name = ""
            maxX = 0
            maxY = 0
        }

        for (line in raw.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("add device")) {
                flush()
                path = t.substringAfter(':', "").trim().ifEmpty { null }
                continue
            }
            if (t.startsWith("name:")) {
                name = t.substringAfter(':').trim().trim('"')
                continue
            }
            if (t.contains("ABS_MT_POSITION_X")) {
                maxX = extractMax(t)
                continue
            }
            if (t.contains("ABS_MT_POSITION_Y")) {
                maxY = extractMax(t)
                continue
            }
        }
        flush()

        return result.filter { d ->
            val n = d.name.lowercase()
            EXCLUDE.none { n.contains(it) }
        }.ifEmpty {
            // 全被排除了就退回原始列表，总比找不到设备强
            result
        }
    }

    private fun extractMax(line: String): Int {
        val idx = line.indexOf("max")
        if (idx < 0) return 0
        val tail = line.substring(idx + 3).trimStart(' ', ':', '=')
        val num = tail.takeWhile { it.isDigit() }
        return num.toIntOrNull() ?: 0
    }
}
