package app.floattok.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import app.floattok.R
import app.floattok.data.Prefs
import app.floattok.data.VideoItem
import app.floattok.data.VideoMode
import app.floattok.playback.PlayerBackend
import app.floattok.playback.PlayerController
import app.floattok.playback.RepeatMode
import app.floattok.ui.Ui
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The floating player: one TYPE_APPLICATION_OVERLAY window that can be dragged, pinched, resized from any edge or
 * corner, maximized with a double tap, and flung against a screen edge to dock. The backend's view (WebView or
 * TextureView) stays attached the whole time, so docking and resizing never reload the video.
 */
@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
class FloatingPlayerWindow(
    private val ctx: Context,
    private val wm: WindowManager,
    private val prefs: Prefs,
    private val controller: PlayerController,
    private val host: Host,
) : PlayerController.Observer {

    interface Host {
        val geometry: WindowGeometry
        val isLandscape: Boolean
        fun closePlayer()
        fun openApp(settings: Boolean)
        fun showTab(side: Int, centerY: Int)
        fun hideTab()
        fun tabCenterY(): Int?
        fun onUserSettingChanged()
    }

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).roundToInt()
    private fun dp(v: Int) = dp(v.toFloat())
    private val main = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()

    private val geom get() = host.geometry

    // ---------------------------------------------------------------- views

    val root: RootLayout = RootLayout()
    private val content = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }
    private val controls = FrameLayout(ctx)
    private val scrimTop = View(ctx).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(0xB0, 0, 0, 0), Color.TRANSPARENT))
    }
    private val scrimBottom = View(ctx).apply {
        background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.argb(0xC0, 0, 0, 0), Color.TRANSPARENT))
    }
    private val topBar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val btnClose = iconButton(R.drawable.ic_close, "Close player") { host.closePlayer() }
    private val titleView = Ui.text(ctx, "", 12f, Color.WHITE, bold = true).apply {
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setShadowLayer(4f, 0f, 1f, Color.BLACK)
    }
    private val btnHome = iconButton(R.drawable.ic_home, "Open FloatTok") { host.openApp(false) }
    private val btnDock = iconButton(R.drawable.ic_chevron_right, "Dock to edge") { dock(if (box.cx < geom.screenW / 2) DockSide.LEFT else DockSide.RIGHT) }
    private val btnMore = iconButton(R.drawable.ic_more, "Player settings") { togglePanel() }

    private val centerRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    private val btnPrev = iconButton(R.drawable.ic_prev, "Previous") { controller.previous(); bumpControls() }
    private val btnPlay = iconButton(R.drawable.ic_play, "Play or pause") { controller.togglePlay(); bumpControls() }.apply {
        background = Ui.ripple(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(0x66, 0, 0, 0)) }, dp(40).toFloat())
    }
    private val btnNext = iconButton(R.drawable.ic_next, "Next") { controller.next(); bumpControls() }

    private val bottomBar = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val seek = SeekStrip(ctx) { f -> if (controller.durationMs > 0) controller.seekTo((f * controller.durationMs).toLong()); bumpControls() }
    private val bottomRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val timeView = Ui.text(ctx, "0:00", 11f, Color.WHITE).apply { setShadowLayer(4f, 0f, 1f, Color.BLACK) }
    private val autoChip = Ui.chip(ctx, "AUTO", prefs.autoplay) { toggleAutoplay() }.apply {
        contentDescription = "Autoplay next video"
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
    }
    private val btnLock = iconButton(R.drawable.ic_lock_open, "Lock size and position") { toggleLock() }
    private val grip = GripView(ctx)

    private val spinner = ProgressBar(ctx).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(Ui.ACCENT)
        visibility = View.GONE
    }
    private val messageView = Ui.text(ctx, "", 12f, Color.WHITE).apply {
        gravity = Gravity.CENTER
        background = Ui.rounded(Color.argb(0xD0, 0x10, 0x10, 0x14), dp(10).toFloat())
        setPadding(dp(10), dp(6), dp(10), dp(6))
        visibility = View.GONE
    }

    private val panel = ScrollView(ctx).apply {
        setBackgroundColor(Color.argb(0xEE, 0x0E, 0x0E, 0x12))
        visibility = View.GONE
        isVerticalScrollBarEnabled = false
    }
    private val panelList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(12)) }
    private val panelRows = ArrayList<Pair<TextView, () -> String>>()

    private val audioBar = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.argb(0xF0, 0x16, 0x16, 0x1A))
        setPadding(dp(6), 0, dp(4), 0)
        visibility = View.GONE
    }
    private val audioTitle = Ui.text(ctx, "", 12f, Color.WHITE, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val audioPlay = iconButton(R.drawable.ic_play, "Play or pause") { controller.togglePlay() }

    private val interactive: List<View> by lazy {
        listOf(btnClose, btnHome, btnDock, btnMore, btnPrev, btnPlay, btnNext, autoChip, btnLock)
    }

    // ---------------------------------------------------------------- window state

    private val lp = WindowManager.LayoutParams(
        1, 1,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "FloatTok player"
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        if (Build.VERSION.SDK_INT >= 33) setCanPlayMoveAnimation(false)
    }

    var box = Box(0, 0, 1, 1)
        private set
    private var attached = false
    var docked = false
        private set
    var dockSide = DockSide.NONE
        private set
    private var preDockBox: Box? = null
    private var resumeOnUndock = false
    private var audioRestoreBox: Box? = null
    private var controlsVisible = true
    private var panelOpen = false
    private var animator: ValueAnimator? = null
    private val locked get() = prefs.positionLocked
    private val audioOnly get() = prefs.audioOnly

    private val hideControlsRunnable = Runnable { hideControls() }
    private val hideMessageRunnable = Runnable { messageView.visibility = View.GONE }
    private val singleTapRunnable = Runnable { onSingleTap() }

    init {
        buildViews()
        controller.addObserver(this)
        controller.backend?.let { onBackendChanged(it) }
    }

    private fun iconButton(res: Int, desc: String, onClick: () -> Unit): ImageView =
        Ui.icon(ctx, res, Color.WHITE, 20, 6).apply {
            contentDescription = desc
            setOnClickListener { onClick() }
        }

    private fun buildViews() {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        root.addView(content, FrameLayout.LayoutParams(match, match))
        root.addView(controls, FrameLayout.LayoutParams(match, match))
        root.addView(audioBar, FrameLayout.LayoutParams(match, match))
        root.addView(panel, FrameLayout.LayoutParams(match, match))

        controls.addView(scrimTop, FrameLayout.LayoutParams(match, dp(64), Gravity.TOP))
        controls.addView(scrimBottom, FrameLayout.LayoutParams(match, dp(72), Gravity.BOTTOM))

        topBar.addView(btnClose)
        topBar.addView(titleView, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
        topBar.addView(btnHome)
        topBar.addView(btnDock)
        topBar.addView(btnMore)
        controls.addView(topBar, FrameLayout.LayoutParams(match, wrap, Gravity.TOP).apply { setMargins(dp(2), dp(2), dp(2), 0) })

        centerRow.addView(btnPrev)
        centerRow.addView(btnPlay, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginStart = dp(10); marginEnd = dp(10) })
        centerRow.addView(btnNext)
        controls.addView(centerRow, FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER))

        bottomRow.addView(timeView, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dp(8) })
        bottomRow.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        bottomRow.addView(autoChip, LinearLayout.LayoutParams(wrap, wrap).apply { marginEnd = dp(2) })
        bottomRow.addView(btnLock)
        bottomBar.addView(seek, LinearLayout.LayoutParams(match, dp(22)))
        bottomBar.addView(bottomRow, LinearLayout.LayoutParams(match, wrap).apply { marginEnd = dp(18) })
        controls.addView(bottomBar, FrameLayout.LayoutParams(match, wrap, Gravity.BOTTOM).apply { setMargins(dp(4), 0, dp(4), dp(2)) })
        controls.addView(grip, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.BOTTOM or Gravity.END))
        controls.addView(spinner, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
        controls.addView(messageView, FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = dp(64) })
        seek.onDragChanged = { dragging -> if (dragging) main.removeCallbacks(hideControlsRunnable) else scheduleHide() }

        // Audio-only bar.
        audioBar.addView(iconButton(R.drawable.ic_headphones, "Leave audio-only mode") { setAudioOnly(false) })
        audioBar.addView(audioTitle, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        audioBar.addView(iconButton(R.drawable.ic_prev, "Previous") { controller.previous() })
        audioBar.addView(audioPlay)
        audioBar.addView(iconButton(R.drawable.ic_next, "Next") { controller.next() })
        audioBar.addView(iconButton(R.drawable.ic_close, "Close player") { host.closePlayer() })

        buildPanel()

        // Rounded corners plus a hairline border so the window reads well over any app.
        root.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, cornerRadius())
        }
        root.clipToOutline = true
    }

    private fun cornerRadius(): Float = min(dp(16).toFloat(), min(box.w, box.h) * 0.12f)

    private fun buildPanel() {
        panel.addView(panelList)
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(Ui.text(ctx, "Player settings", 14f, Ui.ACCENT, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(iconButton(R.drawable.ic_close, "Close settings") { closePanel() })
        panelList.addView(header)

        fun row(label: String, value: () -> String, action: () -> Unit) {
            val r = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(9), dp(4), dp(9))
                background = Ui.ripple(null, dp(8).toFloat())
                isClickable = true
            }
            val v = Ui.text(ctx, value(), 13f, Ui.ACCENT, bold = true)
            r.addView(Ui.text(ctx, label, 13f, Color.WHITE), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(v)
            r.setOnClickListener { action(); refreshPanel(); host.onUserSettingChanged() }
            panelRows.add(v to value)
            panelList.addView(r)
        }
        fun onOff(b: Boolean) = if (b) "On" else "Off"

        row("Autoplay next", { onOff(prefs.autoplay) }) { prefs.autoplay = !prefs.autoplay; refreshControls() }
        row("Shuffle", { onOff(prefs.shuffle) }) { prefs.shuffle = !prefs.shuffle; controller.applySettings() }
        row("Repeat", { when (prefs.repeat) { RepeatMode.OFF -> "Off"; RepeatMode.ONE -> "This video"; RepeatMode.ALL -> "Queue" } }) {
            prefs.repeat = prefs.repeat.next(); controller.applySettings()
        }
        row("Speed", { if (controller.backend?.supportsSpeed == false) "1× (not supported)" else "${fmtSpeed(prefs.speed)}×" }) {
            val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
            val i = speeds.indexOfFirst { abs(it - prefs.speed) < 0.01f }
            prefs.speed = speeds[(i + 1) % speeds.size]; controller.applySettings()
        }
        row("Opacity", { "${(prefs.opacity * 100).roundToInt()}%" }) {
            val steps = floatArrayOf(1f, 0.85f, 0.7f, 0.55f, 0.4f, 0.25f)
            val i = steps.indexOfFirst { abs(it - prefs.opacity) < 0.02f }
            prefs.opacity = steps[(i + 1) % steps.size]; applyOpacity()
        }
        row("Video", { when (prefs.videoMode) { VideoMode.ORIGINAL -> "Original shape"; VideoMode.FIT -> "Fit"; VideoMode.CROP -> "Crop / fill" } }) {
            prefs.videoMode = VideoMode.entries[(prefs.videoMode.ordinal + 1) % VideoMode.entries.size]
            controller.applySettings()
            if (prefs.videoMode == VideoMode.ORIGINAL) onVideoAspectChanged(controller.videoAspect)
        }
        row("Aspect lock", { onOff(prefs.aspectLock) }) { prefs.aspectLock = !prefs.aspectLock }
        row("When docked", { if (prefs.audioWhileDocked) "Keep playing" else "Pause" }) { prefs.audioWhileDocked = !prefs.audioWhileDocked }
        row("Audio only", { onOff(prefs.audioOnly) }) { closePanel(); setAudioOnly(!prefs.audioOnly) }
        row("Mute", { onOff(prefs.muted) }) { prefs.muted = !prefs.muted; controller.applySettings() }
        row("Lock size & position", { onOff(prefs.positionLocked) }) { toggleLock() }
        row("Open FloatTok", { "›" }) { closePanel(); host.openApp(false) }
    }

    private fun fmtSpeed(s: Float) = if (s == s.roundToInt().toFloat()) s.roundToInt().toString() else s.toString().trimEnd('0')

    private fun refreshPanel() = panelRows.forEach { (tv, v) -> tv.text = v() }

    // ---------------------------------------------------------------- attach / layout

    fun show(initial: Box) {
        box = initial
        lp.x = box.x; lp.y = box.y; lp.width = box.w; lp.height = box.h
        lp.alpha = prefs.opacity
        if (!attached) {
            wm.addView(root, lp)
            attached = true
        }
        if (audioOnly) enterAudioOnlyLayout(box)
        refreshControls()
        showControls()
    }

    fun remove() {
        animator?.cancel()
        main.removeCallbacksAndMessages(null)
        controller.removeObserver(this)
        if (attached) {
            try { wm.removeViewImmediate(root) } catch (_: Exception) {}
            attached = false
        }
        content.removeAllViews()
    }

    private fun setBox(b: Box) {
        box = b
        lp.x = b.x; lp.y = b.y; lp.width = b.w; lp.height = b.h
        if (attached) wm.updateViewLayout(root, lp)
    }

    private fun persist() {
        if (docked || audioOnly) return
        prefs.saveWindowBox(host.isLandscape, geom.clampOnScreen(box))
    }

    private fun animateTo(target: Box, durationMs: Long = 220, end: (() -> Unit)? = null) {
        animator?.cancel()
        val from = box
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val f = it.animatedValue as Float
                setBox(Box(
                    (from.x + (target.x - from.x) * f).roundToInt(),
                    (from.y + (target.y - from.y) * f).roundToInt(),
                    (from.w + (target.w - from.w) * f).roundToInt(),
                    (from.h + (target.h - from.h) * f).roundToInt(),
                ))
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled) { setBox(target); end?.invoke() }
                }
            })
            start()
        }
    }

    /** Adapts controls to the window size: everything at normal sizes, just play/pause on a thumbnail. */
    private fun layoutForSize(w: Int, h: Int) {
        root.invalidateOutline()
        val wDp = w / density
        val hDp = h / density
        val tiny = wDp < 130 || hDp < 110
        val compact = wDp < 210 || hDp < 170
        val small = (min(wDp, hDp) * 0.11f).coerceIn(18f, 26f)
        val pad = (small * 0.3f).coerceIn(4f, 7f)
        for (b in listOf(btnClose, btnHome, btnDock, btnMore, btnPrev, btnNext, btnLock)) {
            val s = dp(small + 2 * pad)
            val l = b.layoutParams
            if (l.width != s) { l.width = s; l.height = s; b.layoutParams = l; val p = dp(pad); b.setPadding(p, p, p, p) }
        }
        val playDp = (min(wDp, hDp) * 0.2f).coerceIn(min(28f, hDp * 0.7f), 60f)
        (btnPlay.layoutParams as LinearLayout.LayoutParams).let {
            val s = dp(playDp)
            if (it.width != s) { it.width = s; it.height = s; it.marginStart = dp(playDp * 0.18f); it.marginEnd = dp(playDp * 0.18f); btnPlay.layoutParams = it; val p = dp(playDp * 0.2f); btnPlay.setPadding(p, p, p, p) }
        }
        topBar.visibility = if (hDp < 64) View.GONE else View.VISIBLE
        btnPrev.visibility = if (tiny) View.GONE else View.VISIBLE
        btnNext.visibility = if (tiny) View.GONE else View.VISIBLE
        titleView.visibility = if (compact) View.GONE else View.VISIBLE
        btnHome.visibility = if (tiny) View.GONE else View.VISIBLE
        btnDock.visibility = if (tiny) View.GONE else View.VISIBLE
        btnMore.visibility = if (tiny) View.GONE else View.VISIBLE
        bottomBar.visibility = if (tiny) View.GONE else View.VISIBLE
        timeView.visibility = if (compact) View.GONE else View.VISIBLE
        btnLock.visibility = if (compact) View.GONE else View.VISIBLE
        grip.visibility = if (tiny || locked) View.GONE else View.VISIBLE
        controller.backend?.setViewport(w, h)
        if (panelOpen && (hDp < 200 || wDp < 160)) closePanel()
    }

    // ---------------------------------------------------------------- controller observer

    override fun onBackendChanged(backend: PlayerBackend) {
        val v = backend.view
        if (v.parent === content) return
        (v.parent as? ViewGroup)?.removeView(v)
        content.removeAllViews()
        content.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        backend.setViewport(box.w, box.h)
    }

    override fun onStateChanged() = refreshControls()

    override fun onItemChanged(item: VideoItem?) {
        refreshControls()
        if (!docked) showControls()
    }

    override fun onMessage(text: String?) {
        main.removeCallbacks(hideMessageRunnable)
        if (text == null) { messageView.visibility = View.GONE; return }
        messageView.text = text
        messageView.visibility = View.VISIBLE
        if (!controlsVisible && !docked) showControls()
        main.postDelayed(hideMessageRunnable, 3500)
    }

    override fun onVideoAspectChanged(aspect: Float) {
        if (prefs.videoMode != VideoMode.ORIGINAL || audioOnly) return
        if (docked) { preDockBox = preDockBox?.let { reshape(it, aspect) }; return }
        if (prefs.maximized) animateTo(geom.maximized(aspect, dp(6))) { persist() }
        else animateTo(reshape(box, aspect)) { persist() }
    }

    /** Same area and centre, new aspect ratio. */
    private fun reshape(b: Box, aspect: Float): Box {
        val area = b.w.toFloat() * b.h
        val w = sqrt(area * aspect)
        val (cw, ch) = geom.clampSize(w.roundToInt(), (w / aspect).roundToInt(), aspect)
        return geom.clampOnScreen(Box(b.cx - cw / 2, b.cy - ch / 2, cw, ch))
    }

    private var lastPlaying = false

    fun refreshControls() {
        val playing = controller.playing
        val icon = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        btnPlay.setImageResource(icon)
        audioPlay.setImageResource(icon)
        val title = controller.current?.let { it.displayTitle() + (it.author?.let { a -> "  ·  @$a" } ?: "") } ?: "FloatTok"
        if (titleView.text != title) titleView.text = title
        if (audioTitle.text != title) audioTitle.text = title
        val dur = controller.durationMs
        seek.setProgress(if (dur > 0) controller.positionMs.toFloat() / dur else 0f)
        timeView.text = "${Ui.formatTime(controller.positionMs)} / ${Ui.formatTime(dur)}"
        Ui.styleChip(autoChip, prefs.autoplay)
        btnLock.setImageResource(if (locked) R.drawable.ic_lock else R.drawable.ic_lock_open)
        btnDock.setImageResource(if (box.cx < geom.screenW / 2) R.drawable.ic_chevron_left else R.drawable.ic_chevron_right)
        spinner.visibility = if (controller.buffering && !playing && controller.current != null) View.VISIBLE else View.GONE
        if (playing != lastPlaying) {
            lastPlaying = playing
            if (playing) scheduleHide() else if (!docked) showControls(autoHide = false)
        }
        if (panelOpen) refreshPanel()
    }

    // ---------------------------------------------------------------- controls visibility

    private fun controlsActive() = controlsVisible && controls.visibility == View.VISIBLE && controls.alpha > 0.5f && !audioOnly

    fun showControls(autoHide: Boolean = true) {
        if (audioOnly) return
        controlsVisible = true
        controls.animate().cancel()
        controls.visibility = View.VISIBLE
        controls.animate().alpha(1f).setDuration(150).start()
        if (autoHide) scheduleHide()
    }

    private fun hideControls() {
        if (panelOpen) return
        controlsVisible = false
        controls.animate().cancel()
        controls.animate().alpha(0f).setDuration(200).withEndAction { if (!controlsVisible) controls.visibility = View.INVISIBLE }.start()
    }

    private fun scheduleHide() {
        main.removeCallbacks(hideControlsRunnable)
        if (controller.playing && !panelOpen) main.postDelayed(hideControlsRunnable, prefs.controlsTimeoutMs.toLong())
    }

    private fun bumpControls() { if (controlsVisible) scheduleHide() }

    private fun togglePanel() = if (panelOpen) closePanel() else openPanel()

    private fun openPanel() {
        if (box.h / density < 200 || box.w / density < 160) { host.openApp(true); return }
        panelOpen = true
        refreshPanel()
        panel.visibility = View.VISIBLE
        main.removeCallbacks(hideControlsRunnable)
    }

    private fun closePanel() {
        if (!panelOpen) return
        panelOpen = false
        panel.visibility = View.GONE
        scheduleHide()
    }

    private fun toggleAutoplay() {
        prefs.autoplay = !prefs.autoplay
        refreshControls()
        onMessage(if (prefs.autoplay) "Autoplay on: next video plays automatically" else "Autoplay off: stops after this video")
        host.onUserSettingChanged()
        bumpControls()
    }

    private fun toggleLock() {
        prefs.positionLocked = !prefs.positionLocked
        refreshControls()
        grip.visibility = if (locked) View.GONE else View.VISIBLE
        onMessage(if (locked) "Size and position locked" else "Size and position unlocked")
        host.onUserSettingChanged()
    }

    fun applyOpacity() {
        if (docked) return
        lp.alpha = prefs.opacity
        if (attached) wm.updateViewLayout(root, lp)
    }

    fun applySettings() {
        applyOpacity()
        if (audioOnly != (audioBar.visibility == View.VISIBLE)) setAudioOnly(audioOnly, fromSettings = true)
        refreshControls()
        layoutForSize(box.w, box.h)
    }

    // ---------------------------------------------------------------- maximize / audio-only

    private fun aspectForResize(start: Box): Float? = when {
        audioOnly -> null
        prefs.videoMode == VideoMode.ORIGINAL -> controller.videoAspect
        prefs.aspectLock -> start.w.toFloat() / start.h
        else -> null
    }

    fun toggleMaximize() {
        if (locked || audioOnly || docked) return
        val land = host.isLandscape
        if (prefs.maximized) {
            prefs.maximized = false
            val restore = prefs.restoreBox(land) ?: geom.defaultBox(controller.videoAspect)
            animateTo(geom.clampOnScreen(restore)) { persist() }
        } else {
            prefs.saveRestoreBox(land, box)
            prefs.maximized = true
            val aspect = if (prefs.videoMode == VideoMode.ORIGINAL) controller.videoAspect else if (prefs.aspectLock) box.w.toFloat() / box.h else null
            animateTo(geom.maximized(aspect, dp(6))) { persist() }
        }
    }

    private fun enterAudioOnlyLayout(from: Box) {
        audioRestoreBox = from
        content.alpha = 0f
        controls.visibility = View.INVISIBLE
        controlsVisible = false
        audioBar.visibility = View.VISIBLE
        val w = max(from.w, dp(270)).coerceAtMost(geom.screenW)
        setBox(geom.clampOnScreen(Box(from.x, from.y, w, dp(56))))
    }

    fun setAudioOnly(on: Boolean, fromSettings: Boolean = false) {
        if (!fromSettings) prefs.audioOnly = on
        if (on) {
            if (audioBar.visibility == View.VISIBLE) return
            closePanel()
            enterAudioOnlyLayout(box)
        } else {
            if (audioBar.visibility != View.VISIBLE) return
            audioBar.visibility = View.GONE
            content.alpha = 1f
            val r = audioRestoreBox ?: geom.defaultBox(controller.videoAspect)
            audioRestoreBox = null
            animateTo(geom.clampOnScreen(r.copy(x = box.x, y = box.y))) { persist() }
            showControls()
        }
        host.onUserSettingChanged()
    }

    // ---------------------------------------------------------------- docking

    fun dock(side: Int) {
        if (docked || side == DockSide.NONE) return
        closePanel()
        main.removeCallbacks(hideControlsRunnable)
        controlsVisible = false
        controls.visibility = View.INVISIBLE
        val keep = geom.clampOnScreen(box)
        preDockBox = keep
        prefs.saveWindowBox(host.isLandscape, keep)
        docked = true
        dockSide = side
        prefs.dockSide = side
        val targetX = if (side == DockSide.LEFT) -box.w - dp(8) else geom.screenW + dp(8)
        animateTo(box.copy(x = targetX), 200) {
            // Hidden but still attached: the video keeps its state and (optionally) its sound.
            lp.alpha = 0f
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            if (attached) wm.updateViewLayout(root, lp)
        }
        host.showTab(side, keep.cy)
        resumeOnUndock = false
        if (!prefs.audioWhileDocked && controller.playing) {
            controller.pause()
            resumeOnUndock = true
        }
    }

    /** Puts the window back where it was before docking, lined up with the tab if the tab was moved. */
    fun undock() {
        if (!docked) return
        val tabY = host.tabCenterY()
        host.hideTab()
        docked = false
        prefs.dockSide = DockSide.NONE
        var target = geom.clampOnScreen(preDockBox ?: geom.defaultBox(controller.videoAspect))
        if (tabY != null && abs(tabY - target.cy) > dp(24)) target = geom.clampOnScreen(target.copy(y = tabY - target.h / 2))
        val startX = if (dockSide == DockSide.LEFT) -target.w - dp(8) else geom.screenW + dp(8)
        setBox(target.copy(x = startX))
        lp.alpha = prefs.opacity
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        if (attached) wm.updateViewLayout(root, lp)
        dockSide = DockSide.NONE
        animateTo(target, 240) { persist() }
        if (resumeOnUndock) { resumeOnUndock = false; controller.play() }
        if (!audioOnly) showControls()
    }

    /** Re-fits the window after rotation or a screen size change. */
    fun onGeometryChanged(old: WindowGeometry, new: WindowGeometry) {
        val aspect = aspectForResize(box)
        if (docked) {
            preDockBox = new.adaptFrom(preDockBox ?: box, old, aspect)
            val x = if (dockSide == DockSide.LEFT) -box.w - dp(8) else new.screenW + dp(8)
            setBox(box.copy(x = x, y = preDockBox!!.y))
            host.showTab(dockSide, preDockBox!!.cy)
            return
        }
        if (audioOnly) { setBox(new.clampOnScreen(box.copy(w = box.w.coerceAtMost(new.screenW)))); return }
        val target = if (prefs.maximized) {
            new.maximized(if (prefs.videoMode == VideoMode.ORIGINAL) controller.videoAspect else null, dp(6))
        } else {
            prefs.windowBox(host.isLandscape)?.let { saved ->
                val (w, h) = new.clampSize(saved.w, saved.h, aspect?.let { saved.w.toFloat() / saved.h })
                new.clampOnScreen(saved.copy(w = w, h = h))
            } ?: new.adaptFrom(box, old, aspect)
        }
        setBox(target)
    }

    // ---------------------------------------------------------------- gestures

    private enum class G { NONE, PENDING, DRAG, RESIZE_PENDING, RESIZE, PINCH }

    private var g = G.NONE
    private var downRawX = 0f
    private var downRawY = 0f
    private var startBox = box
    private var edges = 0
    private var downOnInteractive = false
    private var downNoSteal = false
    private var vt: VelocityTracker? = null
    private var pinchStartSpan = 1f
    private var pinchStartSpanX = 1f
    private var pinchStartSpanY = 1f
    private var focusStartX = 0f
    private var focusStartY = 0f
    private var lastTapUp = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private fun inside(v: View, x: Float, y: Float): Boolean {
        if (!v.isShown) return false
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        return x >= loc[0] && x < loc[0] + v.width && y >= loc[1] && y < loc[1] + v.height
    }

    private fun edgesAt(x: Float, y: Float): Int {
        val zone = (min(box.w, box.h) * 0.2f).coerceIn(dp(12).toFloat(), dp(30).toFloat())
        var e = 0
        if (x < zone) e = e or Edge.LEFT
        if (x > box.w - zone) e = e or Edge.RIGHT
        if (y < zone) e = e or Edge.TOP
        if (y > box.h - zone) e = e or Edge.BOTTOM
        return e
    }

    private fun addRaw(ev: MotionEvent) {
        val t = vt ?: return
        val copy = MotionEvent.obtain(ev)
        copy.setLocation(ev.rawX, ev.rawY)
        t.addMovement(copy)
        copy.recycle()
    }

    private fun onDown(ev: MotionEvent) {
        animator?.cancel()
        downRawX = ev.rawX
        downRawY = ev.rawY
        startBox = box
        vt?.recycle()
        vt = VelocityTracker.obtain()
        addRaw(ev)
        val x = ev.x
        val y = ev.y
        downNoSteal = (panelOpen && inside(panel, x, y)) || (controlsActive() && inside(seek, x, y))
        downOnInteractive = downNoSteal ||
            (controlsActive() && interactive.any { inside(it, x, y) }) ||
            (audioOnly && (0 until audioBar.childCount).any { audioBar.getChildAt(it).isClickable && inside(audioBar.getChildAt(it), x, y) })
        edges = if (locked || audioOnly || downOnInteractive) 0 else edgesAt(x, y)
        g = if (edges != 0) G.RESIZE_PENDING else G.PENDING
    }

    private fun startPinch(ev: MotionEvent) {
        if (ev.pointerCount < 2) return
        g = G.PINCH
        startBox = box
        val x0 = ev.getRawX(0); val y0 = ev.getRawY(0); val x1 = ev.getRawX(1); val y1 = ev.getRawY(1)
        pinchStartSpanX = abs(x0 - x1).coerceAtLeast(1f)
        pinchStartSpanY = abs(y0 - y1).coerceAtLeast(1f)
        pinchStartSpan = hypot(x0 - x1, y0 - y1).coerceAtLeast(1f)
        focusStartX = (x0 + x1) / 2
        focusStartY = (y0 + y1) / 2
        main.removeCallbacks(singleTapRunnable)
    }

    private fun updatePinch(ev: MotionEvent) {
        val x0 = ev.getRawX(0); val y0 = ev.getRawY(0); val x1 = ev.getRawX(1); val y1 = ev.getRawY(1)
        val span = hypot(x0 - x1, y0 - y1)
        val aspect = aspectForResize(startBox)
        val s = span / pinchStartSpan
        val minAxisSpan = dp(48).toFloat()
        val sx = if (aspect != null || pinchStartSpanX < minAxisSpan) s else abs(x0 - x1) / pinchStartSpanX
        val sy = if (aspect != null || pinchStartSpanY < minAxisSpan) s else abs(y0 - y1) / pinchStartSpanY
        setBox(geom.pinch(startBox, sx, sy, focusStartX, focusStartY, (x0 + x1) / 2, (y0 + y1) / 2, aspect))
    }

    private fun gesture(ev: MotionEvent) {
        addRaw(ev)
        val dx = (ev.rawX - downRawX).roundToInt()
        val dy = (ev.rawY - downRawY).roundToInt()
        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (!locked && !audioOnly && !downNoSteal) startPinch(ev)
            MotionEvent.ACTION_MOVE -> {
                val dist = hypot((ev.rawX - downRawX), (ev.rawY - downRawY))
                if (g == G.PENDING && dist > slop && !locked) g = G.DRAG
                if (g == G.RESIZE_PENDING && dist > slop) g = G.RESIZE
                when (g) {
                    G.DRAG -> {
                        if (prefs.maximized) prefs.maximized = false
                        setBox(geom.clampForDrag(startBox.copy(x = startBox.x + dx, y = startBox.y + dy)))
                    }
                    G.RESIZE -> {
                        prefs.maximized = false
                        setBox(geom.resize(startBox, edges, dx, dy, aspectForResize(startBox)))
                    }
                    G.PINCH -> if (ev.pointerCount >= 2) { prefs.maximized = false; updatePinch(ev) }
                    else -> {}
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (g == G.PINCH) {
                // Keep following the finger that stays down.
                val keep = if (ev.actionIndex == 0) 1 else 0
                downRawX = ev.getRawX(keep)
                downRawY = ev.getRawY(keep)
                startBox = box
                g = G.DRAG
                vt?.clear()
            }
            MotionEvent.ACTION_UP -> finishGesture(ev, cancelled = false)
            MotionEvent.ACTION_CANCEL -> finishGesture(ev, cancelled = true)
        }
    }

    private fun finishGesture(ev: MotionEvent, cancelled: Boolean) {
        when (g) {
            G.PENDING, G.RESIZE_PENDING -> if (!cancelled) onTap(ev)
            G.DRAG -> {
                val t = vt
                t?.computeCurrentVelocity(1000)
                val vx = t?.xVelocity ?: 0f
                val side = if (locked) DockSide.NONE else geom.dockSideFor(box, vx)
                if (side != DockSide.NONE) dock(side)
                else animateTo(geom.clampOnScreen(box), 180) { persist(); refreshControls() }
            }
            G.RESIZE, G.PINCH -> { setBox(geom.clampOnScreen(box)); persist() }
            G.NONE -> {}
        }
        g = G.NONE
        vt?.recycle()
        vt = null
    }

    private fun onTap(ev: MotionEvent) {
        val now = SystemClock.uptimeMillis()
        if (now - lastTapUp < doubleTapTimeout && hypot(ev.rawX - lastTapX, ev.rawY - lastTapY) < dp(48)) {
            main.removeCallbacks(singleTapRunnable)
            lastTapUp = 0
            toggleMaximize()
        } else {
            lastTapUp = now
            lastTapX = ev.rawX
            lastTapY = ev.rawY
            main.postDelayed(singleTapRunnable, doubleTapTimeout)
        }
    }

    private fun onSingleTap() {
        when {
            panelOpen -> closePanel()
            audioOnly -> {}
            controlsVisible -> hideControls()
            else -> showControls()
        }
    }

    inner class RootLayout : FrameLayout(ctx) {
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    onDown(ev)
                    return !downOnInteractive
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (!downNoSteal && !locked && !audioOnly) {
                    startPinch(ev)
                    return true
                }
                MotionEvent.ACTION_MOVE -> if (!downNoSteal && downOnInteractive && !locked &&
                    hypot(ev.rawX - downRawX, ev.rawY - downRawY) > slop
                ) {
                    // A drag that started on a button moves the window instead.
                    g = G.DRAG
                    return true
                }
            }
            return false
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && g == G.NONE) onDown(ev)
            gesture(ev)
            return true
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            post { layoutForSize(w, h) }
        }
    }
}
