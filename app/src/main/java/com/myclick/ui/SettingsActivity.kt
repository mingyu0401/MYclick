package com.myclick.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsetsController
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import com.myclick.R
import com.myclick.access.ShellFxHost
import com.myclick.core.Config
import com.myclick.core.FxService
import com.myclick.core.GlobalTouchReceiver
import com.myclick.core.NightMode
import com.myclick.core.PackageDetector
import com.myclick.core.Prefs
import com.myclick.core.RootHelper
import com.myclick.core.ShizukuBridge
import com.myclick.core.TouchRelay
import com.myclick.fx.EffectSurfaceView
import rikka.shizuku.Shizuku
import com.myclick.touch.PrivilegedShell

/**
 * 模块设置页。
 *
 * 布局参照 ONextBox：大标题 + 石板蓝主卡 + 双列状态卡 + 分组卡片列表，
 * 底部悬浮胶囊导航把内容拆成「首页 / 特效 / 关于」三页，避免单页长列表。
 * 深浅色跟随「主题」设置（默认跟随系统）：界面、弹窗和点击特效共用同一套判定。
 * 全部使用 framework 控件手写，不引入 AndroidX / Material。
 */
class SettingsActivity : Activity() {

    private lateinit var config: Config
    private lateinit var preview: EffectSurfaceView
    private lateinit var targetEdit: EditText
    private lateinit var extraEdit: EditText
    private lateinit var contentHost: FrameLayout

    private val pages = mutableListOf<View>()
    private val navIcons = mutableListOf<ImageView>()
    private val navLabels = mutableListOf<TextView>()
    private val navItems = mutableListOf<LinearLayout>()
    private val navIconColors = mutableListOf<Int>()
    private val navLabelColors = mutableListOf<Int>()
    /** 首页 / 特效页各有一份「全局生效」开关，切换时静默同步选中状态 */
    private val globalRows = mutableListOf<View>()
    /** 「仅在录屏时显示特效」开关，与 [globalRows] 互斥，切换时同样静默同步 */
    private val recordingRows = mutableListOf<View>()
    private var globalReceiverEnabled: Boolean? = null
    private var overlayGrantAttempted = false
    private var shizukuPermissionPrompted = false
    private lateinit var navIndicator: View
    private var currentTab = -1

    /** 「一键切换」按钮与两套调色板标题，切换后要跟着判定结果更新文案 */
    /** 每个页面标题行里各有一个主题按钮，图标要一起更新 */
    private val themeButtons = mutableListOf<ImageView>()
    private var themeTransitioning = false
    private var darkPaletteLabel: TextView? = null
    private var lightPaletteLabel: TextView? = null

    /** 首页三张运行方式卡片，以及主卡上「当前：××模式」那一行 */
    private val modeCards = linkedMapOf<Int, Ui.OptionCard>()
    private var heroModeView: TextView? = null

    private val density: Float get() = resources.displayMetrics.density

