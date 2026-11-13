package app.touchbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random

class GameBrainTest {
    private val w = 96
    private val h = 144

    /** A fake game screen: a coloured ball at ([bx], [by]) on a dark background, plus noise. */
    private fun screen(bx: Int, by: Int, color: Int = 0xFFFFFF, seed: Long = 1): IntArray {
        val rnd = Random(seed)
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val inBall = (x - bx) * (x - bx) + (y - by) * (y - by) < 14 * 14
            val base = if (inBall) color else 0x202830
            val n = rnd.nextInt(7) - 3
            val r = (((base shr 16) and 0xFF) + n).coerceIn(0, 255)
            val g = (((base shr 8) and 0xFF) + n).coerceIn(0, 255)
            val b = ((base and 0xFF) + n).coerceIn(0, 255)
            px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return px
    }

    private fun feat(bx: Int, by: Int, color: Int = 0xFFFFFF, seed: Long = 1) =
        Features.fromPixels(screen(bx, by, color, seed), w, h)

    private fun tapAt(x: Float, y: Float) = Action("tap", x = x, y = y)

    @Test
    fun identicalFramesHaveZeroDistance() {
        assertEquals(Features.SIZE, feat(20, 20).size)
        assertEquals(0f, Features.distance(feat(20, 20), feat(20, 20)), 0f)
    }

    @Test
    fun noiseIsCloserThanARealChange() {
        val a = feat(20, 20, seed = 1)
        val noisy = feat(20, 20, seed = 2)
        val moved = feat(70, 110, seed = 1)
        assertTrue(Features.distance(a, noisy) < 0.01f)
        assertTrue(Features.distance(a, moved) > Features.distance(a, noisy) * 3)
    }

    @Test
    fun maskHidesTheControlPanel() {
        val mask = floatArrayOf(0f, 0f, 0.5f, 0.4f)
        val a = Features.fromPixels(screen(20, 20), w, h, mask)
        val b = Features.fromPixels(screen(30, 30, color = 0xFF0000), w, h, mask)
        assertTrue(Features.distance(a, b) < 0.005f)
    }

    @Test
    fun policyCopiesTheMoveForTheMatchingScreen() {
        val samples = listOf(
            Sample(feat(20, 20), tapAt(100f, 200f), 1080, 2400),
            Sample(feat(70, 110), tapAt(900f, 2000f), 1080, 2400),
            Sample(feat(48, 72, color = 0x00FF00), null, 0, 0),
        )
        val policy = Policy(samples, 0.03f)

        val left = policy.decide(feat(21, 20, seed = 5))
        assertTrue(left is Decision.Act)
        assertEquals(100f, (left as Decision.Act).sample.action!!.x, 0f)

        val right = policy.decide(feat(70, 109, seed = 6))
        assertEquals(900f, (right as Decision.Act).sample.action!!.x, 0f)

        assertTrue(policy.decide(feat(48, 72, color = 0x00FF00, seed = 7)) is Decision.Idle)
    }

    @Test
    fun unfamiliarScreenIsReportedAsUnknown() {
        val policy = Policy(listOf(Sample(feat(20, 20), tapAt(1f, 1f), 100, 100)), 0.03f)
        val bright = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        assertTrue(policy.decide(Features.fromPixels(bright, w, h)) is Decision.Unknown)
        assertTrue(Policy(emptyList(), 0.03f).decide(feat(1, 1)) is Decision.Unknown)
    }

    @Test
    fun idleMajorityWinsOverASingleStrayTap() {
        val samples = (0 until 4).map { Sample(feat(20, 20, seed = it.toLong()), null, 0, 0) } +
            Sample(feat(20, 20, seed = 9), tapAt(5f, 5f), 100, 100)
        assertTrue(Policy(samples, 0.03f).decide(feat(20, 20, seed = 42)) is Decision.Idle)
    }

