package app.floattok.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import app.floattok.data.Prefs
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Small tab at the left or right screen edge shown while the player is docked.
 * Tap it, or pull it towards the middle of the screen, to bring the player back; drag it up/down to move it.
 */
@SuppressLint("ClickableViewAccessibility")
class DockTab(
    private val ctx: Context,
    private val wm: WindowManager,
    private val geometry: () -> WindowGeometry,
    private val prefs: Prefs,
    private val onRestore: () -> Unit,
) {
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    private val view = TabView(ctx).apply { contentDescription = "Show FloatTok player" }
    private val lp = WindowManager.LayoutParams(
        dp(30), dp(88),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "FloatTok tab"
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        if (Build.VERSION.SDK_INT >= 34) setCanPlayMoveAnimation(false)
    }

    var shown = false
        private set
    private var side = DockSide.RIGHT

    init {
        view.setOnTouchListener { _, e -> onTouch(e) }
        // Ask the system not to treat touches on the tab as the back gesture.
        view.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            v.systemGestureExclusionRects = listOf(Rect(0, 0, r - l, b - t))
        }
    }

    fun show(side: Int, centerY: Int) {
        this.side = side
        view.side = side
        val g = geometry()
        lp.x = if (side == DockSide.LEFT) 0 else g.screenW - lp.width
        lp.y = g.tabY(centerY, lp.height)
        prefs.dockTabFraction = (lp.y + lp.height / 2f) / g.screenH
        if (!shown) {
            wm.addView(view, lp)
            shown = true
            view.translationX = if (side == DockSide.LEFT) -lp.width.toFloat() else lp.width.toFloat()
            view.animate().translationX(0f).setDuration(180).setStartDelay(80).start()
        } else {
            wm.updateViewLayout(view, lp)
        }
    }

    fun hide() {
        if (!shown) return
        view.animate().cancel()
        try { wm.removeViewImmediate(view) } catch (_: Exception) {}
        shown = false
    }

    fun centerY(): Int? = if (shown) lp.y + lp.height / 2 else null

    fun setPlaying(playing: Boolean) { view.playing = playing }

    private var downX = 0f
    private var downY = 0f
    private var startY = 0
    private var moved = false
    private var restored = false

    private fun onTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; startY = lp.y; moved = false; restored = false
                view.animate().scaleX(1.15f).scaleY(1.05f).setDuration(80).start()
            }
            MotionEvent.ACTION_MOVE -> {
                if (restored) return true
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                val inward = if (side == DockSide.RIGHT) -dx else dx
                if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                if (inward > dp(32) && inward > abs(dy)) {
                    restored = true
                    onRestore()
                } else if (moved) {
                    val g = geometry()
                    lp.y = g.tabY(startY + dy.roundToInt() + lp.height / 2, lp.height)
                    if (shown) wm.updateViewLayout(view, lp)
                }
            }
            MotionEvent.ACTION_UP -> {
                view.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                if (!restored) {
                    if (!moved) onRestore()
                    else prefs.dockTabFraction = (lp.y + lp.height / 2f) / geometry().screenH
                }
            }
            MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
        }
        return true
    }
}
