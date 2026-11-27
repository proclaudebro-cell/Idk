package app.floattok

import app.floattok.overlay.Box
import app.floattok.overlay.DockSide
import app.floattok.overlay.Edge
import app.floattok.overlay.WindowGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WindowGeometryTest {
    // Pixel 9a-like portrait screen: 1080 x 2424 px, density 2.625.
    private val g = WindowGeometry(1080, 2424, 0, 120, 0, 60, 2.625f)
    private val aspect = 9f / 16f

    private fun ratio(b: Box) = b.w.toFloat() / b.h

    @Test fun sizeRangeIsTenToHundredPercentOfWidth() {
        assertEquals(108, g.minW) // 10% of 1080 beats 40dp (105px)
        assertEquals(53, g.minH)
        val (minW, _) = g.clampSize(10, 10, null)
        assertEquals(108, minW)
        val (w, h) = g.clampSize(5000, 5000, null)
        assertEquals(1080, w)
        assertEquals(2424 - 120 - 60, h)
    }

    @Test fun clampKeepsAspect() {
        val (w, h) = g.clampSize(5000, 0, aspect)
        assertTrue(w <= 1080 && h <= g.maxH)
        assertTrue(abs(w.toFloat() / h - aspect) < 0.01f)
        val (sw, sh) = g.clampSize(1, 1, aspect)
        assertEquals(108, sw)
        assertEquals(192, sh)
    }

    @Test fun cornerResizeAnchorsOppositeCorner() {
        val start = Box(300, 600, 400, 711)
        val b = g.resize(start, Edge.RIGHT or Edge.BOTTOM, 200, 0, aspect)
        assertEquals(300, b.x)
        assertEquals(600, b.y)
        assertEquals(600, b.w)
        assertTrue(abs(ratio(b) - aspect) < 0.01f)
        val l = g.resize(start, Edge.LEFT or Edge.TOP, -100, -100, null)
        assertEquals(start.right, l.right)
        assertEquals(start.bottom, l.bottom)
        assertEquals(500, l.w)
        assertEquals(811, l.h)
    }

    @Test fun freeEdgeResizeChangesOneDimension() {
        val start = Box(100, 500, 400, 400)
        val b = g.resize(start, Edge.RIGHT, 300, 999, null)
        assertEquals(700, b.w)
        assertEquals(400, b.h)
    }

    @Test fun resizeCannotGoBelowMinimumOrOffScreen() {
        val start = Box(900, 2000, 170, 300)
        val small = g.resize(start, Edge.LEFT, 5000, 0, null)
        assertEquals(g.minW, small.w)
        val big = g.resize(start, Edge.RIGHT or Edge.BOTTOM, 5000, 5000, null)
        assertTrue(big.right <= 1080 && big.bottom <= 2424 && big.x >= 0 && big.y >= 0)
    }

    @Test fun pinchScalesAroundFingers() {
        val start = Box(200, 400, 400, 711)
        val b = g.pinch(start, 1.5f, 1.5f, 400f, 755f, 400f, 755f, aspect)
        assertEquals(600, b.w)
        // Focus point stays at the same relative position.
        assertTrue(abs((400f - b.x) / b.w - 0.5f) < 0.01f)
        val tiny = g.pinch(start, 0.01f, 0.01f, 400f, 755f, 400f, 755f, aspect)
        assertEquals(g.minW, tiny.w)
    }

    @Test fun maximizeFillsInsideBarsWithAspect() {
        val m = g.maximized(aspect, 0)
        assertEquals(1080, m.w)
        assertTrue(m.y >= 120 && m.bottom <= 2424 - 60)
        val land = WindowGeometry(2424, 1080, 0, 0, 0, 0, 2.625f).maximized(aspect, 0)
        assertEquals(1080, land.h)
        assertTrue(abs(ratio(land) - aspect) < 0.01f)
        val free = g.maximized(null, 0)
        assertEquals(1080, free.w)
        assertEquals(2424 - 180, free.h)
    }

    @Test fun dockDecisions() {
        val b = Box(-200, 800, 400, 711) // half off the left edge
        assertEquals(DockSide.LEFT, g.dockSideFor(b, 0f))
        val r = Box(880, 800, 400, 711)
        assertEquals(DockSide.RIGHT, g.dockSideFor(r, 0f))
        val middle = Box(300, 800, 400, 711)
        assertEquals(DockSide.NONE, g.dockSideFor(middle, 0f))
        // Fast fling from near the edge docks.
        assertEquals(DockSide.RIGHT, g.dockSideFor(Box(600, 800, 400, 711), 6000f))
        // Slow drift does not.
        assertEquals(DockSide.NONE, g.dockSideFor(Box(600, 800, 400, 711), 500f))
    }

    @Test fun dragClampAllowsHangingOffSides() {
        val b = g.clampForDrag(Box(-1000, -50, 400, 711))
        assertEquals(-320, b.x)
        assertEquals(0, b.y)
    }

    @Test fun rotationKeepsRelativePlace() {
        val land = WindowGeometry(2424, 1080, 120, 0, 60, 0, 2.625f)
        val b = Box(600, 1500, 400, 711)
        val a = land.adaptFrom(b, g, aspect)
        assertTrue(a.right <= 2424 && a.bottom <= 1080 && a.x >= 0 && a.y >= 0)
        assertTrue(abs(ratio(a) - aspect) < 0.02f)
    }
}
