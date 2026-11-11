package com.myclick.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** 轻量 #RRGGBB 调色板输入对话框：色块预览 + 十六进制输入，无需任何三方库。 */
object ColorPreferenceDialog {

    fun show(context: Context, title: String, initial: Int, onPicked: (Int) -> Unit) {
        val dp = { v: Int -> (v * context.resources.displayMetrics.density).toInt() }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val swatch = View(context).apply {
            background = ColorDrawable(initial or -0x1000000)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56))
        }
        val hint = TextView(context).apply {
            text = "输入 #RRGGBB（例如 #F9A86C）"
            setPadding(0, dp(8), 0, dp(4))
        }
        val input = EditText(context).apply {
            setText("#%06X".format(initial and 0xFFFFFF))
            setSingleLine(true)
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(swatch)
        root.addView(hint)
        root.addView(input)

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                parse(s?.toString())?.let { swatch.background = ColorDrawable(it or -0x1000000) }
            }
        })

        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(root)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                parse(input.text.toString())?.let { onPicked(it) }
            }
            .show()
    }

    private fun parse(text: String?): Int? {
        return try {
            val t = text?.trim()?.removePrefix("#") ?: return null
            if (t.length != 6) null else Color.parseColor("#$t") and 0xFFFFFF
        } catch (e: Throwable) {
            null
        }
    }
}
