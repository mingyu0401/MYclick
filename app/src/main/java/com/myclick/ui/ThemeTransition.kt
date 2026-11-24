package com.myclick.ui

import android.graphics.Bitmap

/**
 * 主题切换的跨重建过渡状态。
 *
 * 换主题必须重建 Activity（资源主题、控件颜色都在构建时固化），重建会换掉整个窗口，
 * 没法像网页那样直接给 CSS 变量加 transition。这里的做法是「快照圆形过渡」：
 *
 *  旧实例：把当前窗口画成一张位图，连同主题按钮的圆心记进 pending → recreate()
 *  新实例：旧界面位图盖在已经换成新主题的界面上，以按钮为圆心收缩 / 扩散，
 *          露出下面的新主题界面（见 [ThemeRevealView]）
 */
internal object ThemeTransition {

    /** 圆形过渡与主题按钮渐变共用的时长，节奏与网页版一致 */
    const val DURATION_MS = 480L

    /**
     * @param cx / [cy] 主题按钮圆心（窗口坐标）
     * @param startRadius 圆的起始半径，也就是按钮的半径
     * @param toDark 新主题是否为深色
     * @param fromCard / [fromText] 旧主题下按钮底色、图标色，新按钮从它们渐变过去
     */
    class Reveal(
        val snapshot: Bitmap,
        val cx: Float,
        val cy: Float,
        val startRadius: Float,
        val toDark: Boolean,
        val fromCard: Int,
        val fromText: Int,
    )

    @Volatile
    var pending: Reveal? = null
}
