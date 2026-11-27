package app.floattok.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import app.floattok.ui.Ui

/** Thin seek bar: tap or drag anywhere on it to seek. */
@SuppressLint("ViewConstructor")
class SeekStrip(context: Context, private val onSeek: (Float) -> Unit) : View(context) {
    private val d = context.resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0x55, 0xFF, 0xFF, 0xFF) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rect = RectF()
    private var fraction = 0f
    private var dragging = false
    private var dragFraction = 0f
    var onDragChanged: ((Boolean) -> Unit)? = null

    fun setProgress(f: Float) {
        if (dragging) return
        val nf = f.coerceIn(0f, 1f)
        if (nf != fraction) { fraction = nf; invalidate() }
    }

    override fun onDraw(c: Canvas) {
        val h = 3 * d
        val cy = height / 2f
        val pad = 6 * d
        val w = width - 2 * pad
        val f = if (dragging) dragFraction else fraction
        rect.set(pad, cy - h / 2, pad + w, cy + h / 2)
        c.drawRoundRect(rect, h, h, track)
        rect.set(pad, cy - h / 2, pad + w * f, cy + h / 2)
        c.drawRoundRect(rect, h, h, fill)
        c.drawCircle(pad + w * f, cy, (if (dragging) 7 else 5) * d, thumb)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pad = 6 * d
        val f = ((e.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true; dragFraction = f; onDragChanged?.invoke(true); invalidate()
            }
            MotionEvent.ACTION_MOVE -> { dragFraction = f; invalidate() }
            MotionEvent.ACTION_UP -> {
                dragging = false; fraction = f; onDragChanged?.invoke(false); invalidate(); onSeek(f)
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; onDragChanged?.invoke(false); invalidate() }
        }
        return true
    }
}

/** Two short diagonal strokes in the bottom-right corner hinting that the corner can be dragged. */
class GripView(context: Context) : View(context) {
    private val d = context.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(0xB0, 0xFF, 0xFF, 0xFF); strokeWidth = 2 * d; strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val m = 5 * d
        c.drawLine(w - m, h - m - 10 * d, w - m - 10 * d, h - m, p)
        c.drawLine(w - m, h - m - 5 * d, w - m - 5 * d, h - m, p)
    }
}

/** The small retractable tab shown at the screen edge while the player is docked. */
class TabView(context: Context) : View(context) {
    private val d = context.resources.displayMetrics.density
    var side = DockSide.RIGHT
        set(v) { field = v; invalidate() }
    var playing = false
        set(v) { field = v; invalidate() }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0xEE, 0x16, 0x16, 0x1A) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1 * d; color = Color.argb(0x60, 0x25, 0xF4, 0xEE)
    }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT }
    private val chevron = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2 * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()

    /** Visible pill width; the view itself is wider so it is easy to grab. */
    val pillWidth get() = 14 * d

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val pw = pillWidth; val r = pw
        // Pill hugging the screen edge, rounded on the inner side only.
        if (side == DockSide.RIGHT) rect.set(w - pw, 0f, w + r, h) else rect.set(-r, 0f, pw, h)
        c.drawRoundRect(rect, r, r, bg)
        c.drawRoundRect(rect, r, r, stroke)
        val cx = if (side == DockSide.RIGHT) w - pw / 2 else pw / 2
        val cy = h / 2
        // Chevron pointing back into the screen.
        path.reset()
        val a = 4 * d
        if (side == DockSide.RIGHT) { path.moveTo(cx + a / 2, cy - a); path.lineTo(cx - a / 2, cy); path.lineTo(cx + a / 2, cy + a) }
        else { path.moveTo(cx - a / 2, cy - a); path.lineTo(cx + a / 2, cy); path.lineTo(cx - a / 2, cy + a) }
        c.drawPath(path, chevron)
        // Playing indicator dot.
        accent.alpha = if (playing) 0xFF else 0x50
        c.drawCircle(cx, cy - h * 0.32f, 2.5f * d, accent)
        c.drawCircle(cx, cy + h * 0.32f, 2.5f * d, accent)
    }
}
