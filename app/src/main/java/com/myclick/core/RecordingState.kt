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

    fun effective(cfg: Config): Boolean = cfg.gateMode == Config.GATE_ALWAYS || active
}
