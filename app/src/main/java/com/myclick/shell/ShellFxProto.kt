package com.myclick.shell

import com.myclick.core.Config

/**
 * App 进程 → shell 绘制进程的极简文本协议。
 *
 * 为什么不用 Bundle / Parcel：这个进程是 `app_process` 起的，没有 AMS 注册、
 * 没有 Binder 通道，用最朴素的 stdin 行协议最稳，也方便在 adb 里手工调试。
 *
 * 行格式：
 *  - `cfg <csv>`   渲染配置（字段顺序见 [encodeConfig]）
 *  - `tap <x> <y>` 一次点击，单位是屏幕物理像素
 *  - `rec <0|1>`   是否正在录屏
 *  - `quit`        主动退出
 */
object ShellFxProto {

    const val CMD_CFG = "cfg"
    const val CMD_TAP = "tap"
    const val CMD_REC = "rec"
    const val CMD_QUIT = "quit"

    /** 字段顺序改了必须两端一起改；这里刻意用固定顺序而不是 key=value，解析最省 */
    fun encodeConfig(cfg: Config): String = buildString {
        append(CMD_CFG).append(' ')
        append(if (cfg.enabled) 1 else 0).append(',')
        append(cfg.scale).append(',')
        append(cfg.speed).append(',')
        append(cfg.themeMode).append(',')
        append(if (cfg.globalEffect) 1 else 0).append(',')
        append(cfg.gateMode).append(',')
        append(if (cfg.dprCapEnabled) 1 else 0).append(',')
        append(cfg.darkFill).append(',')
        append(cfg.darkArcStart).append(',')
        append(cfg.darkArcEnd).append(',')
        append(cfg.darkSpark).append(',')
        append(cfg.lightFill).append(',')
        append(cfg.lightArcStart).append(',')
        append(cfg.lightArcEnd).append(',')
        append(cfg.lightSpark).append(',')
        append(cfg.fxFps)
    }

    fun decodeConfig(payload: String): Config? {
        val f = payload.split(',')
        if (f.size < 15) return null
        fun i(at: Int, def: Int) = f.getOrNull(at)?.toIntOrNull() ?: def
        fun b(at: Int, def: Boolean) = (f.getOrNull(at)?.toIntOrNull() ?: if (def) 1 else 0) != 0
        fun fl(at: Int, def: Float) = f.getOrNull(at)?.toFloatOrNull() ?: def
        val d = Config.default()
        return Config(
            enabled = b(0, d.enabled),
            scale = fl(1, d.scale),
            speed = fl(2, d.speed),
            themeMode = i(3, d.themeMode),
            globalEffect = b(4, d.globalEffect),
            gateMode = i(5, d.gateMode),
            dprCapEnabled = b(6, d.dprCapEnabled),
            darkFill = i(7, d.darkFill),
            darkArcStart = i(8, d.darkArcStart),
            darkArcEnd = i(9, d.darkArcEnd),
            darkSpark = i(10, d.darkSpark),
            lightFill = i(11, d.lightFill),
            lightArcStart = i(12, d.lightArcStart),
            lightArcEnd = i(13, d.lightArcEnd),
            lightSpark = i(14, d.lightSpark),
            fxFps = i(15, d.fxFps)
        )
    }
}
