package com.ercan.otomakro

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

const val C_BG = 0xFFF2F3F8.toInt()
const val C_TEXT = 0xFF1F2330.toInt()
const val C_MUTED = 0xFF6B7080.toInt()
const val C_ACCENT = 0xFF3949AB.toInt()
const val C_TRIGGER = 0xFFE65100.toInt()
const val C_COND = 0xFF2E7D32.toInt()
const val C_ACTION = 0xFF1565C0.toInt()
const val C_DANGER = 0xFFC62828.toInt()

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.label(s: String, size: Float = 15f, bold: Boolean = false, color: Int = C_TEXT): TextView =
    TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

fun Context.rounded(fill: Int, stroke: Int = 0, radius: Int = 12): GradientDrawable =
    GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(fill)
        if (stroke != 0) setStroke(dp(1), stroke)
    }

fun Context.card(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(14), dp(12), dp(14), dp(12))
    background = rounded(Color.WHITE, 0xFFDDE0EA.toInt())
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, 0, 0, dp(10)) }
}

fun Context.pill(s: String, color: Int, onClick: () -> Unit): Button = Button(this).apply {
    text = s
    setAllCaps(false)
    textSize = 14f
    setTextColor(color)
    background = rounded(Color.TRANSPARENT, color, 18)
    minHeight = 0
    minimumHeight = 0
    setPadding(dp(14), dp(6), dp(14), dp(6))
    setOnClickListener { onClick() }
}

fun Context.primary(s: String, onClick: () -> Unit): Button = Button(this).apply {
    text = s
    setAllCaps(false)
    textSize = 16f
    setTextColor(Color.WHITE)
    background = rounded(C_ACCENT, 0, 24)
    gravity = Gravity.CENTER
    setPadding(dp(16), dp(12), dp(16), dp(12))
    setOnClickListener { onClick() }
}

fun Context.row(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun wrap() = LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
)

fun weight1() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

fun matchWrap() = LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
)
