package com.myclick.ui

import android.graphics.Bitmap

/**
 * 主题切换的跨重建过渡状态。
 *
 * 换主题必须重建 Activity（资源主题、控件颜色都在构建时固化），重建会换掉整个窗口，
 * 没法像网页那样直接给 CSS 变量加 transition。这里的做法是「快照交叉淡化」：
 *
 *  旧实例：把当前窗口画成一张位图 → 记进 pending → recreate()
 *  新实例：把旧界面位图铺满窗口，淡出 350ms，露出下面已经换成新主题的界面
 *
 * 视觉上就是整屏颜色平滑过渡，没有遮罩跳变。
 */
internal object ThemeTransition {

    @Volatile
    var pending: Bitmap? = null
}
