package com.myclick.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
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
    private var currentTab = -1

    private val density: Float get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = Prefs.read(this)
        // 首次打开也要落盘 + 广播一次：被 Hook 进程可能已经启动，
        // 而 XSharedPreferences / ContentProvider 在它们那边未必可用。
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

    private fun buildNav(): LinearLayout {
        val labels = listOf("首页", "特效", "关于")
        val icons = listOf(
            R.drawable.ic_nav_home,
            R.drawable.ic_nav_fx,
            R.drawable.ic_nav_info
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.cardBg(this@SettingsActivity, Ui.NAV, 100f)
            elevation = dip(12f).toFloat()
            setPadding(dip(10f), 0, dip(10f), 0)
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
                    setPadding(0, dip(4f), 0, 0)
                }
                item.addView(icon)
                item.addView(text)
                navIcons += icon
                navLabels += text
                addView(item)
            }
        }
    }

    private fun selectTab(index: Int) {
        if (index == currentTab) return
        if (currentTab == TAB_ABOUT) applyTargetFields()
        currentTab = index
        pages.forEachIndexed { i, v -> v.visibility = if (i == index) View.VISIBLE else View.GONE }
        navIcons.forEachIndexed { i, icon ->
            icon.setColorFilter(if (i == index) Ui.ACCENT else Ui.SUB)
        }
        navLabels.forEachIndexed { i, label ->
            label.setTextColor(if (i == index) Ui.TEXT else Ui.SUB)
            label.setTypeface(label.typeface, if (i == index) Typeface.BOLD else Typeface.NORMAL)
        }
        if (index == TAB_FX) {
            preview.post { preview.applyConfig(config, resources.configuration.uiMode) }
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
                Ui.choiceRow(this, "显示时机", R.array.gate_modes, config.gateMode) {
                    config = config.copy(gateMode = it); save()
                }
            )
        )

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

        addView(
            HeroDecorView(this@SettingsActivity),
            FrameLayout.LayoutParams(dip(160f), Ui.MATCH, Gravity.END)
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
                Ui.choiceRow(this, "显示时机", R.array.gate_modes, config.gateMode) {
                    config = config.copy(gateMode = it); save()
                },
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

        box.addView(Ui.sectionLabel(this, "它是怎么工作的"))
        box.addView(
            Ui.group(
                this,
                Ui.infoRow(this, "① 系统框架取坐标", "Hook InputEventReceiver#dispatchInputEvent，读取全局 ACTION_DOWN 的 rawX / rawY").view,
                Ui.infoRow(this, "② 广播中继到录屏进程", "显式包名 + FLAG_RECEIVER_FOREGROUND，端到端延迟约 2ms").view,
                Ui.infoRow(this, "③ 透明覆盖层绘制", "主屏 TYPE_APPLICATION_OVERLAY + SurfaceView + Choreographer，可被 MediaProjection 捕获").view
            )
        )

        box.addView(Ui.sectionLabel(this, "作用于哪个录屏应用"))
        val targetRow = Ui.editRow(this, "目标包名", config.targetPackage)
        val extraRow = Ui.editRow(this, "附加包名（逗号分隔）", config.extraTargets)
        targetEdit = targetRow.input
        extraEdit = extraRow.input
        box.addView(Ui.group(this, targetRow.view, extraRow.view))
        box.addView(Ui.secondaryButton(this, "运行时探测系统录屏包（pm）") { detectTarget() })

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
    }

    private fun applyTargetFields() {
        if (!::targetEdit.isInitialized || !::extraEdit.isInitialized) return
        val target = targetEdit.text.toString().trim().ifEmpty { Config.DEFAULT_TARGET }
        val extra = extraEdit.text.toString().trim()
        if (target != config.targetPackage || extra != config.extraTargets) {
            config = config.copy(targetPackage = target, extraTargets = extra)
            save()
        }
    }

    private fun detectTarget() {
        val found = PackageDetector.detect(this)
        AlertDialog.Builder(this)
            .setTitle("探测结果")
            .setMessage(if (found.isEmpty()) "未找到" else found.joinToString("\n"))
            .setPositiveButton("用第一个") { _, _ ->
                if (found.isNotEmpty()) {
                    targetEdit.setText(found.first())
                    applyTargetFields()
                    toast("已切换到 ${found.first()}")
                }
            }
            .setNegativeButton("关闭", null)
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
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
    }.getOrDefault("1.0.0")

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
