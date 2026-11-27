package app.floattok.playback

import android.content.Context
import app.floattok.data.Library
import app.floattok.data.Prefs
import app.floattok.overlay.OverlayService

/**
 * Queue edits made from the app screens. The queue lives in the database; a running player is told to
 * reload it and keeps playing the same video.
 */
object QueueOps {

    fun currentId(context: Context): Long? =
        PlaybackState.currentId ?: Prefs(context).lastVideoRowId.takeIf { it >= 0 }

    private fun save(context: Context, ids: List<Long>) {
        Library.get(context).saveQueue(ids)
        OverlayService.instance?.onQueueEdited()
        PlaybackState.queueVersion++
        PlaybackState.changed()
    }

    /** Puts [id] right after the current video (moving it if already queued). Returns its index. */
    fun insertNext(context: Context, id: Long): Int {
        val ids = Library.get(context).queueIds().toMutableList()
        val cur = currentId(context)
        if (id == cur && ids.contains(id)) return ids.indexOf(id)
        ids.remove(id)
        val at = (if (cur != null) ids.indexOf(cur) else -1) + 1
        ids.add(at, id)
        save(context, ids)
        return at
    }

    fun append(context: Context, newIds: List<Long>) {
        val ids = Library.get(context).queueIds().toMutableList()
        val set = ids.toHashSet()
        newIds.forEach { if (set.add(it)) ids.add(it) }
        save(context, ids)
    }

    fun remove(context: Context, index: Int) {
        val ids = Library.get(context).queueIds().toMutableList()
        if (index in ids.indices) { ids.removeAt(index); save(context, ids) }
    }

    fun move(context: Context, from: Int, to: Int) {
        val ids = Library.get(context).queueIds().toMutableList()
        if (from !in ids.indices || to !in ids.indices) return
        ids.add(to, ids.removeAt(from))
        save(context, ids)
    }

    fun clear(context: Context) = save(context, emptyList())

    /** Plays [index] of the stored queue, starting the floating player if needed. */
    fun playIndex(context: Context, index: Int) {
        val svc = OverlayService.instance
        if (svc != null) {
            svc.controller.reloadQueueFromStorage()
            svc.controller.playIndex(index)
            OverlayService.start(context)
        } else {
            OverlayService.start(context, Library.get(context).queueIds(), index)
        }
    }

    /** Replaces the queue with [ids] and starts playing at [index]. */
    fun playList(context: Context, ids: List<Long>, index: Int) {
        if (ids.isEmpty()) return
        OverlayService.start(context, ids, index)
    }
}
