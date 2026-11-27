package app.floattok.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Colours and small view factories shared by the app screens and the floating player. */
object Ui {
    val BG = Color.rgb(0x0B, 0x0B, 0x0D)
    val SURFACE = Color.rgb(0x16, 0x16, 0x1A)
    val SURFACE_2 = Color.rgb(0x20, 0x20, 0x26)
    val STROKE = Color.rgb(0x2C, 0x2C, 0x34)
    val ACCENT = Color.rgb(0x25, 0xF4, 0xEE)
    val ACCENT_DIM = Color.argb(0x33, 0x25, 0xF4, 0xEE)
    val TEXT = Color.rgb(0xF2, 0xF2, 0xF5)
    val TEXT_2 = Color.rgb(0xA0, 0xA0, 0xAA)
    val TEXT_3 = Color.rgb(0x6C, 0x6C, 0x76)
    val DANGER = Color.rgb(0xFF, 0x5A, 0x6E)

    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density).roundToInt()
    fun dp(ctx: Context, v: Int): Int = dp(ctx, v.toFloat())

    fun rounded(color: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

    fun ripple(content: android.graphics.drawable.Drawable?, radiusPx: Float = 0f): RippleDrawable {
        val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = radiusPx }
        return RippleDrawable(ColorStateList.valueOf(Color.argb(0x40, 0xFF, 0xFF, 0xFF)), content, mask)
    }

    fun text(ctx: Context, s: CharSequence = "", sizeSp: Float = 14f, color: Int = TEXT, bold: Boolean = false) =
        TextView(ctx).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

    fun icon(ctx: Context, res: Int, tint: Int = TEXT, sizeDp: Int = 24, padDp: Int = 8): ImageView =
        ImageView(ctx).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(tint)
            val p = dp(ctx, padDp)
            setPadding(p, p, p, p)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = ripple(null, dp(ctx, 24).toFloat())
            layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp + 2 * padDp), dp(ctx, sizeDp + 2 * padDp))
        }

    /** Pill-shaped button. */
    fun button(ctx: Context, label: String, primary: Boolean = false, onClick: (View) -> Unit): TextView =
        text(ctx, label, 14f, if (primary) Color.BLACK else TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            val h = dp(ctx, 16); val v = dp(ctx, 10)
            setPadding(h, v, h, v)
            val bg = if (primary) rounded(ACCENT, dp(ctx, 22).toFloat()) else rounded(SURFACE_2, dp(ctx, 22).toFloat(), STROKE, dp(ctx, 1))
            background = ripple(bg, dp(ctx, 22).toFloat())
            isClickable = true
            setOnClickListener(onClick)
        }

    /** Toggle chip showing on/off state in the accent colour. */
    fun chip(ctx: Context, label: String, on: Boolean, onClick: (View) -> Unit): TextView =
        text(ctx, label, 13f, if (on) Color.BLACK else TEXT_2, bold = on).apply {
            gravity = Gravity.CENTER
            val h = dp(ctx, 12); val v = dp(ctx, 7)
            setPadding(h, v, h, v)
            styleChip(this, on)
            isClickable = true
            setOnClickListener(onClick)
        }

    fun styleChip(tv: TextView, on: Boolean) {
        val ctx = tv.context
        tv.setTextColor(if (on) Color.BLACK else TEXT_2)
        tv.typeface = Typeface.create(Typeface.DEFAULT, if (on) Typeface.BOLD else Typeface.NORMAL)
        val bg = if (on) rounded(ACCENT, dp(ctx, 16).toFloat()) else rounded(SURFACE_2, dp(ctx, 16).toFloat(), STROKE, dp(ctx, 1))
        tv.background = ripple(bg, dp(ctx, 16).toFloat())
    }

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(SURFACE, dp(ctx, 16).toFloat(), STROKE, dp(ctx, 1))
        val p = dp(ctx, 16)
        setPadding(p, p, p, p)
    }

    fun lpMatchWrap(ctx: Context, topDp: Int = 0, bottomDp: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(ctx, topDp); bottomMargin = dp(ctx, bottomDp)
        }

    fun formatTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
}
