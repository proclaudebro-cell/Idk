package app.touchbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {
    @Test
    fun pathLengthOfStraightLine() {
        val pts = listOf(Pt(0f, 0f, 0), Pt(3f, 4f, 10), Pt(6f, 8f, 20))
        assertEquals(10f, Geometry.pathLength(pts), 0.001f)
    }

    @Test
    fun singlePointHasZeroLength() {
        assertEquals(0f, Geometry.pathLength(listOf(Pt(5f, 5f, 0))), 0f)
    }

    @Test
    fun downsampleKeepsEndpointsAndOrder() {
        val pts = (0 until 200).map { Pt(it.toFloat(), 0f, it.toLong()) }
        val out = Geometry.downsample(pts, 40)
        assertEquals(40, out.size)
        assertEquals(pts.first(), out.first())
        assertEquals(pts.last(), out.last())
        assertTrue(out.zipWithNext().all { (a, b) -> a.t <= b.t })
    }

    @Test
    fun downsampleLeavesShortListsAlone() {
        val pts = listOf(Pt(0f, 0f, 0), Pt(1f, 1f, 1))
        assertEquals(pts, Geometry.downsample(pts, 40))
    }

    @Test
    fun clampStaysInsideScreen() {
        assertEquals(0f, Geometry.clamp(-5f, 1080), 0f)
        assertEquals(1079f, Geometry.clamp(5000f, 1080), 0f)
        assertEquals(500f, Geometry.clamp(500f, 1080), 0f)
    }
}
