package app.touchbot

import kotlin.math.hypot

/** One sampled touch point: screen position plus time offset from touch-down. */
data class Pt(val x: Float, val y: Float, val t: Long)

object Geometry {
    fun pathLength(pts: List<Pt>): Float {
        var len = 0f
        for (i in 1 until pts.size) {
            len += hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
        }
        return len
    }

    /** Reduces [pts] to at most [max] points, always keeping the first and last. */
    fun downsample(pts: List<Pt>, max: Int): List<Pt> {
        if (pts.size <= max || max < 2) return pts
        val out = ArrayList<Pt>(max)
        val step = (pts.size - 1).toDouble() / (max - 1)
        for (i in 0 until max) {
            out.add(pts[Math.round(i * step).toInt().coerceIn(0, pts.size - 1)])
        }
        return out
    }

    fun clamp(v: Float, size: Int): Float = v.coerceIn(0f, (size - 1).coerceAtLeast(0).toFloat())
}
