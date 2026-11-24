package com.ercan.efos

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView

/** Windows sürümündeki çizimlerin Android karşılıkları (aynı renkler, aynı ölçüler). */
object UI {
    val bg get() = Ortak.renk("arkaPlan")
    val card get() = Ortak.renk("kart")
    val border get() = Ortak.renk("kenar")
    val text get() = Ortak.renk("yazi")
    val sub get() = Ortak.renk("soluk")
    val accent get() = Ortak.renk("vurgu")
    val accentDown get() = Ortak.renk("vurguBasili")
    val disabled get() = Ortak.renk("pasif")
    val danger get() = Ortak.renk("tehlike")
    val dangerDown get() = Ortak.renk("tehlikeUzerinde")
    val green get() = Ortak.renk("yesil")
    val red get() = Ortak.renk("kirmizi")

    val semibold: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun round(color: Int, radiusPx: Float, stroke: Int = 0, strokePx: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusPx
        if (strokePx > 0) setStroke(strokePx, stroke)
    }

    /** Dolgulu buton (birincil) ya da şeffaf buton (hayalet). */
    fun styleButton(v: TextView, fill: Int, pressed: Int, radiusPx: Float) {
        v.background = RippleDrawable(ColorStateList.valueOf(pressed), round(fill, radiusPx), round(0xFFFFFFFF.toInt(), radiusPx))
    }

    fun button(ctx: Context, label: String, primary: Boolean, dp: Float): TextView = TextView(ctx).apply {
        text = label
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        if (primary) {
            setTextColor(0xFFFFFFFF.toInt()); textSize = 16f; typeface = semibold
            styleButton(this, accent, accentDown, 9 * dp)
        } else {
            setTextColor(0xFF454C59.toInt()); textSize = 14f
            styleButton(this, 0x00000000, 0xFFE2E5EB.toInt(), 9 * dp)
        }
    }
}

/** Logo: mavi degrade yuvarlak kare + beyaz indirme oku (Logo.Draw ile aynı geometri). */
class LogoView(ctx: Context) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val arrow = Path()
    private var sizeF = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sizeF = minOf(w, h).toFloat()
        val m = sizeF * 0.03f
        rect.set(m, m, sizeF - m, sizeF - m)
        fill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, UI_LOGO_TOP, UI_LOGO_BOTTOM, Shader.TileMode.CLAMP)
        pen.strokeWidth = maxOf(1.6f, sizeF * 0.1f)
        arrow.reset()
        arrow.moveTo(sizeF * 0.31f, sizeF * 0.43f)
        arrow.lineTo(sizeF * 0.5f, sizeF * 0.62f)
        arrow.lineTo(sizeF * 0.69f, sizeF * 0.43f)
    }

    override fun onDraw(c: Canvas) {
        if (sizeF <= 0f) return
        c.drawRoundRect(rect, sizeF * 0.24f, sizeF * 0.24f, fill)
        c.drawLine(sizeF * 0.5f, sizeF * 0.21f, sizeF * 0.5f, sizeF * 0.6f, pen)
        c.drawPath(arrow, pen)
        c.drawLine(sizeF * 0.27f, sizeF * 0.79f, sizeF * 0.73f, sizeF * 0.79f, pen)
    }

    private companion object {
        val UI_LOGO_TOP get() = Ortak.renk("logoUst")
        val UI_LOGO_BOTTOM get() = Ortak.renk("logoAlt")
    }
}

/** İnce, yuvarlak uçlu ilerleme çubuğu (ProgressLine ile aynı). */
class ProgressLine(ctx: Context) : View(ctx) {
    var value = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        p.color = UI.border
        c.drawRoundRect(0f, 0f, width.toFloat(), h, h / 2, h / 2, p)
        if (value > 0f) {
            p.color = UI.accent
            c.drawRoundRect(0f, 0f, maxOf(h, width * value), h, h / 2, h / 2, p)
        }
    }
}
