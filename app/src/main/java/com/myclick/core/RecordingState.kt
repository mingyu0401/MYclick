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
     * 录屏进程里的覆盖层是否绘制。
     *
     * 全局模式由**模块自身进程**的 GlobalFxHost 统一绘制（不依赖录屏进程是否存活），
     * 所以这里主动让位，避免同一点击被两个窗口画两遍造成重影。
     * 旧版 `gateMode == GATE_ALWAYS` 同样迁移到全局宿主；否则只在录屏中显示。
     */
    fun effective(cfg: Config): Boolean {
        if (cfg.globalEffect || cfg.gateMode == Config.GATE_ALWAYS) return false
        return active
    }
}
