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

    /**
     * 录制刚开始时的回调：录屏进程借此重新拉一次配置。
     *
     * 录屏 App 常常在后台常驻，用户打开「全局生效」时它可能被冻结、错过了
     * CONFIG_PUSH；等真正开录时快照还是旧的。录制开始的这一瞬间刷新一次，
     * 能保证后续点击按最新配置判断该不该由自己绘制。
     */
    @Volatile
    var onStart: (() -> Unit)? = null

    fun isActive(): Boolean = active

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (value) runCatching { onStart?.invoke() }
        onChange?.invoke(value)
    }
}