    /** Shizuku 授权结果：用户点了允许后立刻把事件源拉起来，不用再拨一次开关 */
    private val shizukuGrantListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                runCatching { ShellFxHost.sync(this, config) }
                toast("Shizuku 已授权")
            } else {
                toast("Shizuku 未授权，Shizuku 模式无法工作")
            }
            refreshModeStatus()
        }

    /**
     * 让「强制深色 / 强制浅色」也作用于系统资源主题（弹窗、EditText、Switch 等）。
     *
     * 只改 Ui 的调色板是不够的：Activity 的主题在 attachBaseContext 时就已经按
     * Configuration 解析完成。这里在挂载前把 uiMode 覆写成配置里的强制值，
     * values-night 资源就会正确命中；跟随系统时保持原样。
     */
    override fun attachBaseContext(newBase: Context) {
        val cfg = runCatching { Prefs.read(newBase) }.getOrNull()
        val forced = when (cfg?.themeMode) {
            Config.THEME_DARK -> true
            Config.THEME_LIGHT -> false
            else -> null
        }
        if (forced == null) {
            super.attachBaseContext(newBase)
            return
        }
        val config = Configuration(newBase.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (forced) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuGrantListener) }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (!::config.isInitialized) return
        // 从 Shizuku 授权框 / 后台回来时后端可能刚刚就绪，补一次启动；
        // 进程被系统回收过也在这里恢复，不必让用户重新拨开关。
        if (config.shizukuMode && config.effectOn() && !ShellFxHost.isRunning()) {
            runCatching { ShellFxHost.sync(this, config) }
        }
        // 服务可能被系统回收过，回来时补一次
        if (config.shizukuMode && config.effectOn()) runCatching { FxService.sync(this) }
        window.decorView.post { requestShizukuPermissionIfNeeded() }
        // 从 Shizuku / root 管理器授权回来时，卡片状态要跟着更新
        refreshModeStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = Prefs.read(this)
        // 旧版「显示时机 → 始终显示」迁移到新的「全局生效」开关，
        // 否则 UI 显示关闭、实际却一直全局显示，状态会对不上。
        if (config.gateMode == Config.GATE_ALWAYS) {
            config = config.copy(globalEffect = true, gateMode = Config.GATE_AUTO)
        }
        // 两个开关互斥：旧配置若两个都开着，以「全局生效」为准，关掉录屏开关
        if (config.globalEffect && config.enabled) {
            config = config.copy(enabled = false)
        }
        // 目标包名自动选定为本机真实安装的录屏 App；写盘时会自动广播给被 Hook 进程
        config = autoResolveTarget(config)
        Prefs.write(this, config)
        syncGlobalReceiver()
        // 冷启动也要按配置恢复宿主与前台服务：用户重开 App 后应当立刻可用。
        runCatching { ShellFxHost.sync(this, config) }
        runCatching { FxService.sync(this) }
        runCatching { Shizuku.addRequestPermissionResultListener(shizukuGrantListener) }

        // 界面深浅色与点击特效共用同一套判定：跟随系统 / 强制深色 / 强制浅色
        Ui.applyTheme(config.isDark(NightMode.isDark(this)))
        window.setBackgroundDrawable(ColorDrawable(Ui.BG))
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        window.isNavigationBarContrastEnforced = false

        val root = FrameLayout(this).apply {
            setBackgroundColor(Ui.BG)
            // Android 15+ (targetSdk 35+) 默认强制 edge-to-edge，内容会画到状态栏后面。
            // fitsSystemWindows 让内容避开状态栏/手势区，滚动时文字不再透到状态栏上。
            fitsSystemWindows = true
        }
        contentHost = FrameLayout(this)
        root.addView(contentHost, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))

        pages += buildHomePage()
        pages += buildFxPage()
        pages += buildAboutPage()
        pages.forEach {
            it.visibility = View.GONE
            contentHost.addView(it, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        }

        // 底部渐变遮罩：滚动内容在胶囊导航下方淡出，不会从导航底下露出半行文字
        root.addView(
            View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.TRANSPARENT, Ui.BG, Ui.BG)
                )
            },
            FrameLayout.LayoutParams(Ui.MATCH, dip(112f)).apply { gravity = Gravity.BOTTOM }
        )
        root.addView(buildNav(), FrameLayout.LayoutParams(dip(300f), dip(64f)).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dip(24f)
        })
        setContentView(root)
        applySystemBarIcons()

        selectTab(savedInstanceState?.getInt(KEY_TAB, 0) ?: 0)
        preview.post { preview.applyConfig(config) }

        // 主题切换重建：旧界面快照以主题按钮为圆心收缩 / 扩散，过渡到新主题
        ThemeTransition.pending?.let { reveal ->
            ThemeTransition.pending = null
            playThemeReveal(reveal)
        }
    }

    /**
     * 状态栏/导航栏图标明暗。
     *
     * 必须等 decorView 建好之后再取 controller（onCreate 里 window.insetsController
     * 会因 DecorView 为 null 抛 NPE，ColorOS 上已实测崩溃）；窗口重新挂载时（例如
     * recreate）再调一次，保证图标颜色跟界面一致。
     */
    private fun applySystemBarIcons() {
        val c = window.decorView.windowInsetsController ?: return
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        c.setSystemBarsAppearance(if (Ui.isDark) 0 else light, light)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBarIcons()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, currentTab.coerceAtLeast(0))
    }

    override fun onPause() {
        applyTargetFields()
        super.onPause()
    }

    // ---------------- 底部导航 ----------------

    private fun buildNav(): FrameLayout {
        val labels = listOf("首页", "特效", "关于")
        val icons = listOf(
            R.drawable.ic_nav_home,
            R.drawable.ic_nav_fx,
            R.drawable.ic_nav_info
        )
        val container = FrameLayout(this).apply {
            background = Ui.cardBg(this@SettingsActivity, Ui.NAV, 100f)
            elevation = dip(12f).toFloat()
        }

        // 选中态高亮胶囊：切换时平滑滑到对应 tab
        navIndicator = View(this).apply {
            background = Ui.cardBg(this@SettingsActivity, Ui.NAV_PILL, 100f)
            alpha = 0f
        }
        container.addView(navIndicator, FrameLayout.LayoutParams(dip(92f), dip(52f)).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dip(10f), 0, dip(10f), 0)
        }
        labels.forEachIndexed { i, label ->
            val item = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, Ui.MATCH, 1f)
                setOnClickListener { selectTab(i) }
            }
            val icon = ImageView(this@SettingsActivity).apply {
                setImageResource(icons[i])
                layoutParams = LinearLayout.LayoutParams(dip(22f), dip(22f))
            }
            val text = TextView(this@SettingsActivity).apply {
                this.text = label
                textSize = 12f
                setTextColor(Ui.SUB)
                // LinearLayout 默认给子 View 生成 MATCH_PARENT 宽度，而 TextView 默认
                // 文字左对齐，会让文字偏到图标左侧。显式占满 tab 并让文字水平居中。
                layoutParams = LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP)
                gravity = Gravity.CENTER_HORIZONTAL
                // 去掉字体自带上下留白：文字紧贴图标，整组在胶囊内垂直居中
                includeFontPadding = false
                setPadding(0, dip(2f), 0, 0)
            }
            item.addView(icon)
            item.addView(text)
            navIcons += icon
            navLabels += text
            navItems += item
            navIconColors += Ui.SUB
            navLabelColors += Ui.SUB
            row.addView(item)
        }
        container.addView(row, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        return container
    }

    private fun positionIndicator(index: Int, animate: Boolean) {
        if (!::navIndicator.isInitialized || navItems.isEmpty()) return
        val item = navItems.getOrNull(index) ?: return
        if (item.width <= 0) {
            if (!isFinishing) navIndicator.post { positionIndicator(index, animate) }
            return
        }
        val target = item.left + (item.width - navIndicator.width) / 2f
        navIndicator.animate().cancel()
        navIndicator.alpha = 1f
        if (animate) {
            navIndicator.animate().translationX(target)
                .setDuration(280)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        } else {
            navIndicator.translationX = target
        }
    }

    private fun selectTab(index: Int) {
        if (index == currentTab) return
        val previous = currentTab
        if (previous == TAB_ABOUT) applyTargetFields()
        val animate = previous >= 0
        currentTab = index

        switchPages(previous, index, animate)
        animateNavState(index, animate)
        navIndicator.post { positionIndicator(index, animate) }

        if (index == TAB_FX) {
            preview.post { preview.applyConfig(config) }
        }
    }

    /** 旧页淡出并轻微横移，新页从反方向淡入，避免硬切。 */
    private fun switchPages(from: Int, to: Int, animate: Boolean) {
        if (!animate) {
            pages.forEachIndexed { i, v ->
                v.animate().cancel()
                v.alpha = 1f
                v.translationX = 0f
                v.visibility = if (i == to) View.VISIBLE else View.GONE
            }
            return
        }
        val dir = if (to > from) 1f else -1f
        val shift = dip(26f).toFloat()
        val outgoing = pages[from]
        outgoing.animate().cancel()
        outgoing.animate()
            .alpha(0f).translationX(-dir * shift)
            .setDuration(150)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                outgoing.visibility = View.GONE
                outgoing.translationX = 0f
                outgoing.alpha = 1f
            }
            .start()

        val incoming = pages[to]
        incoming.animate().cancel()
        incoming.alpha = 0f
        incoming.translationX = dir * shift
        incoming.visibility = View.VISIBLE
        incoming.animate()
            .alpha(1f).translationX(0f)
            .setDuration(270)
            .setInterpolator(DecelerateInterpolator(1.4f))
            .start()

        pages.forEachIndexed { i, v ->
            if (i != from && i != to) {
                v.animate().cancel()
                v.visibility = View.GONE
            }
        }
    }

    /** 图标 / 文字颜色渐变 + 选中项弹性缩放。 */
    private fun animateNavState(index: Int, animate: Boolean) {
        navIcons.forEachIndexed { i, icon ->
            val selected = i == index
            val color = if (selected) Ui.ACCENT else Ui.SUB
            if (animate && navIconColors[i] != color) {
                ValueAnimator.ofArgb(navIconColors[i], color).apply {
                    duration = 220
                    addUpdateListener { icon.setColorFilter(it.animatedValue as Int) }
                    start()
                }
            } else {
                icon.setColorFilter(color)
            }
            navIconColors[i] = color
            val s = if (selected) 1.14f else 1f
            icon.animate().scaleX(s).scaleY(s)
                .setDuration(if (animate) 260 else 0L)
                .setInterpolator(OvershootInterpolator(1.2f))
                .start()
        }
        navLabels.forEachIndexed { i, label ->
            val selected = i == index
            val color = if (selected) Ui.TEXT else Ui.SUB
            if (animate && navLabelColors[i] != color) {
                ValueAnimator.ofArgb(navLabelColors[i], color).apply {
                    duration = 220
                    addUpdateListener { label.setTextColor(it.animatedValue as Int) }
                    start()
                }
            } else {
                label.setTextColor(color)
            }
            navLabelColors[i] = color
            label.setTypeface(label.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    // ---------------- 首页 ----------------

    private fun buildHomePage(): ScrollView {
        val (scroll, box) = Ui.pageScaffold(this)
        box.addView(pageHeader("首页"))
        box.addView(heroCard())

        // ---- 运行方式：三种并列、三选一 ----
        box.addView(Ui.sectionLabel(this, "运行方式 · 三选一"))
        modeCards[Config.RUN_LSPOSED] = Ui.optionCard(
            this,
            "LSPosed 模式",
            "需要 LSPosed",
            "需在 LSPosed 中勾选「系统框架」和录屏 App。"
        ) { onModeCardClicked(Config.RUN_LSPOSED) }
        modeCards[Config.RUN_SHIZUKU] = Ui.optionCard(
            this,
            "Shizuku 模式",
            "免 root",
            "需要去lsp中勾选推荐的并软重启手机."
        ) { onModeCardClicked(Config.RUN_SHIZUKU) }
        modeCards[Config.RUN_ROOT] = Ui.optionCard(
            this,
            "Root 模式",
            "需要 root",
            "需在 Magisk / KernelSU 中允许 MYclick。"
        ) { onModeCardClicked(Config.RUN_ROOT) }
        modeCards.values.forEach { box.addView(it.view) }
        box.addView(Ui.body(this, "同一时间只会有一种方式在工作，切换后另外两种会自动停用，不会出现两份特效。"))
        updateModeSelection()
        refreshModeStatus()

        // ---- 设备信息 ----
        box.addView(Ui.sectionLabel(this, "设备信息"))
        box.addView(
            Ui.group(
                this,
                Ui.infoRow(this, "设备型号", Build.MODEL).view,
                Ui.infoRow(this, "系统版本", "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）").view,
                Ui.infoRow(this, "系统构建", Build.DISPLAY).view,
                Ui.infoRow(this, "目标录屏应用", config.targetPackage).view
            )
        )

        box.addView(Ui.secondaryButton(this, "重新推送配置") {
            applyTargetFields()
            TouchRelay.sendConfig(this, config)
            toast("已重新推送配置")
        })
        box.addView(
            Ui.body(
                this,
                "修改任何设置都会自动保存并生效。只有 LSPosed 模式下系统框架没收到新配置时，才需要手动重新推送。"
            )
        )
        return scroll
    }

    // ---------------- 运行方式 ----------------

    private fun runModeName(mode: Int): String = when (mode) {
        Config.RUN_SHIZUKU -> "Shizuku 模式"
        Config.RUN_ROOT -> "Root 模式"
        else -> "LSPosed 模式"
    }

    private fun updateModeSelection() {
        modeCards.forEach { (mode, card) -> card.setSelected(mode == config.runMode) }
        heroModeView?.text = "当前：${runModeName(config.runMode)}"
    }

    /**
     * 点模式卡片：没选中就切过去；已经选中且条件未满足时，再点一次就重新尝试授权。
     */
    private fun onModeCardClicked(mode: Int) {
        if (mode != config.runMode) {
            config = config.withRunMode(mode)
            save()
            updateModeSelection()
        }
        when (mode) {
            Config.RUN_SHIZUKU -> afterShizukuEnabled()
            Config.RUN_ROOT -> afterRootEnabled()
            else -> afterLsposedSelected()
        }
        refreshModeStatus()
    }

    /**
     * 后台探测三种方式的可用性，刷新卡片底部的状态行。
     *
     * `su` 可能弹 root 授权框并阻塞、LSPosed 目录要 root 才能看，全都放后台线程。
     */
    private fun refreshModeStatus() {
        if (modeCards.isEmpty()) return
        val cfg = config
        Thread {
            val lspManager = runCatching {
                packageManager.getPackageInfo("org.lsposed.manager", 0) != null
            }.getOrDefault(false)
            val rootOk = PrivilegedShell.rootReady()
            val lspDir = rootOk &&
                RootHelper.su("test -d /data/adb/lspd && echo yes")?.contains("yes") == true
            val lsposed = lspManager || lspDir
            val shizukuOk = PrivilegedShell.shizukuReady()
            val shizukuGrant = PrivilegedShell.shizukuNeedsGrant()
            val hostRunning = ShellFxHost.isRunning()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // 只在当前选中的那张卡片上追加运行情况
                fun extra(mode: Int): String = when {
                    cfg.runMode != mode -> ""
                    !cfg.effectOn() -> " · 特效总开关已关闭"
                    mode != Config.RUN_LSPOSED && hostRunning -> " · 绘制进程运行中"
                    else -> ""
                }
                modeCards[Config.RUN_LSPOSED]?.setStatus(
                    if (lsposed) "● 已检测到 LSPosed" + extra(Config.RUN_LSPOSED)
                    else "● 未检测到 LSPosed",
                    lsposed
                )
                modeCards[Config.RUN_SHIZUKU]?.setStatus(
                    when {
                        shizukuOk -> "● Shizuku 已授权" + extra(Config.RUN_SHIZUKU)
                        shizukuGrant -> "● Shizuku 未授权，点此卡片申请"
                        else -> "● Shizuku 未运行，点此卡片打开 Shizuku"
                    },
                    shizukuOk
                )
                modeCards[Config.RUN_ROOT]?.setStatus(
                    if (rootOk) "● 已获得 root" + extra(Config.RUN_ROOT)
                    else "● 未获得 root，点此卡片重新申请",
                    rootOk
                )
            }
        }.start()
    }

    /** 切到 LSPosed 模式：独立绘制进程已由 save() 停掉，这里只提示生效条件 */
    private fun afterLsposedSelected() {
        toast("已切换到 LSPosed 模式（需在 LSPosed 勾选系统框架与录屏 App 并重启）")
    }

    /**
     * 切到 Root 模式后的引导。
     *
     * 探测放后台：`su` 会弹 root 授权框并一直等用户点，放主线程就是 ANR。
     * 授权成功直接拉起绘制进程；被拒绝就说明怎么去 root 管理器里放行。
     */
    private fun afterRootEnabled() {
        Thread {
            val ok = PrivilegedShell.rootReady()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ok) {
                    runCatching { ShellFxHost.sync(this, config) }
                    runCatching { FxService.sync(this) }
                    toast("Root 模式已启动")
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("没有拿到 root 授权")
                        .setMessage(
                            "Root 模式需要 root 权限来启动绘制进程。\n\n" +
                                "请在 Magisk / KernelSU / APatch 等 root 管理器里允许 MYclick，" +
                                "然后回到这里再点一次「Root 模式」卡片。\n\n" +
                                "手机没有 root 的话，可以改用「Shizuku 模式」。"
                        )
                        .setPositiveButton("知道了", null)
                        .show()
                }
                refreshModeStatus()
            }
        }.start()
    }

    private fun heroCard(): FrameLayout = FrameLayout(this).apply {
        background = Ui.heroBg(this@SettingsActivity)
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(Ui.MATCH, dip(164f))

        // 与桌面 App 图标共用同一个 vector，圆形裁切后自然融入主卡。
        // 圆形裁切放在 Drawable 里逐帧做，避免重建后 outline 未就绪时露出方形底。
        val icon = CircleClipDrawable(resources.getDrawable(R.drawable.ic_launcher, theme).mutate())
        addView(
            ImageView(this@SettingsActivity).apply {
                setImageDrawable(icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                // 点击或长按：内部图形加速旋转，松手后慢慢停下
                isLongClickable = true
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    icon.tap()
                }
                setOnLongClickListener { icon.setHolding(true); true }
                setOnTouchListener { _, e ->
                    if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                        icon.setHolding(false)
                    }
                    false
                }
            },
            FrameLayout.LayoutParams(dip(132f), dip(132f), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                rightMargin = dip(16f)
            }
        )
        val texts = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dip(22f), dip(20f), dip(150f), dip(20f))
            layoutParams = FrameLayout.LayoutParams(
                Ui.MATCH, Ui.WRAP, Gravity.START or Gravity.CENTER_VERTICAL
            )
            addView(TextView(this@SettingsActivity).apply {
                text = "MYclick"
                textSize = 36f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@SettingsActivity).apply {
                text = "版本：${versionName()}"
                textSize = 15f
                setTextColor(Color.parseColor("#EAF0F7"))
                setPadding(0, dip(10f), 0, 0)
            })
            addView(TextView(this@SettingsActivity).apply {
                text = "当前：${runModeName(config.runMode)}"
                textSize = 14f
                setTextColor(Color.parseColor("#D3DBE5"))
                setPadding(0, dip(4f), 0, 0)
            }.also { heroModeView = it })
        }
        addView(texts)
    }

    // ---------------- 特效页 ----------------

    private fun buildFxPage(): ScrollView {
        val (scroll, box) = Ui.pageScaffold(this)
        box.addView(pageHeader("特效"))

        box.addView(Ui.sectionLabel(this, "实时预览 · 点一下试试"))
        val previewCard = FrameLayout(this).apply {
            background = Ui.cardBg(this@SettingsActivity, Ui.CARD_SOFT, 24f)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(Ui.MATCH, dip(220f))
        }
        preview = EffectSurfaceView(this, density).apply {
            isClickable = true
            isFocusable = false
            setOnTouchListener { v, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    preview.emit(ev.x / density, ev.y / density)
                    v.performClick()
                }
                true
            }
        }
        previewCard.addView(preview, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        box.addView(previewCard)
        box.addView(Ui.secondaryButton(this, "居中触发一次") {
            preview.post {
                preview.emit((preview.width / density) / 2f, (preview.height / density) / 2f)
            }
        })

        box.addView(Ui.sectionLabel(this, "基础参数"))
        val fxFpsValues = listOf(Config.FPS_AUTO, 60, 90, 120)
        box.addView(
            Ui.group(
                this,
                Ui.switchRow(this, "全局生效", "全局显示可能会厌烦，慎重打开", config.globalEffect) {
                    config = config.copy(globalEffect = it, enabled = config.enabled && !it); save()
                }.also { globalRows += it },
                Ui.switchRow(this, "仅在录屏时显示特效", "与全局生效互斥，录屏时才绘制", config.enabled) {
                    config = config.copy(enabled = it, globalEffect = config.globalEffect && !it); save()
                }.also { recordingRows += it },
                Ui.seekRow(this, "SCALE 大小", "默认 1.0", config.scale, 5, 30) {
                    config = config.copy(scale = it / 10f); save()
                },
                Ui.seekRow(this, "动画速度", "1.0 = 60fps 原速", config.speed, 3, 30) {
                    config = config.copy(speed = it / 10f); save()
                },
                Ui.choiceRow(this, "特效帧率", R.array.fx_fps, fxFpsValues.indexOf(config.fxFps).coerceAtLeast(0)) {
                    config = config.copy(fxFps = fxFpsValues[it]); save()
                },
                Ui.choiceRow(this, "触摸事件来源", R.array.touch_sources, config.touchSource) {
                    config = config.copy(touchSource = it); save()
                },
                Ui.switchRow(this, "隐藏系统原生触摸圆点", "把 show_touches 钳为 0", config.hideNativeDot) {
                    config = config.copy(hideNativeDot = it); save()
                },
                Ui.switchRow(this, "DPR 自适应（上限 2x）", "关闭则按屏幕真实密度渲染", config.dprCapEnabled) {
                    config = config.copy(dprCapEnabled = it); save()
                }
            )
        )

        box.addView(Ui.sectionLabel(this, "调色板 · 深色主题").also { darkPaletteLabel = it })
        box.addView(
            Ui.group(
                this,
                Ui.colorRow(this, "填充圆", config.darkFill) { config = config.copy(darkFill = it); save() },
                Ui.colorRow(this, "光弧起始色", config.darkArcStart) { config = config.copy(darkArcStart = it); save() },
                Ui.colorRow(this, "光弧结束色", config.darkArcEnd) { config = config.copy(darkArcEnd = it); save() },
                Ui.colorRow(this, "三角火花", config.darkSpark) { config = config.copy(darkSpark = it); save() }
            )
        )

        box.addView(Ui.sectionLabel(this, "调色板 · 浅色主题").also { lightPaletteLabel = it })
        box.addView(
            Ui.group(
                this,
                Ui.colorRow(this, "填充圆", config.lightFill) { config = config.copy(lightFill = it); save() },
                Ui.colorRow(this, "光弧起始色", config.lightArcStart) { config = config.copy(lightArcStart = it); save() },
                Ui.colorRow(this, "光弧结束色", config.lightArcEnd) { config = config.copy(lightArcEnd = it); save() },
                Ui.colorRow(this, "三角火花", config.lightSpark) { config = config.copy(lightSpark = it); save() }
            )
        )

        box.addView(Ui.secondaryButton(this, "恢复默认调色板") {
            val d = Config.default()
            config = config.copy(
                darkFill = d.darkFill, darkArcStart = d.darkArcStart,
                darkArcEnd = d.darkArcEnd, darkSpark = d.darkSpark,
                lightFill = d.lightFill, lightArcStart = d.lightArcStart,
                lightArcEnd = d.lightArcEnd, lightSpark = d.lightSpark
            )
            save()
            recreate()
        })
        updateThemeUi()
        return scroll
    }

    // ---------------- 关于页 ----------------

    private fun buildAboutPage(): ScrollView {
        val (scroll, box) = Ui.pageScaffold(this)
        box.addView(pageHeader("关于"))

        val imageCard = FrameLayout(this).apply {
            background = Ui.cardBg(this@SettingsActivity, Ui.CARD, 24f)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(Ui.MATCH, dip(240f))
        }
        imageCard.addView(ImageView(this).apply {
            setImageResource(R.drawable.effect_demo)
            // 原图是 400x400 正方形；CENTER_CROP 会把上下光弧裁掉，改用完整等比显示
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH)
        })
        box.addView(imageCard)
        box.addView(
            Ui.body(
                this,
                "MYclick 是一款为安卓手机提供触摸位置特效提示的工具，让录屏时方便观看者更明显地看到点击了哪里。" +
                    "同时专门为 ColorOS 系统提供仅录屏时生效的检测。"
            )
        )

        box.addView(Ui.sectionLabel(this, "作用于哪个录屏应用 · 打开时自动识别本机"))
        val targetRow = Ui.editRow(this, "目标包名", config.targetPackage)
        val extraRow = Ui.editRow(this, "附加包名（逗号分隔）", config.extraTargets)
        targetEdit = targetRow.input
        extraEdit = extraRow.input
        box.addView(Ui.group(this, targetRow.view, extraRow.view))
        box.addView(Ui.secondaryButton(this, "探测本机录屏应用并选择") { detectTarget() })
        box.addView(Ui.body(this, "非ColorOS不保证录屏检测功能正常。"))

        box.addView(Ui.sectionLabel(this, "工具与链接"))
        box.addView(
            Ui.group(
                this,
                Ui.actionRow(this, "放行悬浮窗权限", "Shizuku → root 兜底，纯 Xposed 不需要") { grantOverlay() },
                Ui.actionRow(this, "触发一次测试点击", "坐标 (540, 1200)，需录屏进程正在运行") { testTouch() },
                Ui.actionRow(this, "重新推送配置", "保存并广播 CONFIG_PUSH 到被 Hook 进程") {
                    applyTargetFields()
                    TouchRelay.sendConfig(this, config)
                    toast("已广播配置")
                },
                Ui.actionRow(this, "GitHub 仓库", REPO_URL.removePrefix("https://")) { openUrl(REPO_URL) },
                Ui.actionRow(this, "下载 Release 包", RELEASES_URL.removePrefix("https://")) { openUrl(RELEASES_URL) }
            )
        )

        box.addView(Ui.sectionLabel(this, "各模式排障"))
        box.addView(
            Ui.group(
                this,
                Ui.infoRow(this, "LSPosed", "没有特效：确认「系统框架」和录屏 App 已勾选，然后重启手机").view,
                Ui.infoRow(this, "Shizuku", "没有特效：确认 Shizuku 服务已启动；手机重启后需重新启动").view,
                Ui.infoRow(this, "Root", "没有特效：确认已在 Magisk / KernelSU 中允许 MYclick").view
            )
        )
        box.addView(Ui.sectionLabel(this, "App 后台保活"))
        box.addView(
            Ui.group(
                this,
                Ui.infoRow(this, "电池设置", "对 MYclick 选择「不限制」，并允许自启动与后台运行").view,
                Ui.infoRow(this, "最近任务", "对 MYclick 下拉或上锁，防止被系统清理").view,
                Ui.infoRow(this, "常驻通知", "请勿关闭，它是保持特效在后台运行的前台通知").view
            )
        )
        box.addView(Ui.body(this, "LSPosed 模块 · Xposed API 82 · Kotlin · 无第三方运行时依赖 · Apache-2.0"))
        return scroll
    }

    // ---------------- 深浅色 ----------------

    /** 当前实际生效的是不是深色调色板（强制深/浅色优先，否则跟随系统实时判定） */
    private fun effectiveDark(): Boolean = config.isDark(NightMode.isDark(this))

    /**
     * 页面标题行：左边大标题，右边主题按钮。
     *
     * 按钮放在每个页面自己的内容里，随页面一起滚动，而不是钉在整屏右上角。
     * 垂直位置与标题文字对齐：按钮顶端和标题的上内边距（30dp）齐平。
     */
    private fun pageHeader(title: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP)
        addView(Ui.pageTitle(this@SettingsActivity, title).apply {
            layoutParams = LinearLayout.LayoutParams(0, Ui.WRAP, 1f)
        })
        addView(buildThemeButton().apply {
            layoutParams = LinearLayout.LayoutParams(dip(46f), dip(46f)).apply {
                topMargin = dip(30f)
            }
        })
    }

    /**
     * 主题按钮：点一次按「跟随系统 → 强制深色 → 强制浅色」循环。
     * 图标直接反映当前档位（半明半暗圆 / 月牙 / 太阳），一眼看出现在是什么模式。
     */
    private fun buildThemeButton(): ImageView {
        val btn = ImageView(this).apply {
            isClickable = true
            isFocusable = true
            background = Ui.cardBg(this@SettingsActivity, Ui.CARD, 23f)
            elevation = dip(8f).toFloat()
            setPadding(dip(11f), dip(11f), dip(11f), dip(11f))
            setOnClickListener { cycleThemeMode(it) }
        }
        themeButtons += btn
        updateThemeButtonIcon()
        return btn
    }

    private fun cycleThemeMode(anchor: View) {
        // 过渡进行中再点会叠出多层遮罩，直接忽略
        if (themeTransitioning) return
        themeTransitioning = true
        val next = when (config.themeMode) {
            Config.THEME_FOLLOW_SYSTEM -> Config.THEME_DARK
            Config.THEME_DARK -> Config.THEME_LIGHT
            else -> Config.THEME_FOLLOW_SYSTEM
        }
        // 先拍旧界面：save() 会刷新按钮图标和调色板标题，快照里要保留旧主题的样子
        val fromDark = Ui.isDark
        val fromCard = Ui.CARD
        val fromText = Ui.TEXT
        val snapshot = captureWindow()
        NightMode.invalidate()
        config = config.copy(themeMode = next)
        save()

        // 明暗真的变了才做圆形过渡，圆心取主题按钮在窗口里的中心
        val toDark = effectiveDark()
        if (snapshot != null && toDark != fromDark) {
            val loc = IntArray(2).also { anchor.getLocationInWindow(it) }
            ThemeTransition.pending = ThemeTransition.Reveal(
                snapshot = snapshot,
                cx = loc[0] + anchor.width / 2f,
                cy = loc[1] + anchor.height / 2f,
                startRadius = anchor.width / 2f,
                toDark = toDark,
                fromCard = fromCard,
                fromText = fromText,
            )
        } else {
            snapshot?.recycle()
        }
        recreate()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    /** 把当前窗口画成一张位图（旧主题的界面快照） */
    private fun captureWindow(): Bitmap? = runCatching {
        val v = window.decorView
        if (v.width <= 0 || v.height <= 0) return null
        Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { bmp ->
            v.draw(Canvas(bmp))
        }
    }.getOrNull()

    /** 新实例启动后：旧界面快照以主题按钮为圆心做圆形过渡，露出已经换成新主题的界面 */
    private fun playThemeReveal(reveal: ThemeTransition.Reveal) {
        val host = window.decorView as? ViewGroup ?: run { reveal.snapshot.recycle(); return }
        ThemeRevealView(this, reveal).also {
            host.addView(it, ViewGroup.LayoutParams(Ui.MATCH, Ui.MATCH))
            it.play()
        }
        fadeThemeButtons(reveal)
    }

    /**
     * 主题按钮的底色和图标色从旧主题渐变到新主题，时长与圆形过渡一致。
     * 按钮正好在圆心，圆一开始就把它罩在里面，不渐变的话会在第一帧直接跳色。
     */
    private fun fadeThemeButtons(reveal: ThemeTransition.Reveal) {
        val argb = ArgbEvaluator()
        fun tint(f: Float) {
            val card = argb.evaluate(f, reveal.fromCard, Ui.CARD) as Int
            val text = argb.evaluate(f, reveal.fromText, Ui.TEXT) as Int
            themeButtons.forEach {
                (it.background as GradientDrawable).setColor(card)
                it.setColorFilter(text)
            }
        }
        tint(0f)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ThemeTransition.DURATION_MS
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { tint(it.animatedFraction) }
            start()
        }
    }

    private fun updateThemeButtonIcon() {
        val res = when (config.themeMode) {
            Config.THEME_DARK -> R.drawable.ic_theme_dark
            Config.THEME_LIGHT -> R.drawable.ic_theme_light
            else -> R.drawable.ic_theme_auto
        }
        val desc = when (config.themeMode) {
            Config.THEME_DARK -> "主题：强制深色"
            Config.THEME_LIGHT -> "主题：强制浅色"
            else -> "主题：跟随系统"
        }
        themeButtons.forEach {
            it.setImageResource(res)
            it.setColorFilter(Ui.TEXT)
            it.contentDescription = desc
        }
    }

    /** 两套调色板标题上的「当前生效」标记跟着判定结果走 */
    private fun updateThemeUi() {
        if (!::config.isInitialized) return
        val dark = effectiveDark()
        updateThemeButtonIcon()
        darkPaletteLabel?.text =
            if (dark) "调色板 · 深色主题（当前生效）" else "调色板 · 深色主题"
        lightPaletteLabel?.text =
            if (dark) "调色板 · 浅色主题" else "调色板 · 浅色主题（当前生效）"
    }

    /**
     * 系统深浅色切换。
     *
     * 正常情况下 uiMode 变化会让 Activity 重建、这里不会回调；但部分 ROM 会直接
     * 改配置而不重建，补一个兜底，保证预览和按钮文案立刻跟着变。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        NightMode.invalidate()
        if (!::config.isInitialized) return
        // 深浅色变了就整套重建（资源主题、所有控件颜色都已固化在 View 上）；
        // 其余配置变化（字号、旋转等）只刷新预览即可。
        if (config.isDark(NightMode.isDark(this)) != Ui.isDark) {
            recreate()
            return
        }
        if (::preview.isInitialized) preview.applyConfig(config)
        updateThemeUi()
    }

    // ---------------- 行为 ----------------

    private fun save() {
        Prefs.write(this, config)
        preview.applyConfig(config)
        updateThemeUi()
        // 两个互斥开关的选中状态跟着配置走（静默，不弹确认框）
        globalRows.forEach { Ui.syncSwitch(it, config.globalEffect) }
        recordingRows.forEach { Ui.syncSwitch(it, config.enabled) }
        syncGlobalReceiver()
        ensureOverlayPermission()
        // 本进程宿主：开关变化直接同步；关闭时会立刻收掉绘制进程
        runCatching { ShellFxHost.sync(this, config) }
        // 前台服务保活：退出界面后特效仍要继续工作（否则 ColorOS 几秒回收进程）
        runCatching { FxService.sync(this) }
        if (config.effectOn() && config.shizukuMode) ensureNotifyPermission()
    }

    /**
     * 旧的「模块进程静态接收器」兜底已经停用。
     *
     * 全局绘制现在完全由 system_server 里的宿主承担（同进程直连，无广播延迟）；
     * 再保留一个靠广播触发的第二绘制入口，只会让同一次点击出现两份特效，
     * 而且它的 nonce 是公开默认值，任何 App 都能伪造。这里始终把组件禁用，
     * 系统会直接丢弃对应广播，不会为点击唤醒模块进程。
     */
    private fun syncGlobalReceiver() {
        if (globalReceiverEnabled == false) return
        globalReceiverEnabled = false
        runCatching {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, GlobalTouchReceiver::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    private fun ensureOverlayPermission() {
        if ((!config.globalEffect && !config.shizukuMode) || overlayGrantAttempted) return
        if (Settings.canDrawOverlays(this)) return
        overlayGrantAttempted = true
        Thread {
            val viaShizuku = ShizukuBridge.grantOverlayViaShizuku(packageName)
            if (viaShizuku == null) RootHelper.grantOverlay(packageName)
        }.start()
    }

    /**
     * 目标包名自动选定为用户手机上真实安装的录屏 App。
     * 当前值仍有效（含用户手动填写且已安装）时保持不动；否则用运行时探测结果替换。
     */
    private fun autoResolveTarget(current: Config): Config {
        if (PackageDetector.isInstalled(this, current.targetPackage)) return current
        val best = PackageDetector.detectBest(this) ?: return current
        return current.copy(targetPackage = best)
    }

    private fun applyTargetFields() {
        if (!::targetEdit.isInitialized || !::extraEdit.isInitialized) return
        val target = targetEdit.text.toString().trim().ifEmpty {
            PackageDetector.detectBest(this) ?: Config.DEFAULT_TARGET
        }
        val extra = extraEdit.text.toString().trim()
        if (target != config.targetPackage || extra != config.extraTargets) {
            config = config.copy(targetPackage = target, extraTargets = extra)
            save()
        }
    }

    /** 搜索本机包名含关键字的应用（默认 screenrec），点选即设为目标。 */
    private fun detectTarget() {
        val keyword = Ui.editRow(this, "搜索包名关键字", "screenrec")
        val results = mutableListOf<String>()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, results)
        val list = ListView(this).apply { this.adapter = adapter }
        fun search() {
            val key = keyword.input.text.toString().trim()
            results.clear()
            results += PackageDetector.search(this, key)
            adapter.notifyDataSetChanged()
            if (results.isEmpty()) toast("未找到包名含「$key」的应用")
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dip(8f), 0, dip(8f), 0)
            addView(keyword.view)
            addView(Ui.secondaryButton(this@SettingsActivity, "搜索") { search() })
            addView(list, LinearLayout.LayoutParams(Ui.MATCH, dip(300f)))
            addView(Ui.body(this@SettingsActivity, "非ColorOS不保证录屏检测功能正常。"))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("选择目标录屏应用")
            .setView(box)
            .setNegativeButton("取消", null)
            .create()
        list.setOnItemClickListener { _, _, pos, _ ->
            targetEdit.setText(results[pos])
            applyTargetFields()
            dialog.dismiss()
        }
        dialog.show()
        search()
    }


    /**
     * 打开 Shizuku 模式后的引导。
     *
     * 三条状态分开处理，避免用户开了开关却毫无反应：
     *  - Shizuku 已授权 → 直接启动绘制进程
     *  - Shizuku 在跑但没授权本应用 → 弹系统授权框
     *  - 没在运行 → 引导安装 / 启动；有 root 的用户建议改选 Root 模式
     *
     * 这里不再悄悄退回 su：用户选的是哪种方式，就只用哪种。
     *
     * 探测必须放后台：`su` 可能弹出 root 授权框并一直等，放在主线程就是一次 ANR。
     */
    private fun afterShizukuEnabled() {
        Thread {
            val backend = PrivilegedShell.backend()
            val desc = PrivilegedShell.describe()
            val needsGrant = PrivilegedShell.shizukuNeedsGrant()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    needsGrant -> {
                        AlertDialog.Builder(this)
                            .setTitle("需要授权")
                            .setMessage("Shizuku 正在运行，但还没有授权给 MYclick。授权后即可读取触摸事件。")
                            .setPositiveButton("授权") { _, _ ->
                                PrivilegedShell.requestShizukuPermission()
                            }
                            .setNegativeButton("稍后", null)
                            .show()
                    }
                    backend == PrivilegedShell.Backend.SHIZUKU -> {
                        ShellFxHost.sync(this, config)
                        toast("Shizuku 模式已启动：$desc")
                    }
                    else -> {
                        AlertDialog.Builder(this)
                            .setTitle("Shizuku 还没启动")
                            .setMessage(
                                "Shizuku 模式需要 Shizuku 服务正在运行。\n\n" +
                                    "· 已装 Shizuku：打开它，用「无线调试」或「root」启动服务，" +
                                    "再回到这里点一次「Shizuku 模式」卡片\n" +
                                    "· 没装 Shizuku：装好后按上面步骤启动\n\n" +
                                    "手机已经 root 的话，可以直接选「Root 模式」，不需要 Shizuku。"
                            )
                            .setPositiveButton("打开 Shizuku") { _, _ -> openShizuku() }
                            .setNegativeButton("知道了", null)
                            .show()
                    }
                }
            }
        }.start()
    }

    /**
     * Shizuku 只有在应用实际发起 requestPermission() 后，才会把应用登记到授权列表。
     * 设备有 root 时旧流程会静默走 root，导致 Shizuku 里一直看不到 MYclick。
     */
    private fun requestShizukuPermissionIfNeeded(): Boolean {
        if (shizukuPermissionPrompted) return false
        // 只有选了 Shizuku 模式才去申请；Root 模式用户不该被 Shizuku 弹窗打扰
        if (!::config.isInitialized || !config.effectOn() || config.runMode != Config.RUN_SHIZUKU) return false
        if (!PrivilegedShell.shizukuNeedsGrant()) return false
        shizukuPermissionPrompted = true
        val requested = PrivilegedShell.requestShizukuPermission()
        if (!requested) shizukuPermissionPrompted = false
        return requested
    }

    private fun openShizuku() {
        val pkg = "moe.shizuku.privileged.api"
        val launch = runCatching { packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()
        if (launch != null) {
            runCatching { startActivity(launch) }
                .onFailure { toast("无法启动 Shizuku") }
            return
        }
        // 没装：给个下载页
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { toast("请先安装 Shizuku") }
    }

    private fun grantOverlay() {
        val status = ShizukuBridge.probe(this)
        val pkg = config.targetPackage
        Thread {
            val shizuku = ShizukuBridge.grantOverlayViaShizuku(pkg)
            val root = if (shizuku == null) RootHelper.grantOverlay(pkg) else null
            val text = buildString {
                append(status.message).append('\n')
                append("root 可用: ").append(RootHelper.isRootAvailable()).append('\n')
                append("Shizuku: ").append(shizuku ?: "未使用").append('\n')
                if (root != null) append(root.log)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                AlertDialog.Builder(this).setTitle("特权放行结果").setMessage(text)
                    .setPositiveButton("好", null).show()
            }
        }.start()
    }

    private fun testTouch() {
        val i = Intent(TouchRelay.ACTION_TEST_TOUCH)
            .putExtra(TouchRelay.EX_X, 540f)
            .putExtra(TouchRelay.EX_Y, 1200f)
            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
        sendBroadcast(i)
        toast("已发送（需录屏进程正在运行）")
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Android 13+ 前台服务通知需要运行时权限；不给也能跑，只是看不到常驻通知 */
    private fun ensureNotifyPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4301)
        }
    }

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.25"
    }.getOrDefault("1.0.25")

    private fun toast(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val KEY_TAB = "tab"
        const val TAB_FX = 1
        const val TAB_ABOUT = 2
        const val REPO_URL = "https://github.com/mingyu0401/MYclick"
        const val RELEASES_URL = "https://github.com/mingyu0401/MYclick/releases"
    }
}
