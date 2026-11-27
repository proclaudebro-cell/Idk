package app.floattok.playback

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import app.floattok.data.Kind
import app.floattok.data.Net
import app.floattok.data.VideoItem
import app.floattok.data.VideoMode

/** MediaPlayer + TextureView for local files and direct video URLs. Supports speed, loop, fit and crop. */
class NativeVideoBackend(private val context: Context) : PlayerBackend, TextureView.SurfaceTextureListener {

    private val main = Handler(Looper.getMainLooper())
    private val texture = TextureView(context)
    private val container = object : FrameLayout(context) {
        override fun onInterceptTouchEvent(ev: android.view.MotionEvent?) = true
    }.apply {
        setBackgroundColor(Color.BLACK)
        addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override val view: View get() = container
    override val supportsSpeed = true
    override var listener: PlayerBackend.Listener? = null

    private var mp: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false
    private var wantPlay = false
    private var startMs = 0L
    private var speed = 1f
    private var loop = false
    private var muted = false
    private var mode = VideoMode.ORIGINAL
    private var videoW = 0
    private var videoH = 0
    private var pausedByFocusLoss = false

    private val audio = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attrs)
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener({ change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> { pausedByFocusLoss = false; pause() }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (isPlaying()) { pause(); pausedByFocusLoss = true }
                AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocusLoss) { pausedByFocusLoss = false; play() }
            }
        }, main)
        .build()

    private val ticker = object : Runnable {
        override fun run() {
            val p = mp
            if (p != null && prepared) {
                try { listener?.onProgress(p.currentPosition.toLong(), p.duration.toLong()) } catch (_: IllegalStateException) {}
            }
            main.postDelayed(this, 500)
        }
    }

    init {
        texture.surfaceTextureListener = this
        if (texture.isAvailable) texture.surfaceTexture?.let { surface = Surface(it) }
    }

    private fun isPlaying() = try { prepared && mp?.isPlaying == true } catch (_: IllegalStateException) { false }

    override fun load(item: VideoItem, startMs: Long, autoplay: Boolean) {
        releasePlayer()
        this.startMs = startMs
        wantPlay = autoplay
        prepared = false
        val p = MediaPlayer()
        mp = p
        p.setAudioAttributes(attrs)
        p.setOnPreparedListener {
            if (mp !== it) return@setOnPreparedListener
            prepared = true
            it.isLooping = loop
            applyVolume()
            if (this.startMs > 0) it.seekTo(this.startMs, MediaPlayer.SEEK_CLOSEST)
            listener?.onReady(it.duration.toLong())
            if (wantPlay) play() else listener?.onPlayingChanged(false)
            main.removeCallbacks(ticker)
            main.post(ticker)
        }
        p.setOnCompletionListener {
            if (mp !== it || loop) return@setOnCompletionListener
            listener?.onPlayingChanged(false)
            listener?.onEnded()
        }
        p.setOnErrorListener { it, what, extra ->
            if (mp === it) listener?.onError("Can't play this video (error $what/$extra).")
            true
        }
        p.setOnVideoSizeChangedListener { _, w, h ->
            if (w > 0 && h > 0) {
                videoW = w; videoH = h
                applyTransform()
                listener?.onVideoSize(w, h)
            }
        }
        p.setOnInfoListener { _, what, _ ->
            when (what) {
                MediaPlayer.MEDIA_INFO_BUFFERING_START -> listener?.onBuffering(true)
                MediaPlayer.MEDIA_INFO_BUFFERING_END -> listener?.onBuffering(false)
            }
            false
        }
        try {
            val uri = Uri.parse(item.url)
            if (item.kind == Kind.LOCAL) p.setDataSource(context, uri)
            else p.setDataSource(context, uri, mapOf("User-Agent" to Net.userAgent(context)))
            surface?.let { p.setSurface(it) }
            p.prepareAsync()
            main.postDelayed({ if (mp === p && !prepared) listener?.onError("This video took too long to load.") }, 30_000)
        } catch (e: Exception) {
            listener?.onError("Can't open this video: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override fun play() {
        wantPlay = true
        val p = mp ?: return
        if (!prepared) return
        audio.requestAudioFocus(focusRequest)
        try {
            p.start()
            if (speed != 1f) p.playbackParams = p.playbackParams.setSpeed(speed)
            listener?.onPlayingChanged(true)
        } catch (e: IllegalStateException) {
            listener?.onError("Playback error")
        }
    }

    override fun pause() {
        wantPlay = false
        val p = mp ?: return
        if (isPlaying()) p.pause()
        listener?.onPlayingChanged(false)
    }

    override fun seekTo(ms: Long) {
        val p = mp
        if (p != null && prepared) p.seekTo(ms, MediaPlayer.SEEK_CLOSEST) else startMs = ms
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed
        // Changing speed on a paused MediaPlayer starts it, so only apply while playing (play() re-applies).
        if (isPlaying()) try { mp?.let { it.playbackParams = it.playbackParams.setSpeed(speed) } } catch (_: Exception) {}
    }

    override fun setLoop(loop: Boolean) {
        this.loop = loop
        if (prepared) mp?.isLooping = loop
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        applyVolume()
    }

    private fun applyVolume() {
        val v = if (muted) 0f else 1f
        if (prepared) try { mp?.setVolume(v, v) } catch (_: IllegalStateException) {}
    }

    override fun setVideoMode(mode: VideoMode) {
        this.mode = mode
        applyTransform()
    }

    override fun setViewport(width: Int, height: Int) = applyTransform()

    /** TextureView stretches frames to its size; correct the aspect for fit (letterbox) or crop (fill). */
    private fun applyTransform() {
        val vw = texture.width.toFloat()
        val vh = texture.height.toFloat()
        if (vw <= 0 || vh <= 0 || videoW <= 0 || videoH <= 0) return
        val videoAspect = videoW.toFloat() / videoH
        val viewAspect = vw / vh
        var sx = 1f
        var sy = 1f
        if (mode == VideoMode.CROP) {
            if (videoAspect > viewAspect) sx = videoAspect / viewAspect else sy = viewAspect / videoAspect
        } else {
            if (videoAspect > viewAspect) sy = viewAspect / videoAspect else sx = videoAspect / viewAspect
        }
        texture.setTransform(Matrix().apply { setScale(sx, sy, vw / 2f, vh / 2f) })
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(st)
        try { mp?.setSurface(surface) } catch (_: IllegalStateException) {}
        applyTransform()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = applyTransform()

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        try { mp?.setSurface(null) } catch (_: IllegalStateException) {}
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    private fun releasePlayer() {
        main.removeCallbacks(ticker)
        mp?.let {
            it.setOnPreparedListener(null)
            it.setOnCompletionListener(null)
            it.setOnErrorListener(null)
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        mp = null
        prepared = false
        audio.abandonAudioFocusRequest(focusRequest)
    }

    override fun stop() = releasePlayer()

    override fun release() {
        releasePlayer()
        surface?.release()
        surface = null
        listener = null
    }
}