    @Test
    fun trainerScoresAConsistentPlayerHighly() {
        // The player always taps where the ball is; nothing on screen -> waits.
        val samples = ArrayList<Sample>()
        val spots = listOf(20 to 20, 70 to 20, 20 to 110, 70 to 110)
        for (round in 0 until 6) {
            for ((x, y) in spots) {
                samples.add(Sample(
                    feat(x, y, seed = round * 10L + x), tapAt(x * 1080f / w, y * 2400f / h), 1080, 2400,
                ))
            }
            samples.add(Sample(feat(-100, -100, seed = round + 77L), null, 0, 0))
        }
        val r = Trainer.calibrate(samples)
        assertEquals(24, r.moves)
        assertEquals(30, r.moments)
        assertTrue("agreement was ${r.agreement}", r.agreement >= 90)
        assertTrue(r.threshold in 0.03f..0.2f)
    }

    @Test
    fun mergeDropsOldIdleMomentsFirst() {
        val f = ByteArray(Features.SIZE)
        val old = (0 until Trainer.MAX_SAMPLES).map { Sample(f, if (it % 2 == 0) tapAt(1f, 1f) else null, 10, 10) }
        val fresh = (0 until 100).map { Sample(f, tapAt(2f, 2f), 10, 10) }
        val merged = Trainer.merge(old, fresh)
        assertEquals(Trainer.MAX_SAMPLES, merged.size)
        assertEquals(Trainer.MAX_SAMPLES / 2 + 100, merged.count { it.action != null })
        assertEquals(2f, merged.last().action!!.x, 0f)
    }

    @Test
    fun samplesSurviveSaveAndLoad() {
        val gesture = Action("gesture", duration = 300, points = listOf(Pt(1f, 2f, 0), Pt(30f, 40f, 300)))
        val samples = listOf(
            Sample(feat(20, 20), tapAt(11f, 22f), 1080, 2400),
            Sample(feat(70, 110), null, 0, 0),
            Sample(feat(48, 72), gesture, 720, 1600),
        )
        val out = ByteArrayOutputStream()
        SampleCodec.write(out, samples)
        val back = SampleCodec.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(3, back.size)
        assertTrue(back[0].feat.contentEquals(samples[0].feat))
        assertEquals(tapAt(11f, 22f), back[0].action)
        assertNull(back[1].action)
        assertEquals(gesture, back[2].action)
        assertEquals(720, back[2].w)
        assertTrue(SampleCodec.read(ByteArrayInputStream(byteArrayOf(1, 2, 3))).isEmpty())
    }

    @Test
    fun styleSummaryMentionsWhereThePlayerTaps() {
        val f = ByteArray(Features.SIZE)
        val samples = (0 until 9).map { Sample(f, tapAt(540f, 2200f), 1080, 2400) } +
            Sample(f, Action("gesture", points = listOf(Pt(100f, 100f, 0), Pt(900f, 100f, 200))), 1080, 2400)
        val s = Trainer.describeStyle(samples)
        assertTrue(s, s.contains("9 taps"))
        assertTrue(s, s.contains("(50%, 90%) 90% of moves"))
    }

    @Test
    fun gameStoreKeepsProfilesAndSamples() {
        val dir = File(System.getProperty("java.io.tmpdir"), "touchbot-test-${System.nanoTime()}")
        try {
            val store = GameStore(dir)
            assertNull(store.load("com.example.game"))
            store.save(GameProfile("com.example.game", "Example", guide = "Tap to jump.", moves = 3, agreement = 80))
            store.saveSamples("com.example.game", listOf(Sample(feat(20, 20), tapAt(1f, 2f), 10, 20)))
            val p = store.load("com.example.game")
            assertNotNull(p)
            assertEquals("Tap to jump.", p!!.guide)
            assertEquals(80, p.agreement)
            assertEquals(1, store.list().size)
            assertEquals(1, store.loadSamples("com.example.game").size)
            store.delete("com.example.game")
            assertTrue(store.list().isEmpty())
            assertTrue(store.loadSamples("com.example.game").isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
