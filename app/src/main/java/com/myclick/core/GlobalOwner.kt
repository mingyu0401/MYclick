package com.myclick.core

/**
 * 「这一次绘制归谁」的进程级权威标志。
 *
 * 为什么需要它：全局生效打开后，system_server 里有一个常驻宿主在画，录屏进程里
 * 也有一套覆盖层。只靠录屏进程读到的配置快照判断是不够的 —— 录屏 App 进程可能
 * 早就在后台常驻（或被 ColorOS 冻结），打开「全局生效」时那条 CONFIG_PUSH 广播
 * 它收不到，快照里 `globalEffect` 仍是 false。于是录屏一开始 `show_touches` 把
 * RecordingState 置为 active，录屏进程立刻也开始画，和全局宿主叠成两份。
 *
 * 这里改为由 system_server 在每次投递 ACTION_DOWN 时携带归属标记
 * （[TouchRelay.EX_GLOBAL_OWNED]）：全局宿主确认自己能画，就明确告诉录屏进程
 * 「这次你别画」。归属信息跟着事件走，不依赖任何配置同步，也不怕进程被冻结。
 *
 * 反过来，标记为 false 时说明全局宿主这一击没画（刚开机 / 正在挂窗口），
 * 录屏进程可以放心接管，不会出现两边都以为对方在画、结果一个特效都没有。
 */
object GlobalOwner {

    /** 全局宿主已经确认绘制当前这一击，本进程必须让位 */
    @Volatile
    var owned: Boolean = false
        private set

    /**
     * 事件明确表示「这一击没人画，交还给你」。
     *
     * 和 owned 互补：全局宿主还没挂好窗口时，框架会把绘制权临时交回录屏进程，
     * 此时即使配置里写着「全局生效」，录屏进程也应该接住这一击，而不是双双沉默。
     */
    @Volatile
    var handedBack: Boolean = false
        private set

    /**
     * 更新归属标记；返回 true 表示状态发生变化（调用方据此决定是否重建窗口）。
     */
    fun update(globalOwned: Boolean): Boolean {
        val changed = owned != globalOwned
        owned = globalOwned
        handedBack = !globalOwned
        return changed
    }
}
