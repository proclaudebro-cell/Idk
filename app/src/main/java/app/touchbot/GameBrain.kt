package app.touchbot

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Turns a (small) screen frame into a compact fingerprint: the average colour of every
 * cell in a [GRID] x [GRID] grid. Two frames showing the same game situation end up close
 * together, which is all the imitation policy needs.
 */
object Features {
    const val GRID = 24
    const val SIZE = GRID * GRID * 3

    /**
     * [px] is ARGB, row-major, [w] x [h]. [mask] is an optional rectangle (left, top, right,
     * bottom as 0..1 fractions of the frame) that is blanked out - used to hide the floating
     * control panel, whose text changes all the time.
     */
    fun fromPixels(px: IntArray, w: Int, h: Int, mask: FloatArray? = null): ByteArray {
        val out = ByteArray(SIZE)
        if (w <= 0 || h <= 0 || px.size < w * h) return out
        for (gy in 0 until GRID) {
            val y0 = gy * h / GRID
            val y1 = maxOf(y0 + 1, (gy + 1) * h / GRID).coerceAtMost(h)
            for (gx in 0 until GRID) {
                val x0 = gx * w / GRID
                val x1 = maxOf(x0 + 1, (gx + 1) * w / GRID).coerceAtMost(w)
                val o = (gy * GRID + gx) * 3
                if (mask != null) {
                    val cx = (gx + 0.5f) / GRID
                    val cy = (gy + 0.5f) / GRID
                    if (cx >= mask[0] && cx <= mask[2] && cy >= mask[1] && cy <= mask[3]) {
                        out[o] = 127; out[o + 1] = 127; out[o + 2] = 127
                        continue
                    }
                }
                var r = 0; var g = 0; var b = 0; var n = 0
                for (y in y0 until y1) {
                    val row = y * w
                    for (x in x0 until x1) {
                        val c = px[row + x]
                        r += (c shr 16) and 0xFF
                        g += (c shr 8) and 0xFF
                        b += c and 0xFF
                        n++
                    }
                }
                if (n > 0) {
                    out[o] = (r / n).toByte()
                    out[o + 1] = (g / n).toByte()
                    out[o + 2] = (b / n).toByte()
                }
            }
        }
        return out
    }

    /** Mean absolute difference, 0 (identical) .. 1 (black vs white). */
    fun distance(a: ByteArray, b: ByteArray): Float {
        val n = minOf(a.size, b.size)
        if (n == 0) return 1f
        var sum = 0
        for (i in 0 until n) sum += abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
        return sum / (n * 255f)
    }
}

/**
 * One demonstrated moment: what the screen looked like ([feat]) and what the player did
 * right after ([action], or null for "did nothing"). [w] x [h] is the screen size the
 * action's coordinates belong to.
 */
class Sample(val feat: ByteArray, val action: Action?, val w: Int, val h: Int) {
    /** Where the touch started, as 0..1 fractions of the screen; null for idle samples. */
    fun start(): Pair<Float, Float>? {
        val a = action ?: return null
        val (x, y) = if (a.type == "gesture") {
            val p = a.points?.firstOrNull() ?: return null
            p.x to p.y
        } else a.x to a.y
        return (if (w > 0) x / w else 0f) to (if (h > 0) y / h else 0f)
    }
}

sealed class Decision {
    /** The screen is familiar and the player did nothing here. */
    object Idle : Decision()

    /** The screen is familiar and the player did [sample]'s action here. */
    class Act(val sample: Sample, val index: Int, val distance: Float) : Decision()

    /** Nothing the player demonstrated looks like this screen. */
    class Unknown(val distance: Float) : Decision()
}

/** Nearest-neighbour imitation: find the demonstrated moments most like now and copy them. */
class Policy(private val samples: List<Sample>, private val threshold: Float) {
    val size: Int get() = samples.size

    fun decide(feat: ByteArray, skip: Int = -1): Decision {
        val bestIdx = IntArray(K) { -1 }
        val bestDist = FloatArray(K) { Float.MAX_VALUE }
        for (i in samples.indices) {
            if (i == skip) continue
            val d = Features.distance(feat, samples[i].feat)
            if (d >= bestDist[K - 1]) continue
            var j = K - 1
            while (j > 0 && bestDist[j - 1] > d) {
                bestDist[j] = bestDist[j - 1]; bestIdx[j] = bestIdx[j - 1]; j--
            }
            bestDist[j] = d; bestIdx[j] = i
        }
        if (bestIdx[0] < 0) return Decision.Unknown(1f)
        val best = bestDist[0]
        if (best > threshold) return Decision.Unknown(best)

        // Only neighbours about as close as the best one get a vote.
        val cutoff = minOf(threshold, maxOf(best * 1.6f, best + 0.01f))
        var actWeight = 0f; var idleWeight = 0f
        var actIdx = -1; var actDist = 0f
        for (j in 0 until K) {
            val i = bestIdx[j]
            if (i < 0 || bestDist[j] > cutoff) break
            val wgt = 1f / (bestDist[j] + 0.005f)
            if (samples[i].action != null) {
                actWeight += wgt
                if (actIdx < 0) { actIdx = i; actDist = bestDist[j] }
            } else idleWeight += wgt
        }
        return if (actIdx >= 0 && actWeight >= idleWeight) Decision.Act(samples[actIdx], actIdx, actDist)
        else Decision.Idle
    }

    companion object {
        private const val K = 5
    }
}

class TrainResult(val threshold: Float, val agreement: Int, val moves: Int, val moments: Int)

object Trainer {
    const val DEFAULT_THRESHOLD = 0.06f
    const val MAX_SAMPLES = 4000
    private const val MAX_PROBES = 300

