package com.myclick.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.myclick.R
import com.myclick.core.Config
import com.myclick.core.PackageDetector
import com.myclick.core.Prefs
import com.myclick.core.RootHelper
import com.myclick.core.ShizukuBridge
import com.myclick.core.TouchRelay
import com.myclick.fx.EffectSurfaceView

/**
 * 模块设置页。
 *
 * 布局参照 ONextBox：纯黑底 + 大标题 + 石板蓝主卡 + 双列状态卡 + 分组卡片列表，
 * 底部悬浮胶囊导航把内容拆成「首页 / 特效 / 关于」三页，避免单页长列表。
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
    private lateinit var navIndicator: View
    private var currentTab = -1

    private val density: Float get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = Prefs.read(this)
        // 旧版「显示时机 → 始终显示」迁移到新的「全局生效」开关，
        // 否则 UI 显示关闭、实际却一直全局显示，状态会对不上。
        if (config.gateMode == Config.GATE_ALWAYS) {
            config = config.copy(globalEffect = true, gateMode = Config.GATE_AUTO)
        }
        // 目标包名自动选定为本机真实安装的录屏 App；写盘时会自动广播给被 Hook 进程
        config = autoResolveTarget(config)
        Prefs.write(this, config)

        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

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

        selectTab(savedInstanceState?.getInt(KEY_TAB, 0) ?: 0)
        preview.post { preview.applyConfig(config, resources.configuration.uiMode) }
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
            background = Ui.cardBg(this@SettingsActivity, Color.parseColor("#3B3B3B"), 100f)
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
            preview.post { preview.applyConfig(config, resources.configuration.uiMode) }
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
        box.addView(Ui.pageTitle(this, "首页"))
        box.addView(heroCard())

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dip(12f) }
        }
        val rootCard = Ui.statusCard(this, "Root 状态", "检测中…")
        val lspCard = Ui.statusCard(this, "LSPosed 状态", "检测中…")
        row.addView(rootCard.view)
        row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dip(12f), 1) })
        row.addView(lspCard.view)
        box.addView(row)
        probeStatus(rootCard.value, lspCard.value)

        box.addView(Ui.sectionLabel(this, "设备信息"))
        box.addView(
            Ui.group(
                this,
                Ui.infoRow(this, "设备型号", Build.MODEL).view,
                Ui.infoRow(this, "Android / API", "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}").view,
                Ui.infoRow(this, "系统版本", Build.DISPLAY).view,
                Ui.infoRow(this, "系统架构", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown").view,
                Ui.infoRow(this, "目标应用", config.targetPackage).view
            )
        )

        box.addView(Ui.sectionLabel(this, "快速设置"))
        box.addView(
            Ui.group(
                this,
                Ui.switchRow(this, "启用特效", "关闭后所有 Hook 直接跳过", config.enabled) {
                    config = config.copy(enabled = it); save()
                },
                Ui.switchRow(this, "隐藏系统原生触摸圆点", "把 show_touches 钳为 0", config.hideNativeDot) {
                    config = config.copy(hideNativeDot = it); save()
                },
                Ui.switchRow(
                    this,
                    "全局生效",
                    "全局显示可能会厌烦，慎重打开",
                    config.globalEffect,
                    confirmOnEnable = "开启后，即使没有在录屏，每次点击屏幕也会出现特效。" +
                        "\n\n全局显示可能会厌烦，慎重打开。（默认仅录屏时显示）"
                ) {
                    config = config.copy(globalEffect = it); save()
                }.also { globalRows += it }
            )
        )
        box.addView(Ui.body(this, "⚠ 全局显示可能会厌烦，慎重打开；默认关闭，仅在录屏时显示特效。"))

        box.addView(Ui.primaryButton(this, "保存并推送配置") {
            applyTargetFields()
            TouchRelay.sendConfig(this, config)
            toast("已保存并广播配置")
        })
        box.addView(
            Ui.body(
                this,
                "修改任意配置都会即时写入并广播。系统框架 Hook 需要在 LSPosed 中勾选「系统框架」并重启后生效；\n" +
                    "录屏启动时不能再往 AUTO_MIRROR 虚拟屏添加窗口，否则 MediaProjection 会拿到全黑画面。"
            )
        )
        return scroll
    }

    private fun heroCard(): FrameLayout = FrameLayout(this).apply {
        background = Ui.heroBg(this@SettingsActivity)
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(Ui.MATCH, dip(164f))

        // 与桌面 App 图标共用同一个 vector，圆形裁切后自然融入主卡
        addView(
            ImageView(this@SettingsActivity).apply {
                setImageResource(R.drawable.ic_launcher)
                scaleType = ImageView.ScaleType.FIT_CENTER
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
                clipToOutline = true
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
                text = "BASpark 点击特效 · LSPosed 模块"
                textSize = 14f
                setTextColor(Color.parseColor("#D3DBE5"))
                setPadding(0, dip(4f), 0, 0)
            })
        }
        addView(texts)
    }

    private fun probeStatus(rootView: TextView, lspView: TextView) {
        Thread {
            val hasRoot = RootHelper.isRootAvailable()
            val hasManager = runCatching {
                packageManager.getPackageInfo("org.lsposed.manager", 0) != null
            }.getOrDefault(false)
            val hasLspdDir = RootHelper.su("test -d /data/adb/lspd && echo yes")?.contains("yes") == true
            val lsposed = hasManager || hasLspdDir
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                rootView.text = if (hasRoot) "可用 (su)" else "不可用 · 纯 Xposed 也可工作"
                lspView.text = if (lsposed) "已启用 · 重启生效" else "未检测到 · 请检查作用域"
            }
        }.start()
    }

    // ---------------- 特效页 ----------------

    private fun buildFxPage(): ScrollView {
        val (scroll, box) = Ui.pageScaffold(this)
        box.addView(Ui.pageTitle(this, "特效"))

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
        box.addView(
            Ui.group(
                this,
                Ui.switchRow(this, "启用特效", "关闭后所有 Hook 直接跳过", config.enabled) {
                    config = config.copy(enabled = it); save()
                },
                Ui.seekRow(this, "SCALE 大小", "规格默认 1.4", config.scale, 5, 30) {
                    config = config.copy(scale = it / 10f); save()
                },
                Ui.seekRow(this, "动画速度", "1.0 = 60fps 原速", config.speed, 3, 30) {
                    config = config.copy(speed = it / 10f); save()
                },
                Ui.choiceRow(this, "主题", R.array.theme_modes, config.themeMode) {
                    config = config.copy(themeMode = it); save()
                },
                Ui.choiceRow(this, "触摸事件来源", R.array.touch_sources, config.touchSource) {
                    config = config.copy(touchSource = it); save()
                },
                Ui.switchRow(
                    this,
                    "全局生效",
                    "全局显示可能会厌烦，慎重打开",
                    config.globalEffect,
                    confirmOnEnable = "开启后，即使没有在录屏，每次点击屏幕也会出现特效。" +
                        "\n\n全局显示可能会厌烦，慎重打开。（默认仅录屏时显示）"
                ) {
                    config = config.copy(globalEffect = it); save()
                }.also { globalRows += it },
                Ui.switchRow(this, "隐藏系统原生触摸圆点", "把 show_touches 钳为 0", config.hideNativeDot) {
                    config = config.copy(hideNativeDot = it); save()
                },
                Ui.switchRow(this, "DPR 自适应（上限 2x）", "关闭则按屏幕真实密度渲染", config.dprCapEnabled) {
                    config = config.copy(dprCapEnabled = it); save()
                }
            )
        )

        box.addView(Ui.sectionLabel(this, "调色板 · 深色主题"))
        box.addView(
            Ui.group(
                this,
                Ui.colorRow(this, "填充圆", config.darkFill) { config = config.copy(darkFill = it); save() },
                Ui.colorRow(this, "光弧起始色", config.darkArcStart) { config = config.copy(darkArcStart = it); save() },
                Ui.colorRow(this, "光弧结束色", config.darkArcEnd) { config = config.copy(darkArcEnd = it); save() },
                Ui.colorRow(this, "三角火花", config.darkSpark) { config = config.copy(darkSpark = it); save() }
            )
        )

        box.addView(Ui.sectionLabel(this, "调色板 · 浅色主题"))
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
        box.addView(Ui.body(this, "预览区与录屏覆盖层使用同一套 EffectEngine / Choreographer / SurfaceView，所见即录屏所得。"))
        return scroll
    }

    // ---------------- 关于页 ----------------

    private fun buildAboutPage(): ScrollView {
        val (scroll, box) = Ui.pageScaffold(this)
        box.addView(Ui.pageTitle(this, "关于"))

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
                "效果示意：扩散填充圆 + 双旋转光弧 + 三角火花，以实际点击点为圆心；" +
                    "橙色扩散圆、双段白 → 淡蓝光弧与四个三角火花都会被录进视频。"
            )
        )

        box.addView(Ui.sectionLabel(this, "作用于哪个录屏应用 · 打开时自动识别本机"))
        val targetRow = Ui.editRow(this, "目标包名", config.targetPackage)
        val extraRow = Ui.editRow(this, "附加包名（逗号分隔）", config.extraTargets)
        targetEdit = targetRow.input
        extraEdit = extraRow.input
        box.addView(Ui.group(this, targetRow.view, extraRow.view))
        box.addView(Ui.secondaryButton(this, "重新探测本机录屏应用并自动选定") { detectTarget() })

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

        box.addView(Ui.sectionLabel(this, "排障速查"))
        box.addView(
            Ui.group(
                this,
                Ui.infoRow(this, "完全没有特效", "多半只勾了录屏 App、没勾「系统框架」，或没重启").view,
                Ui.infoRow(this, "录屏产物全黑 / 只有几帧", "覆盖层误加到 AUTO_MIRROR 虚拟屏；本版本已过滤虚拟屏").view,
                Ui.infoRow(this, "特效位置偏移", "检查窗口 inset 补偿；当前用 getLocationOnScreen 对齐").view,
                Ui.infoRow(this, "点击与特效不同步", "广播需显式 setPackage + FLAG_RECEIVER_FOREGROUND").view
            )
        )
        box.addView(Ui.body(this, "LSPosed 模块 · Xposed API 82 · Kotlin · 无第三方运行时依赖 · Apache-2.0"))
        return scroll
    }

    // ---------------- 行为 ----------------

    private fun save() {
        Prefs.write(this, config)
        preview.applyConfig(config, resources.configuration.uiMode)
        // 首页 / 特效页两份「全局生效」开关保持一致（静默，不弹确认框）
        globalRows.forEach { Ui.syncSwitch(it, config.globalEffect) }
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

    private fun detectTarget() {
        val found = PackageDetector.detect(this)
        if (found.isEmpty()) {
            AlertDialog.Builder(this).setTitle("探测结果")
                .setMessage("未在本机找到屏幕录制应用，可手动填写目标包名。")
                .setPositiveButton("好", null).show()
            return
        }
        val chosen = found.first()
        targetEdit.setText(chosen)
        applyTargetFields()
        AlertDialog.Builder(this)
            .setTitle("已自动选定本机录屏应用")
            .setMessage("当前：$chosen\n\n本机候选：\n" + found.joinToString("\n"))
            .setPositiveButton("好", null)
            .show()
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

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.1"
    }.getOrDefault("1.0.1")

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
