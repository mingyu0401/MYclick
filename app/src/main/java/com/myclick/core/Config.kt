package com.myclick.core

import android.content.res.Configuration
import android.os.Bundle

/**
 * 模块配置。所有字段都必须是可放进 Bundle 的基本类型，
 * 因为要跨进程通过 ContentProvider.call() / Broadcast 传给被 Hook 进程。
 */
data class Config(
    val enabled: Boolean = true,
    val scale: Float = 1.4f,                 // SCALE，默认 1.4
    val speed: Float = 1.0f,                 // 动画速度倍率
    val themeMode: Int = THEME_FOLLOW_SYSTEM,
    val touchSource: Int = SOURCE_AUTO,
    val gateMode: Int = GATE_AUTO,
    val globalEffect: Boolean = false,       // 全局生效：未录屏时也显示点击特效（默认关闭）
    val hideNativeDot: Boolean = true,       // 强制 show_touches=0，隐藏原生白点
    val dprCapEnabled: Boolean = true,       // DPR 上限 2x
    val targetPackage: String = DEFAULT_TARGET,
    val extraTargets: String = "",           // 逗号分隔的附加包名
    // ---- 调色板（RGB， alpha 由动画单独计算） ----
    val darkFill: Int = 0xF9A86C,
    val darkArcStart: Int = 0xFAFCFC,
    val darkArcEnd: Int = 0x96D2FF,
    val darkSpark: Int = 0xFFFFFF,
    val lightFill: Int = 0xE07A3F,
    val lightArcStart: Int = 0xFAFCFC,
    val lightArcEnd: Int = 0xE04F7C,
    val lightSpark: Int = 0xE04F7C,
    val nonce: String = DEFAULT_NONCE
) {
    companion object {
        const val MODULE_PACKAGE = "com.myclick"
        const val PREFS_FILE = "myclick_config"
        const val CONFIG_AUTHORITY = "com.myclick.config"
        const val DEFAULT_TARGET = "com.oplus.screenrecorder"
        const val DEFAULT_NONCE = "myclick-1.0-9f3c"

        const val THEME_FOLLOW_SYSTEM = 0
        const val THEME_DARK = 1
        const val THEME_LIGHT = 2

        const val SOURCE_AUTO = 0
        const val SOURCE_FRAMEWORK = 1
        const val SOURCE_APP_SCOPED = 2
        const val SOURCE_LOCAL = 3

        const val GATE_AUTO = 0
        const val GATE_ALWAYS = 1

        /** 已知 ColorOS / 一加 / OPPO 各分支屏录包名，用于降级探测 */
        val KNOWN_TARGETS = listOf(
            "com.oplus.screenrecorder",
            "com.coloros.screenrecorder",
            "com.oppo.screenrecorder",
            "com.oneplus.screenrecorder",
            "com.android.screenrecorder",
            "com.oplus.screenrecorder.oem"
        )

        fun default(): Config = Config()

        fun fromBundle(b: Bundle): Config {
            val d = default()
            return Config(
                enabled = b.getBoolean("enabled", d.enabled),
                scale = b.getFloat("scale", d.scale),
                speed = b.getFloat("speed", d.speed),
                themeMode = b.getInt("themeMode", d.themeMode),
                touchSource = b.getInt("touchSource", d.touchSource),
                gateMode = b.getInt("gateMode", d.gateMode),
                globalEffect = b.getBoolean("globalEffect", d.globalEffect),
                hideNativeDot = b.getBoolean("hideNativeDot", d.hideNativeDot),
                dprCapEnabled = b.getBoolean("dprCapEnabled", d.dprCapEnabled),
                targetPackage = b.getString("targetPackage") ?: d.targetPackage,
                extraTargets = b.getString("extraTargets") ?: d.extraTargets,
                darkFill = b.getInt("darkFill", d.darkFill),
                darkArcStart = b.getInt("darkArcStart", d.darkArcStart),
                darkArcEnd = b.getInt("darkArcEnd", d.darkArcEnd),
                darkSpark = b.getInt("darkSpark", d.darkSpark),
                lightFill = b.getInt("lightFill", d.lightFill),
                lightArcStart = b.getInt("lightArcStart", d.lightArcStart),
                lightArcEnd = b.getInt("lightArcEnd", d.lightArcEnd),
                lightSpark = b.getInt("lightSpark", d.lightSpark),
                nonce = b.getString("nonce") ?: d.nonce
            )
        }
    }

    fun toBundle(): Bundle = Bundle().apply {
        putBoolean("enabled", enabled)
        putFloat("scale", scale)
        putFloat("speed", speed)
        putInt("themeMode", themeMode)
        putInt("touchSource", touchSource)
        putInt("gateMode", gateMode)
        putBoolean("globalEffect", globalEffect)
        putBoolean("hideNativeDot", hideNativeDot)
        putBoolean("dprCapEnabled", dprCapEnabled)
        putString("targetPackage", targetPackage)
        putString("extraTargets", extraTargets)
        putInt("darkFill", darkFill)
        putInt("darkArcStart", darkArcStart)
        putInt("darkArcEnd", darkArcEnd)
        putInt("darkSpark", darkSpark)
        putInt("lightFill", lightFill)
        putInt("lightArcStart", lightArcStart)
        putInt("lightArcEnd", lightArcEnd)
        putInt("lightSpark", lightSpark)
        putString("nonce", nonce)
    }

    fun isDark(uiMode: Int): Boolean = when (themeMode) {
        THEME_DARK -> true
        THEME_LIGHT -> false
        else -> (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun palette(dark: Boolean): Palette = if (dark) {
        Palette(darkFill, darkArcStart, darkArcEnd, darkSpark)
    } else {
        Palette(lightFill, lightArcStart, lightArcEnd, lightSpark)
    }

    /** 运行时探测到目标包名后追加；返回所有可能被 Hook 的录屏包名 */
    fun allTargets(): List<String> = buildList {
        add(targetPackage)
        extraTargets.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
        addAll(KNOWN_TARGETS)
    }.distinct()
}

data class Palette(
    val fill: Int,
    val arcStart: Int,
    val arcEnd: Int,
    val spark: Int
) {
    val fillR: Int get() = (fill shr 16) and 0xFF
    val fillG: Int get() = (fill shr 8) and 0xFF
    val fillB: Int get() = fill and 0xFF
}
