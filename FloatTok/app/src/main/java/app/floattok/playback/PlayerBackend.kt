package app.floattok.playback

import android.view.View
import app.floattok.data.VideoItem
import app.floattok.data.VideoMode

/**
 * A playback engine that renders into a View. The floating window only talks to this interface,
 * so the video source can change without touching the overlay code.
 */
interface PlayerBackend {
    val view: View

    /** Whether playback speed can be changed for the current item. */
    val supportsSpeed: Boolean

    var listener: Listener?

    fun load(item: VideoItem, startMs: Long, autoplay: Boolean)
    fun play()
    fun pause()
    fun seekTo(ms: Long)
    fun setSpeed(speed: Float)
    fun setLoop(loop: Boolean)
    fun setMuted(muted: Boolean)
    fun setVideoMode(mode: VideoMode)

    /** The size of the area the video is shown in, in px (the window content size). */
    fun setViewport(width: Int, height: Int) {}

    /** Stops playback and frees the current item but keeps the engine reusable. */
    fun stop()

    /** Frees everything; the backend can't be used afterwards. */
    fun release()

    interface Listener {
        fun onReady(durationMs: Long)
        fun onPlayingChanged(playing: Boolean)
        fun onBuffering(buffering: Boolean)
        fun onProgress(positionMs: Long, durationMs: Long)
        fun onEnded()
        fun onError(message: String)
        fun onVideoSize(width: Int, height: Int)
    }
}