    /**
     * Works out how different a screen may look before it counts as "unfamiliar", and
     * measures how often the policy reproduces the player's own choice on moments it is
     * not allowed to look at (leave-one-out). That score is shown as "match %".
     */
    fun calibrate(samples: List<Sample>): TrainResult {
        val moves = samples.count { it.action != null }
        if (samples.size < 4) return TrainResult(DEFAULT_THRESHOLD, -1, moves, samples.size)

        val step = maxOf(1, samples.size / MAX_PROBES)
        val probes = samples.indices.filter { it % step == 0 }
        val nearest = FloatArray(probes.size)
        for ((n, i) in probes.withIndex()) {
            var best = Float.MAX_VALUE
            for (j in samples.indices) {
                if (j == i) continue
                val d = Features.distance(samples[i].feat, samples[j].feat)
                if (d < best) best = d
            }
            nearest[n] = best
        }
        val sorted = nearest.sortedArray()
        val p90 = sorted[((sorted.size - 1) * 0.9f).toInt()]
        val threshold = (p90 * 1.5f + 0.01f).coerceIn(0.03f, 0.2f)

        val policy = Policy(samples, threshold)
        var agree = 0
        for (i in probes) {
            val s = samples[i]
            when (val d = policy.decide(s.feat, skip = i)) {
                is Decision.Idle -> if (s.action == null) agree++
                is Decision.Act -> if (sameMove(s, d.sample)) agree++
                is Decision.Unknown -> {}
            }
        }
        return TrainResult(threshold, agree * 100 / probes.size, moves, samples.size)
    }

    /** Same kind of touch, starting within ~12% of the screen of each other. */
    fun sameMove(a: Sample, b: Sample): Boolean {
        val pa = a.start() ?: return false
        val pb = b.start() ?: return false
        val swipeA = a.action?.type == "gesture" || a.action?.type == "swipe"
        val swipeB = b.action?.type == "gesture" || b.action?.type == "swipe"
        return swipeA == swipeB && hypot(pa.first - pb.first, pa.second - pb.second) < 0.12f
    }

    /** Appends [fresh] to [old], dropping the oldest idle moments first if there are too many. */
    fun merge(old: List<Sample>, fresh: List<Sample>): List<Sample> {
        val all = ArrayList<Sample>(old.size + fresh.size)
        all.addAll(old); all.addAll(fresh)
        var extra = all.size - MAX_SAMPLES
        if (extra <= 0) return all
        val out = ArrayList<Sample>(MAX_SAMPLES)
        for (s in all) {
            if (extra > 0 && s.action == null) { extra--; continue }
            out.add(s)
        }
        return if (out.size > MAX_SAMPLES) out.subList(out.size - MAX_SAMPLES, out.size) else out
    }

    /** A short description of how the player plays, for the AI that handles unfamiliar screens. */
    fun describeStyle(samples: List<Sample>): String {
        val moves = samples.filter { it.action != null }
        if (moves.isEmpty()) return "No demonstrations recorded."
        val kinds = moves.groupingBy {
            when (it.action!!.type) {
                "gesture", "swipe" -> "swipes/drags"
                "long_press" -> "long presses"
                "double_tap" -> "double taps"
                else -> "taps"
            }
        }.eachCount()
        val zones = HashMap<Int, Int>()
        for (s in moves) {
            val p = s.start() ?: continue
            val gx = (p.first * 5).toInt().coerceIn(0, 4)
            val gy = (p.second * 5).toInt().coerceIn(0, 4)
            zones[gy * 5 + gx] = (zones[gy * 5 + gx] ?: 0) + 1
        }
        val sb = StringBuilder()
        sb.append(moves.size).append(" moves over ").append(samples.size).append(" observed moments: ")
        sb.append(kinds.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.value} ${it.key}" })
        sb.append(". Touches usually start around (x%, y% of the screen): ")
        sb.append(zones.entries.sortedByDescending { it.value }.take(5).joinToString("; ") {
            val gx = it.key % 5; val gy = it.key / 5
            "(${gx * 20 + 10}%, ${gy * 20 + 10}%) ${it.value * 100 / moves.size}% of moves"
        })
        sb.append('.')
        return sb.toString()
    }
}

/** Binary (de)serialisation of demonstrations. */
object SampleCodec {
    private const val MAGIC = 0x54424731 // "TBG1"

    fun write(out: OutputStream, samples: List<Sample>) {
        val d = DataOutputStream(out)
        d.writeInt(MAGIC)
        d.writeInt(Features.SIZE)
        d.writeInt(samples.size)
        for (s in samples) {
            d.writeInt(s.w); d.writeInt(s.h)
            d.write(s.feat, 0, Features.SIZE)
            val json = s.action?.toJson()?.toString()?.toByteArray(Charsets.UTF_8)
            d.writeInt(json?.size ?: -1)
            if (json != null) d.write(json)
        }
        d.flush()
    }

    /** Returns an empty list for missing, corrupt or incompatible data. */
    fun read(input: InputStream): List<Sample> {
        return try {
            val d = DataInputStream(input)
            if (d.readInt() != MAGIC || d.readInt() != Features.SIZE) return emptyList()
            val n = d.readInt()
            if (n < 0 || n > Trainer.MAX_SAMPLES * 2) return emptyList()
            val out = ArrayList<Sample>(n)
            repeat(n) {
                val w = d.readInt(); val h = d.readInt()
                val feat = ByteArray(Features.SIZE)
                d.readFully(feat)
                val len = d.readInt()
                val action = if (len < 0) null else {
                    if (len > 1_000_000) return emptyList()
                    val buf = ByteArray(len)
                    d.readFully(buf)
                    Action.fromJson(JSONObject(String(buf, Charsets.UTF_8)))
                }
                out.add(Sample(feat, action, w, h))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }
}
