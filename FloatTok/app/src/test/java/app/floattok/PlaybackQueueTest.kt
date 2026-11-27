package app.floattok

import app.floattok.playback.PlaybackQueue
import app.floattok.playback.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class PlaybackQueueTest {

    private fun q(n: Int, start: Int = 0) = PlaybackQueue(random = Random(7)).apply { setItems((1L..n.toLong()).toList(), start) }

    @Test fun autoplayOnAdvancesOnEnd() {
        val q = q(3)
        assertEquals(1, q.advance(natural = true, autoplay = true, repeat = RepeatMode.OFF))
        assertEquals(2, q.advance(natural = true, autoplay = true, repeat = RepeatMode.OFF))
        assertNull(q.advance(natural = true, autoplay = true, repeat = RepeatMode.OFF))
        assertEquals(2, q.currentIndex)
    }

    @Test fun autoplayOffStopsAfterCurrent() {
        val q = q(3)
        assertNull(q.advance(natural = true, autoplay = false, repeat = RepeatMode.OFF))
        assertEquals(0, q.currentIndex)
        // The Next button still works with autoplay off.
        assertEquals(1, q.advance(natural = false, autoplay = false, repeat = RepeatMode.OFF))
    }

    @Test fun repeatOneReplays() {
        val q = q(3, 1)
        assertEquals(1, q.advance(natural = true, autoplay = true, repeat = RepeatMode.ONE))
        assertEquals(1, q.advance(natural = true, autoplay = false, repeat = RepeatMode.ONE))
        assertEquals(2, q.advance(natural = false, autoplay = true, repeat = RepeatMode.ONE))
    }

    @Test fun repeatAllWraps() {
        val q = q(2, 1)
        assertEquals(0, q.advance(natural = true, autoplay = true, repeat = RepeatMode.ALL))
    }

    @Test fun manualNextWrapsAtEnd() {
        val q = q(2, 1)
        assertEquals(0, q.advance(natural = false, autoplay = true, repeat = RepeatMode.OFF))
    }

    @Test fun backStopsAtStartUnlessRepeatAll() {
        val q = q(3)
        assertEquals(0, q.back(RepeatMode.OFF))
        assertEquals(2, q.back(RepeatMode.ALL))
        assertEquals(1, q.back(RepeatMode.ALL))
    }

    @Test fun shuffleVisitsEveryItemOnceStartingWithCurrent() {
        val q = q(10, 4)
        q.setShuffle(true)
        assertEquals(4, q.currentIndex)
        val seen = mutableListOf(q.currentIndex)
        while (true) seen.add(q.advance(natural = true, autoplay = true, repeat = RepeatMode.OFF) ?: break)
        assertEquals(10, seen.size)
        assertEquals((0 until 10).toSet(), seen.toSet())
        q.setShuffle(false)
        assertEquals(seen.last(), q.currentIndex)
    }

    @Test fun editsKeepCurrentItem() {
        val q = q(5, 2) // current id 3
        q.remove(0)
        assertEquals(3L, q.currentId)
        q.move(0, 3)
        assertEquals(3L, q.currentId)
        q.insertNext(99L)
        assertEquals(99L, q.ids[q.currentIndex + 1])
        q.remove(q.currentIndex)
        assertEquals(99L, q.currentId)
    }

    @Test fun restoreOrderRejectsMismatch() {
        val q = q(3)
        q.restoreOrder(intArrayOf(2, 0, 1), 1, true)
        assertEquals(0, q.currentIndex)
        q.restoreOrder(intArrayOf(0, 0, 1), 1, true)
        assertEquals(0, q.currentIndex)
    }

    @Test fun emptyQueue() {
        val q = PlaybackQueue()
        assertNull(q.advance(natural = true, autoplay = true, repeat = RepeatMode.ALL))
        assertNull(q.back(RepeatMode.ALL))
        assertEquals(-1, q.currentIndex)
    }
}
