package com.myclick.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

internal fun Context.dip(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/**
 * 一套纯 framework 控件的深色卡片式设计语言，视觉参照 ONextBox：
 * 纯黑底、大标题、石板蓝主卡、双列状态卡、#1A1A1A 分组卡、底部胶囊导航。
 * 不引入 AndroidX / Material，保持模块 APK 零运行时依赖。
 */
object Ui {

    val BG: Int = Color.parseColor("#000000")
    val CARD: Int = Color.parseColor("#1A1A1A")
    val CARD_SOFT: Int = Color.parseColor("#141414")
    val HERO: Int = Color.parseColor("#647589")
    val HERO_END: Int = Color.parseColor("#4E5D70")
    val DIVIDER: Int = Color.parseColor("#2E2E2E")
    val TEXT: Int = Color.parseColor("#F2F2F2")
    val SUB: Int = Color.parseColor("#9B9B9B")
    val ACCENT: Int = Color.parseColor("#F9A86C")
    val NAV: Int = Color.parseColor("#292929")
    val CHIP: Int = Color.parseColor("#2A2A2A")

    const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    fun cardBg(ctx: Context, color: Int = CARD, radiusDp: Float = 26f): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = ctx.dip(radiusDp).toFloat()
        }

    fun heroBg(ctx: Context): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf(HERO, HERO_END)
    ).apply { cornerRadius = ctx.dip(26f).toFloat() }

    private fun ripple(ctx: Context): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(Color.parseColor("#22FFFFFF")),
        null,
        GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = ctx.dip(18f).toFloat()
        }
    )

    /** 页面骨架：一个可滚动容器 + 带底部留白的纵向内容区。 */
    fun pageScaffold(ctx: Context): Pair<ScrollView, LinearLayout> {
        val scroll = ScrollView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val side = ctx.dip(20f)
            setPadding(side, 0, side, ctx.dip(120f))
        }
        scroll.addView(box, FrameLayout.LayoutParams(MATCH, WRAP))
        return scroll to box
    }

    fun pageTitle(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 34f
        setTextColor(TEXT)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(ctx.dip(2f), ctx.dip(30f), 0, ctx.dip(22f))
    }

    fun sectionLabel(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 14f
        setTextColor(SUB)
        setTypeface(typeface, Typeface.BOLD)
        val p = ctx.dip(6f)
        setPadding(p, ctx.dip(26f), p, ctx.dip(10f))
    }

    fun body(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        setTextColor(SUB)
        setPadding(ctx.dip(6f), ctx.dip(14f), ctx.dip(6f), 0)
        setLineSpacing(ctx.dip(4f).toFloat(), 1f)
    }

    private fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(DIVIDER)
        layoutParams = LinearLayout.LayoutParams(MATCH, ctx.dip(0.6f).coerceAtLeast(1)).apply {
            marginStart = ctx.dip(20f)
            marginEnd = ctx.dip(20f)
        }
    }

    /** 分组卡片：行与行之间插入左右内缩的分隔线。 */
    fun group(ctx: Context, vararg rows: View): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = cardBg(ctx)
        setPadding(0, ctx.dip(6f), 0, ctx.dip(6f))
        rows.forEachIndexed { i, r ->
            if (i > 0) addView(divider(ctx))
            addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    private fun rowBox(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val h = ctx.dip(20f)
        val v = ctx.dip(14f)
        setPadding(h, v, h, v)
    }

    private fun rowTitle(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 17f
        setTextColor(TEXT)
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun rowSub(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        setTextColor(SUB)
        setPadding(0, ctx.dip(4f), 0, 0)
    }

    class InfoRow(val view: LinearLayout, val value: TextView)

    fun infoRow(ctx: Context, title: String, value: String): InfoRow {
        val box = rowBox(ctx)
        box.addView(rowTitle(ctx, title))
        val v = rowSub(ctx, value)
        box.addView(v)
        return InfoRow(box, v)
    }

    fun actionRow(ctx: Context, title: String, sub: String = "", onClick: () -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(ctx)
            isClickable = true
            isFocusable = true
            val h = ctx.dip(20f)
            val v = ctx.dip(14f)
            setPadding(h, v, h, v)
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                addView(rowTitle(ctx, title))
                if (sub.isNotEmpty()) addView(rowSub(ctx, sub))
            }
            addView(texts)
            addView(TextView(ctx).apply {
                text = "›"
                textSize = 24f
                setTextColor(SUB)
                setPadding(ctx.dip(10f), 0, 0, 0)
            })
            setOnClickListener { onClick() }
        }

    fun switchRow(
        ctx: Context,
        title: String,
        sub: String,
        checked: Boolean,
        /** 非空时：从关到开会先弹一次确认，取消则自动回退开关状态 */
        confirmOnEnable: String? = null,
        onChange: (Boolean) -> Unit
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val h = ctx.dip(20f)
        val v = ctx.dip(14f)
        setPadding(h, v, h, v)
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            addView(rowTitle(ctx, title))
            addView(rowSub(ctx, sub))
        })
        lateinit var sw: Switch
        sw = Switch(ctx).apply {
            isChecked = checked
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(ACCENT, Color.parseColor("#8A8A8A"))
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.parseColor("#66F9A86C"), Color.parseColor("#33FFFFFF"))
            )
        }
        val listener = CompoundButton.OnCheckedChangeListener { _, c ->
            if (c && !confirmOnEnable.isNullOrEmpty()) {
                AlertDialog.Builder(ctx)
                    .setTitle(title)
                    .setMessage(confirmOnEnable)
                    .setPositiveButton("仍然开启") { _, _ -> onChange(true) }
                    .setNegativeButton("取消") { _, _ -> sw.isChecked = false }
                    .setOnCancelListener { sw.isChecked = false }
                    .show()
            } else {
                onChange(c)
            }
        }
        // 保存监听器，供 syncSwitch() 静默刷新多页重复开关时临时解绑
        sw.tag = listener
        sw.setOnCheckedChangeListener(listener)
        addView(sw)
    }

    /** 静默同步开关状态：不弹确认框、不触发 onChange，避免多页开关互相递归写盘。 */
    fun syncSwitch(row: View, checked: Boolean) {
        val group = row as? ViewGroup ?: return
        val sw = (0 until group.childCount)
            .firstNotNullOfOrNull { group.getChildAt(it) as? Switch } ?: return
        val listener = sw.tag as? CompoundButton.OnCheckedChangeListener ?: return
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = checked
        sw.setOnCheckedChangeListener(listener)
    }

    fun seekRow(
        ctx: Context,
        label: String,
        desc: String,
        value: Float,
        min: Int,
        max: Int,
        onChange: (Int) -> Unit
    ): LinearLayout {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dip(20f), ctx.dip(14f), ctx.dip(20f), ctx.dip(10f))
        }
        val valueView = TextView(ctx).apply {
            text = "%.1f".format(value)
            textSize = 13f
            setTextColor(ACCENT)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = cardBg(ctx, CHIP, 10f)
            setPadding(ctx.dip(10f), ctx.dip(5f), ctx.dip(10f), ctx.dip(5f))
        }
        box.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                addView(rowTitle(ctx, label))
                addView(rowSub(ctx, desc))
            })
            addView(valueView)
        })
        val bar = SeekBar(ctx).apply {
            this.max = max
            this.min = min
            progress = (value * 10f).toInt().coerceIn(min, max)
            progressTintList = ColorStateList.valueOf(ACCENT)
            thumbTintList = ColorStateList.valueOf(ACCENT)
            setPadding(0, ctx.dip(8f), 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    valueView.text = "%.1f".format(p / 10f)
                    if (fromUser) onChange(p)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        return box
    }

    /**
     * 用 AlertDialog 单选代替原生 Spinner：外观可控、不会在初始化时误触发回调，
     * 也避免 DayNight 主题下 Spinner 弹窗文字变黑的问题。
     */
    fun choiceRow(
        ctx: Context,
        label: String,
        arrayRes: Int,
        selection: Int,
        onSelect: (Int) -> Unit
    ): LinearLayout {
        val raw = ctx.resources.getStringArray(arrayRes)
        val items: Array<CharSequence> = Array(raw.size) { raw[it] }
        var selected = selection.coerceIn(0, items.size - 1)
        val chip = TextView(ctx).apply {
            text = "${items[selected]}  ⌄"
            textSize = 13f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            background = cardBg(ctx, CHIP, 12f)
            setPadding(ctx.dip(12f), ctx.dip(8f), ctx.dip(12f), ctx.dip(8f))
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(ctx)
            isClickable = true
            isFocusable = true
            setPadding(ctx.dip(20f), ctx.dip(12f), ctx.dip(20f), ctx.dip(12f))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                addView(rowTitle(ctx, label))
                addView(rowSub(ctx, items[selected].toString()))
            })
            addView(chip)
            setOnClickListener {
                AlertDialog.Builder(ctx)
                    .setTitle(label)
                    .setSingleChoiceItems(items, selected) { dialog, which ->
                        selected = which
                        chip.text = "${items[which]}  ⌄"
                        onSelect(which)
                        dialog.dismiss()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    fun colorRow(
        ctx: Context,
        label: String,
        color: Int,
        onPicked: (Int) -> Unit
    ): LinearLayout {
        val swatch = View(ctx).apply {
            background = cardBg(ctx, color or -0x1000000, 9f)
            layoutParams = LinearLayout.LayoutParams(ctx.dip(30f), ctx.dip(30f))
        }
        val hex = TextView(ctx).apply {
            text = "#%06X".format(color and 0xFFFFFF)
            textSize = 13f
            setTextColor(SUB)
            setTypeface(typeface, Typeface.BOLD)
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(ctx)
            isClickable = true
            isFocusable = true
            setPadding(ctx.dip(20f), ctx.dip(12f), ctx.dip(20f), ctx.dip(12f))
            addView(swatch)
            addView(TextView(ctx).apply {
                text = label
                textSize = 16f
                setTextColor(TEXT)
                setPadding(ctx.dip(14f), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            addView(hex)
            addView(TextView(ctx).apply {
                text = "›"
                textSize = 22f
                setTextColor(SUB)
                setPadding(ctx.dip(10f), 0, 0, 0)
            })
            setOnClickListener {
                ColorPreferenceDialog.show(ctx, label, color) { picked ->
                    swatch.background = cardBg(ctx, picked or -0x1000000, 9f)
                    hex.text = "#%06X".format(picked and 0xFFFFFF)
                    onPicked(picked)
                }
            }
        }
    }

    class EditRow(val view: LinearLayout, val input: EditText)

    /** 一行「标题 + 输入框」，放进 group 卡片里；输入框不带默认下划线底色的强对比。 */
    fun editRow(ctx: Context, label: String, value: String): EditRow {
        val input = EditText(ctx).apply {
            setText(value)
            setSingleLine(true)
            textSize = 14f
            setTextColor(TEXT)
            setHintTextColor(SUB)
            background = cardBg(ctx, CHIP, 10f)
            setPadding(ctx.dip(12f), ctx.dip(9f), ctx.dip(12f), ctx.dip(9f))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ctx.dip(8f) }
        }
        val box = rowBox(ctx).apply {
            addView(rowTitle(ctx, label))
            addView(input)
        }
        return EditRow(box, input)
    }

    fun primaryButton(ctx: Context, text: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 16f
        setTextColor(Color.parseColor("#1A1208"))
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = cardBg(ctx, ACCENT, 18f)
        isClickable = true
        isFocusable = true
        setPadding(ctx.dip(16f), ctx.dip(15f), ctx.dip(16f), ctx.dip(15f))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ctx.dip(18f) }
    }

    fun secondaryButton(ctx: Context, text: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 15f
        setTextColor(TEXT)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = cardBg(ctx, CHIP, 18f)
        isClickable = true
        isFocusable = true
        setPadding(ctx.dip(16f), ctx.dip(14f), ctx.dip(16f), ctx.dip(14f))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ctx.dip(10f) }
    }

    class StatusCard(val view: LinearLayout, val value: TextView)

    fun statusCard(ctx: Context, title: String, initial: String): StatusCard {
        val value = TextView(ctx).apply {
            text = initial
            textSize = 14f
            setTextColor(SUB)
            setPadding(0, ctx.dip(10f), 0, 0)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg(ctx, CARD, 22f)
            setPadding(ctx.dip(18f), ctx.dip(18f), ctx.dip(18f), ctx.dip(18f))
            layoutParams = LinearLayout.LayoutParams(0, ctx.dip(112f), 1f)
            addView(TextView(ctx).apply {
                text = title
                textSize = 18f
                setTextColor(TEXT)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(value)
        }
        return StatusCard(box, value)
    }
}
