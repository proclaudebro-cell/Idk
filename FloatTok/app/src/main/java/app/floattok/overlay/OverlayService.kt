package app.floattok.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import app.floattok.R
import app.floattok.data.ImageLoader
import app.floattok.data.Prefs
import app.floattok.data.VideoItem
import app.floattok.playback.PlaybackState
import app.floattok.playback.PlayerController
import app.floattok.ui.MainActivity
import java.util.concurrent.Executors
import android.media.session.PlaybackState as SessionState

/**
 * Foreground service (type mediaPlayback) that owns the floating windows, the player and the media session.
 * Started from the app while it is visible, which also gives it the "while in use" capability Android 17 needs
 * for background audio. Closing the player stops the service completely.
 */
class OverlayService : Service(), FloatingPlayerWindow.Host, PlayerController.Observer {

    companion object {
        const val ACTION_SHOW = "app.floattok.action.SHOW"
        const val ACTION_TOGGLE = "app.floattok.action.TOGGLE"
        const val ACTION_NEXT = "app.floattok.action.NEXT"
        const val ACTION_PREV = "app.floattok.action.PREV"
        const val ACTION_CLOSE = "app.floattok.action.CLOSE"
        const val ACTION_RESTORE = "app.floattok.action.RESTORE"
        const val EXTRA_IDS = "ids"
        const val EXTRA_INDEX = "index"
        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 17

        @Volatile var instance: OverlayService? = null
            private set

        /** Opens the floating player. With [ids] it replaces the queue and plays [index]; without, it resumes. */
        fun start(context: Context, ids: List<Long>? = null, index: Int = 0) {
            val i = Intent(context, OverlayService::class.java).setAction(ACTION_SHOW)
            if (!ids.isNullOrEmpty()) i.putExtra(EXTRA_IDS, ids.toLongArray()).putExtra(EXTRA_INDEX, index)
            context.startForegroundService(i)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val artExec = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs
    private lateinit var viewCtx: Context
    private lateinit var wm: WindowManager
    lateinit var controller: PlayerController
        private set
    private var window: FloatingPlayerWindow? = null
    private var tab: DockTab? = null
    private lateinit var session: MediaSession
    private var geo: WindowGeometry? = null
    private var closing = false
    private var art: Bitmap? = null
    private var artFor = -1L
    private var lastNotifKey = ""
    private var lastSessionKey = ""
    private var lastSessionPosUpdate = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        // A window context is the supported way for a Service to add overlay windows on Android 11+.
        val windowContext: Context = try {
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } catch (_: Exception) {
            this
        }
        viewCtx = ContextThemeWrapper(windowContext, R.style.Theme_FloatTok)
        wm = windowContext.getSystemService(WindowManager::class.java)
        controller = PlayerController(viewCtx, prefs)
        controller.addObserver(this)
        controller.restoreQueue()
        createChannel()
        session = MediaSession(this, "FloatTok").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = this@OverlayService.controller.play()
                override fun onPause() = this@OverlayService.controller.pause()
                override fun onSkipToNext() = this@OverlayService.controller.next()
                override fun onSkipToPrevious() = this@OverlayService.controller.previous()
                override fun onSeekTo(pos: Long) = this@OverlayService.controller.seekTo(pos)
                override fun onStop() = closeAll()
                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    if (action == "close") closeAll()
                }
            })
            setSessionActivity(openAppIntent())
            isActive = true
        }
        PlaybackState.running = true
        PlaybackState.changed()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must be called promptly after startForegroundService().
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        if (closing) return START_NOT_STICKY
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "FloatTok needs the 'Display over other apps' permission", Toast.LENGTH_LONG).show()
            closeAll()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_SHOW -> {
                ensureWindow()
                val ids = intent.getLongArrayExtra(EXTRA_IDS)
                if (ids != null && ids.isNotEmpty()) controller.setQueue(ids.toList(), intent.getIntExtra(EXTRA_INDEX, 0))
                else if (controller.current == null) controller.resumeLast()
                window?.let { if (it.docked) it.undock() else it.showControls() }
            }
            ACTION_TOGGLE -> controller.togglePlay()
            ACTION_NEXT -> controller.next()
            ACTION_PREV -> controller.previous()
            ACTION_RESTORE -> window?.undock()
            ACTION_CLOSE -> closeAll()
            else -> if (window == null) closeAll()
        }
        return START_NOT_STICKY
    }

    private fun ensureWindow() {
        if (window != null) return
        val g = geometry(refresh = true)
        val land = isLandscape
        val aspect = controller.videoAspect
        prefs.dockSide = DockSide.NONE
        val saved = prefs.windowBox(land)
        val initial = when {
            prefs.maximized -> g.maximized(aspect, (6 * resources.displayMetrics.density).toInt())
            saved != null -> {
                val (w, h) = g.clampSize(saved.w, saved.h, null)
                g.clampOnScreen(saved.copy(w = w, h = h))
            }
            else -> g.defaultBox(aspect)
        }
        tab = DockTab(viewCtx, wm, { geometry() }, prefs) { window?.undock() }
        window = FloatingPlayerWindow(viewCtx, wm, prefs, controller, this).also { it.show(initial) }
    }

    /** Called by the app after settings or the queue change. */
    fun onSettingsChanged() {
        controller.applySettings()
        window?.applySettings()
        updateNotification(force = true)
    }

    fun onQueueEdited() = controller.reloadQueueFromStorage()

    fun closeAll() {
        if (closing) return
        closing = true
        controller.savePosition(force = true)
        window?.remove()
        window = null
        tab?.hide()
        tab = null
        controller.removeObserver(this)
        controller.release()
        session.isActive = false
        session.release()
        artExec.shutdownNow()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        PlaybackState.running = false
        PlaybackState.playing = false
        PlaybackState.changed()
    }

    override fun onDestroy() {
        if (!closing) closeAll()
        instance = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- geometry / rotation

    private fun geometry(refresh: Boolean = false): WindowGeometry {
        val cached = geo
        if (cached != null && !refresh) return cached
        val m = wm.currentWindowMetrics
        val b = m.bounds
        val ins = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return WindowGeometry(b.width(), b.height(), ins.left, ins.top, ins.right, ins.bottom, resources.displayMetrics.density)
            .also { geo = it }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Metrics settle slightly after the configuration callback.
        main.postDelayed({ checkGeometry() }, 120)
        main.postDelayed({ checkGeometry() }, 600)
    }

    private fun checkGeometry() {
        if (closing) return
        val old = geo ?: return
        val new = geometry(refresh = true)
        if (old.screenW != new.screenW || old.screenH != new.screenH) window?.onGeometryChanged(old, new)
    }

    // ---------------------------------------------------------------- Host

    override val geometry: WindowGeometry get() = geometry()
    override val isLandscape: Boolean get() = geometry().let { it.screenW > it.screenH }
    override fun closePlayer() = closeAll()

    override fun openApp(settings: Boolean) {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (settings) i.putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SETTINGS)
        try { startActivity(i) } catch (_: Exception) {
            try { openAppIntent().send() } catch (_: Exception) {}
        }
    }

    override fun showTab(side: Int, centerY: Int) {
        tab?.setPlaying(controller.playing)
        tab?.show(side, centerY)
    }

    override fun hideTab() { tab?.hide() }
    override fun tabCenterY(): Int? = tab?.centerY()

    override fun onUserSettingChanged() {
        updateNotification(force = true)
        PlaybackState.changed()
    }

    // ---------------------------------------------------------------- Observer → session/notification

    override fun onStateChanged() {
        if (closing) return
        tab?.setPlaying(controller.playing)
        updateSession()
        updateNotification(force = false)
    }

    override fun onItemChanged(item: VideoItem?) {
        if (item == null || item.id == artFor) return
        artFor = item.id
        art = null
        val size = (96 * resources.displayMetrics.density).toInt()
        if (!artExec.isShutdown) artExec.execute {
            val bmp = ImageLoader.loadBlocking(this, item, size)
            main.post { if (artFor == item.id && !closing) { art = bmp; updateSession(force = true); updateNotification(force = true) } }
        }
    }

    private fun updateSession(force: Boolean = false) {
        val item = controller.current
        val key = "${item?.id}|${item?.title}|${controller.playing}|${controller.buffering}|${controller.durationMs}|${art != null}"
        val now = System.currentTimeMillis()
        if (!force && key == lastSessionKey && now - lastSessionPosUpdate < 5000) return
        lastSessionPosUpdate = now
        if (key != lastSessionKey || force) {
            session.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, item?.displayTitle() ?: "FloatTok")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, item?.author?.let { "@$it" } ?: "FloatTok")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, controller.durationMs)
                .apply { art?.let { putBitmap(MediaMetadata.METADATA_KEY_ART, it) } }
                .build())
        }
        lastSessionKey = key
        val state = when {
            controller.playing -> SessionState.STATE_PLAYING
            controller.buffering && item != null -> SessionState.STATE_BUFFERING
            else -> SessionState.STATE_PAUSED
        }
        session.setPlaybackState(SessionState.Builder()
            .setActions(SessionState.ACTION_PLAY or SessionState.ACTION_PAUSE or SessionState.ACTION_PLAY_PAUSE or
                SessionState.ACTION_SKIP_TO_NEXT or SessionState.ACTION_SKIP_TO_PREVIOUS or SessionState.ACTION_SEEK_TO or
                SessionState.ACTION_STOP)
            .setState(state, controller.positionMs, if (controller.playing) prefs.speed else 0f)
            .addCustomAction(SessionState.CustomAction.Builder("close", "Close", R.drawable.ic_close).build())
            .build())
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL, getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            description = "Controls for the floating player"
        }
        nm.createNotificationChannel(ch)
    }

    private fun servicePi(action: String, req: Int) = PendingIntent.getService(
        this, req, Intent(this, OverlayService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openAppIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(icon: Int, title: String, pi: PendingIntent) =
        Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build()

    private fun buildNotification(): Notification {
        val item = if (::controller.isInitialized) controller.current else null
        val playing = ::controller.isInitialized && controller.playing
        val text = when {
            item?.author != null -> "@${item.author}" + if (prefs.autoplay) "  ·  Autoplay on" else ""
            item != null -> if (prefs.autoplay) "Autoplay on" else "Autoplay off"
            else -> "Floating player"
        }
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(item?.displayTitle() ?: "FloatTok")
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openAppIntent())
            .addAction(action(R.drawable.ic_prev, "Previous", servicePi(ACTION_PREV, 1)))
            .addAction(action(if (playing) R.drawable.ic_pause else R.drawable.ic_play, if (playing) "Pause" else "Play", servicePi(ACTION_TOGGLE, 2)))
            .addAction(action(R.drawable.ic_next, "Next", servicePi(ACTION_NEXT, 3)))
            .addAction(action(R.drawable.ic_close, "Close", servicePi(ACTION_CLOSE, 4)))
        if (::session.isInitialized) {
            b.setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
        }
        art?.let { b.setLargeIcon(it) }
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    private fun updateNotification(force: Boolean) {
        if (closing) return
        val item = controller.current
        val key = "${item?.id}|${item?.title}|${controller.playing}|${prefs.autoplay}|${art != null}"
        if (!force && key == lastNotifKey) return
        lastNotifKey = key
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }
}
