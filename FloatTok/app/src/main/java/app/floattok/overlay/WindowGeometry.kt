package app.floattok.overlay

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class Box(val x: Int, val y: Int, val w: Int, val h: Int) {
    val right get() = x + w
    val bottom get() = y + h
    val cx get() = x + w / 2
    val cy get() = y + h / 2
}

object Edge {
    const val LEFT = 1
    const val TOP = 2
    const val RIGHT = 4
    const val BOTTOM = 8
}

object DockSide {
    const val NONE = -1
    const val LEFT = 0
    const val RIGHT = 1
}

/**
 * All floating-window maths in screen pixels. No Android types so it is unit tested.
 * Width can go from ~10% of the screen width up to the full width; height up to the usable height.
 */
class WindowGeometry(
    val screenW: Int,
    val screenH: Int,
    val insetLeft: Int,
    val insetTop: Int,
    val insetRight: Int,
    val insetBottom: Int,
    val density: Float,
) {
    val minW: Int = max((screenW * 0.10f).roundToInt(), (40 * density).roundToInt()).coerceAtMost(screenW)
    /** Low enough that a 16:9 video can still shrink to ~10% of the screen width. */
    val minH: Int = (20 * density).roundToInt()
    val maxW: Int = screenW
    val maxH: Int = (screenH - insetTop - insetBottom).coerceAtLeast(minH)

    /** Clamp a size to the allowed range, keeping [aspect] (w/h) when given. */
    fun clampSize(w: Int, h: Int, aspect: Float?): Pair<Int, Int> {
        if (aspect == null || aspect <= 0f) {
            return Pair(w.coerceIn(minW, maxW), h.coerceIn(minH, maxH))
        }
        var nw = w.toFloat()
        var nh = nw / aspect
        if (nw > maxW) { nw = maxW.toFloat(); nh = nw / aspect }
        if (nh > maxH) { nh = maxH.toFloat(); nw = nh * aspect }
        if (nw < minW) { nw = minW.toFloat(); nh = nw / aspect }
        if (nh < minH) { nh = minH.toFloat(); nw = nh * aspect }
        // Extreme aspect ratios can't satisfy every bound; the max bounds win.
        nw = min(nw, maxW.toFloat())
        nh = min(nh, maxH.toFloat())
        return Pair(nw.roundToInt(), nh.roundToInt())
    }

    /** Fully on screen. */
    fun clampOnScreen(b: Box): Box {
        val x = b.x.coerceIn(0, (screenW - b.w).coerceAtLeast(0))
        val y = b.y.coerceIn(0, (screenH - b.h).coerceAtLeast(0))
        return b.copy(x = x, y = y)
    }

    /** While dragging the window may hang off the left/right edge (that is how docking starts). */
    fun clampForDrag(b: Box): Box {
        val x = b.x.coerceIn(-(b.w * 0.8f).roundToInt(), screenW - (b.w * 0.2f).roundToInt())
        val y = b.y.coerceIn(0, (screenH - b.h).coerceAtLeast(0))
        return b.copy(x = x, y = y)
    }

    fun resize(start: Box, edges: Int, dx: Int, dy: Int, aspect: Float?): Box {
        var l = start.x
        var t = start.y
        var r = start.right
        var b = start.bottom
        if (edges and Edge.LEFT != 0) l += dx
        if (edges and Edge.RIGHT != 0) r += dx
        if (edges and Edge.TOP != 0) t += dy
        if (edges and Edge.BOTTOM != 0) b += dy
        var w = (r - l).toFloat()
        var h = (b - t).toFloat()
        val horiz = edges and (Edge.LEFT or Edge.RIGHT) != 0
        val vert = edges and (Edge.TOP or Edge.BOTTOM) != 0
        if (aspect != null && aspect > 0f) {
            if (horiz && !vert) h = w / aspect
            else if (vert && !horiz) w = h * aspect
            else {
                val rw = abs(w / start.w - 1f)
                val rh = abs(h / start.h - 1f)
                if (rw >= rh) h = w / aspect else w = h * aspect
            }
        }
        val (cw, ch) = clampSize(w.roundToInt(), h.roundToInt(), aspect)
        val x = when {
            edges and Edge.LEFT != 0 -> start.right - cw
            edges and Edge.RIGHT != 0 -> start.x
            else -> start.cx - cw / 2
        }
        val y = when {
            edges and Edge.TOP != 0 -> start.bottom - ch
            edges and Edge.BOTTOM != 0 -> start.y
            else -> start.cy - ch / 2
        }
        return clampOnScreen(Box(x, y, cw, ch))
    }

    /**
     * Pinch: scale from the gesture's start box. [sx]/[sy] are span ratios (equal when the aspect is locked).
     * The point under the fingers at the start stays under the fingers.
     */
    fun pinch(start: Box, sx: Float, sy: Float, focusStartX: Float, focusStartY: Float,
              focusX: Float, focusY: Float, aspect: Float?): Box {
        val w0 = start.w * sx
        val h0 = if (aspect != null && aspect > 0f) w0 / aspect else start.h * sy
        val (w, h) = clampSize(w0.roundToInt(), h0.roundToInt(), aspect)
        val fx = ((focusStartX - start.x) / start.w).coerceIn(0f, 1f)
        val fy = ((focusStartY - start.y) / start.h).coerceIn(0f, 1f)
        val x = (focusX - fx * w).roundToInt()
        val y = (focusY - fy * h).roundToInt()
        return clampOnScreen(Box(x, y, w, h))
    }

    /** Nearly full screen inside the system bars, keeping [aspect] when given. */
    fun maximized(aspect: Float?, margin: Int): Box {
        val availW = (screenW - insetLeft - insetRight - 2 * margin).coerceAtLeast(minW)
        val availH = (screenH - insetTop - insetBottom - 2 * margin).coerceAtLeast(minH)
        var w = availW.toFloat()
        var h = availH.toFloat()
        if (aspect != null && aspect > 0f) {
            h = w / aspect
            if (h > availH) { h = availH.toFloat(); w = h * aspect }
        }
        val wi = w.roundToInt()
        val hi = h.roundToInt()
        return Box(insetLeft + margin + (availW - wi) / 2, insetTop + margin + (availH - hi) / 2, wi, hi)
    }

    fun defaultBox(aspect: Float): Box {
        val (w, h) = clampSize((screenW * 0.42f).roundToInt(), (screenW * 0.42f / aspect).roundToInt(), aspect)
        return clampOnScreen(Box(screenW - w - (16 * density).roundToInt(), (screenH * 0.18f).roundToInt(), w, h))
    }

    /**
     * Whether a released drag should dock, and to which side. Docks when a good part of the window is already
     * past an edge, or when the fling would carry it there.
     */
    fun dockSideFor(b: Box, velocityX: Float): Int {
        val projectedX = b.x + velocityX * 0.18f
        val offLeft = -min(b.x.toFloat(), projectedX)
        val offRight = max(b.right.toFloat(), projectedX + b.w) - screenW
        val threshold = b.w * 0.35f
        val flingOk = abs(velocityX) > 900 * density
        return when {
            -b.x > threshold -> DockSide.LEFT
            b.right - screenW > threshold -> DockSide.RIGHT
            flingOk && velocityX < 0 && offLeft > threshold -> DockSide.LEFT
            flingOk && velocityX > 0 && offRight > threshold -> DockSide.RIGHT
            else -> DockSide.NONE
        }
    }

    /** Re-fit a box from another screen size (rotation): keep its relative centre and width fraction. */
    fun adaptFrom(b: Box, old: WindowGeometry, aspect: Float?): Box {
        val wFrac = b.w.toFloat() / old.screenW
        val nw = (wFrac * screenW).roundToInt()
        val nh = if (aspect != null) (nw / aspect).roundToInt() else (b.h.toFloat() / old.screenH * screenH).roundToInt()
        val (cw, ch) = clampSize(nw, nh, aspect)
        val cxF = b.cx.toFloat() / old.screenW
        val cyF = b.cy.toFloat() / old.screenH
        return clampOnScreen(Box((cxF * screenW - cw / 2f).roundToInt(), (cyF * screenH - ch / 2f).roundToInt(), cw, ch))
    }

    fun tabY(desiredCenterY: Int, tabH: Int): Int =
        (desiredCenterY - tabH / 2).coerceIn(insetTop, (screenH - insetBottom - tabH).coerceAtLeast(insetTop))
}
