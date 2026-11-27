package app.floattok.playback

import kotlin.random.Random

enum class RepeatMode { OFF, ONE, ALL;
    fun next() = entries[(ordinal + 1) % entries.size]
}

/**
 * Ordered list of library row ids plus the play order (identity, or a shuffled permutation).
 * Pure logic: no Android types, so the advance rules are unit tested.
 */
class PlaybackQueue(ids: List<Long> = emptyList(), private val random: Random = Random.Default) {

    val ids: MutableList<Long> = ids.toMutableList()

    /** Positions into [ids] in the order they will be played. */
    private var order: IntArray = IntArray(this.ids.size) { it }
    private var orderPos = 0

    var shuffle = false
        private set

    val size get() = ids.size
    val isEmpty get() = ids.isEmpty()

    /** Index into [ids] of the current item, or -1 when empty. */
    val currentIndex: Int get() = if (ids.isEmpty()) -1 else order[orderPos.coerceIn(0, order.size - 1)]
    val currentId: Long? get() = if (ids.isEmpty()) null else ids[currentIndex]

    fun setItems(newIds: List<Long>, startIndex: Int) {
        ids.clear()
        ids.addAll(newIds)
        rebuildOrder(startIndex.coerceIn(0, (ids.size - 1).coerceAtLeast(0)))
    }

    fun jumpTo(index: Int) {
        if (ids.isEmpty()) return
        val i = index.coerceIn(0, ids.size - 1)
        if (shuffle) rebuildOrder(i) else orderPos = i
    }

    fun setShuffle(on: Boolean) {
        if (on == shuffle) return
        shuffle = on
        rebuildOrder(currentIndex.coerceAtLeast(0))
    }

    private fun rebuildOrder(startIndex: Int) {
        val n = ids.size
        if (n == 0) { order = IntArray(0); orderPos = 0; return }
        if (!shuffle) {
            order = IntArray(n) { it }
            orderPos = startIndex
        } else {
            val rest = (0 until n).filter { it != startIndex }.shuffled(random)
            order = IntArray(n)
            order[0] = startIndex
            rest.forEachIndexed { i, v -> order[i + 1] = v }
            orderPos = 0
        }
    }

    /**
     * What to play after the current item.
     * [natural] is true when the video finished by itself (autoplay rules apply), false for the Next button.
     * Returns the index into [ids] to play, or null to stop.
     */
    fun advance(natural: Boolean, autoplay: Boolean, repeat: RepeatMode): Int? {
        if (ids.isEmpty()) return null
        if (natural) {
            if (repeat == RepeatMode.ONE) return currentIndex
            if (!autoplay) return null
        }
        if (orderPos < order.size - 1) {
            orderPos++
            return currentIndex
        }
        // At the end of the queue.
        if (repeat == RepeatMode.ALL || !natural) {
            if (shuffle) {
                val last = currentIndex
                rebuildOrder((0 until ids.size).filter { it != last || ids.size == 1 }.random(random))
            } else {
                orderPos = 0
            }
            return currentIndex
        }
        return null
    }

    /** Previous item in play order; at the start wraps when repeating all, otherwise stays put. */
    fun back(repeat: RepeatMode): Int? {
        if (ids.isEmpty()) return null
        if (orderPos > 0) {
            orderPos--
        } else if (repeat == RepeatMode.ALL) {
            orderPos = order.size - 1
        }
        return currentIndex
    }

    fun peekNextIndex(autoplay: Boolean, repeat: RepeatMode): Int? {
        if (ids.isEmpty()) return null
        if (orderPos < order.size - 1) return order[orderPos + 1]
        return if (repeat == RepeatMode.ALL) order[0] else null
    }

    fun remove(index: Int) {
        if (index !in ids.indices) return
        val cur = currentIndex
        ids.removeAt(index)
        val newCur = when {
            ids.isEmpty() -> 0
            index < cur -> cur - 1
            index == cur -> cur.coerceAtMost(ids.size - 1)
            else -> cur
        }
        rebuildOrderKeepingShuffle(newCur)
    }

    fun move(from: Int, to: Int) {
        if (from !in ids.indices || to !in ids.indices || from == to) return
        val curId = currentId
        val v = ids.removeAt(from)
        ids.add(to, v)
        rebuildOrderKeepingShuffle(ids.indexOf(curId).coerceAtLeast(0))
    }

    fun insertNext(id: Long) {
        val at = if (ids.isEmpty()) 0 else currentIndex + 1
        val curId = currentId
        ids.add(at, id)
        rebuildOrderKeepingShuffle(if (curId == null) 0 else ids.indexOf(curId))
    }

    fun append(newIds: List<Long>) {
        val curId = currentId
        ids.addAll(newIds)
        rebuildOrderKeepingShuffle(if (curId == null) 0 else ids.indexOf(curId))
    }

    private fun rebuildOrderKeepingShuffle(cur: Int) {
        rebuildOrder(cur.coerceIn(0, (ids.size - 1).coerceAtLeast(0)))
    }

    fun orderSnapshot(): IntArray = order.copyOf()
    fun orderPosition(): Int = orderPos

    /** Restores a saved shuffle order if it still matches the queue. */
    fun restoreOrder(saved: IntArray, pos: Int, shuffled: Boolean) {
        shuffle = shuffled
        if (saved.size == ids.size && saved.sorted() == (0 until ids.size).toList() && pos in saved.indices) {
            order = saved.copyOf()
            orderPos = pos
        } else {
            rebuildOrder(0)
        }
    }
}
