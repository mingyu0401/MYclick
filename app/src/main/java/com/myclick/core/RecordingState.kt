package com.myclick.core

/**
 * 录屏进行中标志。
 *
 * 逆向 17.5.4 得到的高可信信号：
 *   录屏开始时 com.oplus.screenrecorder.common.b0 会
 *   Settings.System.putInt(cr, "show_touches", 1)，结束时写回 0。
 * 另外补充 MediaProjection / MediaRecorder 作为兜底信号。
 */
object RecordingState {

    @Volatile
    private var active: Boolean = false

    @Volatile
    var onChange: ((Boolean) -> Unit)? = null

    fun isActive(): Boolean = active

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        onChange?.invoke(value)
    }

    /**
     * 是否允许绘制特效：
     * - `globalEffect`：「全局生效」开关，打开后不再要求录屏进行中；
     * - `gateMode == GATE_ALWAYS`：旧版「显示时机 → 始终显示」配置，保留兼容；
     * - 否则只在录屏进行中（active）显示。
     */
    fun effective(cfg: Config): Boolean =
        cfg.globalEffect || cfg.gateMode == Config.GATE_ALWAYS || active
}
