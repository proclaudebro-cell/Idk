package app.floattok.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.floattok.data.Kind
import app.floattok.data.Library
import app.floattok.data.MetadataFetcher
import app.floattok.data.Prefs
import app.floattok.data.VideoItem
import app.floattok.data.VideoMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Snapshot of what is playing, for the app screens (which don't hold a reference to the service). */
object PlaybackState {
    @Volatile var running = false
    @Volatile var currentId: Long? = null
    @Volatile var playing = false
    @Volatile var queueVersion = 0
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun add(l: () -> Unit) { listeners.add(l) }
    fun remove(l: () -> Unit) { listeners.remove(l) }
    fun changed() { main.post { listeners.forEach { it() } } }
}

/**
 * Queue + current item + backend selection. The floating window and the notification observe it through
 * [addObserver]. Backends are created with [viewContext] (the overlay window context).
 */
class PlayerController(private val viewContext: Context, private val prefs: Prefs) : PlayerBackend.Listener {

    interface Observer {
        fun onStateChanged() {}
        fun onBackendChanged(backend: PlayerBackend) {}
        fun onVideoAspectChanged(aspect: Float) {}
        fun onMessage(text: String?) {}
        fun onItemChanged(item: VideoItem?) {}
    }

    private val app = viewContext.applicationContext
    private val lib = Library.get(app)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val observers = CopyOnWriteArrayList<Observer>()

    val queue = PlaybackQueue()
    var current: VideoItem? = null
        private set
    var playing = false
        private set
    var buffering = false
        private set
    var positionMs = 0L
        private set
    var durationMs = 0L
        private set
    /** Width / height of the current video. TikTok videos are usually 9:16 until the real size is known. */
    var videoAspect = 9f / 16f
        private set

    private var tiktok: TikTokWebBackend? = null
    private var tiktokMode = -1
    private var native: NativeVideoBackend? = null
    var backend: PlayerBackend? = null
        private set

    private var loadSeq = 0
    private var consecutiveErrors = 0
    private var lastSavedPos = 0L
    private var released = false

    private fun bg(task: () -> Unit) { if (!io.isShutdown) io.execute(task) }

    fun addObserver(o: Observer) { observers.add(o) }
    fun removeObserver(o: Observer) { observers.remove(o) }
    private fun notifyState() {
        observers.forEach { it.onStateChanged() }
        PlaybackState.currentId = current?.id
        PlaybackState.playing = playing
        PlaybackState.changed()
    }
    private fun message(text: String?) = observers.forEach { it.onMessage(text) }

    // ---------------------------------------------------------------- queue

    /** Loads the saved queue (and shuffle order) from storage. */
    fun restoreQueue() {
        val ids = lib.queueIds()
        queue.setItems(ids, 0)
        prefs.queueOrder()?.let { (order, pos) -> queue.restoreOrder(order, pos, prefs.shuffle) }
        if (queue.shuffle != prefs.shuffle) queue.setShuffle(prefs.shuffle)
        val last = prefs.lastVideoRowId
        val idx = ids.indexOf(last)
        if (idx >= 0 && queue.currentIndex != idx) queue.jumpTo(idx)
    }

    private fun persistQueue() {
        val ids = ArrayList(queue.ids)
        val order = queue.orderSnapshot()
        val pos = queue.orderPosition()
        bg {
            lib.saveQueue(ids)
            prefs.saveQueueOrder(order, pos)
        }
        PlaybackState.queueVersion++
        PlaybackState.changed()
    }

    fun setQueue(ids: List<Long>, startIndex: Int, autoplay: Boolean = true) {
        if (ids.isEmpty()) return
        queue.setShuffle(prefs.shuffle)
        queue.setItems(ids, startIndex)
        persistQueue()
        playCurrent(0, autoplay)
    }

    /** The queue was edited in the app; keep playing the same item if it is still there. */
    fun reloadQueueFromStorage() {
        val curId = current?.id
        val ids = lib.queueIds()
        queue.setItems(ids, 0)
        prefs.queueOrder()?.let { (order, pos) -> queue.restoreOrder(order, pos, prefs.shuffle) }
        val idx = if (curId != null) ids.indexOf(curId) else -1
        if (idx >= 0) queue.jumpTo(idx)
        notifyState()
    }

    fun playIndex(index: Int) {
        if (queue.isEmpty) return
        queue.jumpTo(index)
        persistQueue()
        playCurrent(0, true)
    }

    /** Resume the last item and position (or the start of the queue). */
    fun resumeLast() {
        if (queue.isEmpty) restoreQueue()
        if (queue.isEmpty) {
            message("Queue is empty. Pick a video in FloatTok.")
            notifyState()
            return
        }
        val id = queue.currentId
        val start = if (prefs.resumeLast && id == prefs.lastVideoRowId) prefs.lastPositionMs else 0L
        playCurrent(start, true)
    }

    // ---------------------------------------------------------------- playback

    private fun backendFor(item: VideoItem): PlayerBackend {
        return if (item.isTikTok) {
            val mode = prefs.tiktokEmbedMode
            if (tiktok == null || tiktokMode != mode) {
                val old = tiktok
                if (backend === old) backend = null
                old?.release()
                tiktok = TikTokWebBackend(viewContext, mode).also { it.listener = this }
                tiktokMode = mode
            }
            tiktok!!
        } else {
            native ?: NativeVideoBackend(viewContext).also { it.listener = this; native = it }
        }
    }

    private fun playCurrent(startMs: Long, autoplay: Boolean) {
        val id = queue.currentId ?: return
        val seq = ++loadSeq
        savePosition(force = true)
        bg {
            var item = lib.get(id)
            if (item != null && item.kind == Kind.TIKTOK_SHORT) {
                main.post { if (seq == loadSeq) message("Resolving link…") }
                item = MetadataFetcher.get(app).fetchNow(item)
            }
            main.post {
                if (seq != loadSeq || released) return@post
                if (item == null) {
                    message("That video was removed from the library.")
                    onError("missing")
                    return@post
                }
                if (item.id != id) {
                    // Short link merged into an existing entry.
                    val i = queue.ids.indexOf(id)
                    if (i >= 0) queue.ids[i] = item.id
                    persistQueue()
                }
                startItem(item, startMs, autoplay)
            }
        }
    }

    private fun startItem(item: VideoItem, startMs: Long, autoplay: Boolean) {
        if (item.kind == Kind.TIKTOK_SHORT) {
            message("Couldn't resolve this TikTok link.")
            onError("unresolved")
            return
        }
        val b = backendFor(item)
        if (backend !== b) {
            backend?.stop()
            backend = b
            observers.forEach { it.onBackendChanged(b) }
        }
        current = item
        positionMs = startMs
        durationMs = item.durationMs
        playing = false
        buffering = true
        message(null)
        observers.forEach { it.onItemChanged(item) }
        applySettingsToBackend()
        b.load(item, startMs, autoplay)
        prefs.lastVideoRowId = item.id
        prefs.lastPositionMs = startMs
        notifyState()
        if (item.title == null) MetadataFetcher.get(app).request(item)
    }

    fun applySettingsToBackend() {
        val b = backend ?: return
        b.setSpeed(prefs.speed)
        b.setLoop(prefs.repeat == RepeatMode.ONE)
        b.setMuted(prefs.muted)
        b.setVideoMode(prefs.videoMode)
    }

    fun applySettings() {
        if (queue.shuffle != prefs.shuffle) {
            queue.setShuffle(prefs.shuffle)
            persistQueue()
        }
        if (tiktok != null && tiktokMode != prefs.tiktokEmbedMode && current?.isTikTok == true) {
            val pos = positionMs
            playCurrent(pos, playing)
        }
        applySettingsToBackend()
        notifyState()
    }

    fun play() {
        if (backend == null || current == null) { resumeLast(); return }
        backend?.play()
    }

    fun pause() { backend?.pause(); savePosition(force = true) }

    fun togglePlay() = if (playing) pause() else play()

    fun next() {
        queue.advance(natural = false, autoplay = true, repeat = prefs.repeat) ?: return
        persistQueue()
        consecutiveErrors = 0
        playCurrent(0, true)
    }

    fun previous() {
        // Restart the current video if we're more than 3 s in, like most players.
        if (positionMs > 3000) { seekTo(0); return }
        queue.back(prefs.repeat) ?: return
        persistQueue()
        consecutiveErrors = 0
        playCurrent(0, true)
    }

    fun seekTo(ms: Long) {
        backend?.seekTo(ms.coerceAtLeast(0))
        positionMs = ms
        notifyState()
    }

    fun seekBy(deltaMs: Long) = seekTo((positionMs + deltaMs).coerceIn(0, if (durationMs > 0) durationMs else Long.MAX_VALUE))

    fun savePosition(force: Boolean = false) {
        val item = current ?: return
        if (!force && kotlin.math.abs(positionMs - lastSavedPos) < 5000) return
        lastSavedPos = positionMs
        val pos = positionMs
        val dur = durationMs
        prefs.lastVideoRowId = item.id
        prefs.lastPositionMs = pos
        bg { lib.savePosition(item.id, pos, dur) }
    }

    // ---------------------------------------------------------------- backend events

    override fun onReady(durationMs: Long) {
        if (durationMs > 0) this.durationMs = durationMs
        consecutiveErrors = 0
        notifyState()
    }

    override fun onPlayingChanged(playing: Boolean) {
        this.playing = playing
        if (playing) buffering = false
        notifyState()
        if (!playing) savePosition(force = true)
    }

    override fun onBuffering(buffering: Boolean) {
        this.buffering = buffering
        notifyState()
    }

    override fun onProgress(positionMs: Long, durationMs: Long) {
        this.positionMs = positionMs
        if (durationMs > 0) this.durationMs = durationMs
        savePosition()
        observers.forEach { it.onStateChanged() }
    }

    override fun onEnded() {
        playing = false
        positionMs = 0
        savePosition(force = true)
        val next = queue.advance(natural = true, autoplay = prefs.autoplay, repeat = prefs.repeat)
        if (next == null) {
            notifyState()
            message(if (prefs.autoplay) "End of queue" else null)
            return
        }
        persistQueue()
        playCurrent(0, true)
    }

    override fun onError(message: String) {
        playing = false
        buffering = false
        if (message != "missing" && message != "unresolved") message(message)
        notifyState()
        consecutiveErrors++
        // Skip unplayable videos when autoplay is on, but stop if everything is failing (e.g. offline).
        if (prefs.autoplay && queue.size > 1 && consecutiveErrors < 5) {
            val seq = loadSeq
            main.postDelayed({
                if (seq != loadSeq || released) return@postDelayed
                if (queue.advance(natural = true, autoplay = true, repeat = prefs.repeat) != null) {
                    persistQueue()
                    playCurrent(0, true)
                }
            }, 2500)
        }
    }

    override fun onVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val a = width.toFloat() / height
        if (kotlin.math.abs(a - videoAspect) < 0.01f) return
        videoAspect = a
        observers.forEach { it.onVideoAspectChanged(a) }
    }

    fun release() {
        if (released) return
        savePosition(force = true)
        released = true
        loadSeq++
        tiktok?.release()
        native?.release()
        tiktok = null
        native = null
        backend = null
        current = null
        playing = false
        io.shutdown()
        notifyState()
    }

    val isLooping get() = prefs.repeat == RepeatMode.ONE
    val videoModeIsOriginal get() = prefs.videoMode == VideoMode.ORIGINAL
}
