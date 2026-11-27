package app.floattok.data

import android.content.Context
import android.content.SharedPreferences
import app.floattok.overlay.Box
import app.floattok.playback.RepeatMode

/** How the video fills the floating window. */
enum class VideoMode { ORIGINAL, FIT, CROP }

/** All user settings and remembered window/playback state. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.applicationContext.getSharedPreferences("floattok", Context.MODE_PRIVATE)

    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun putBool(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    var autoplay: Boolean
        get() = bool("autoplay", true)
        set(v) = putBool("autoplay", v)

    var shuffle: Boolean
        get() = bool("shuffle", false)
        set(v) = putBool("shuffle", v)

    var repeat: RepeatMode
        get() = RepeatMode.entries.getOrElse(sp.getInt("repeat", 0)) { RepeatMode.OFF }
        set(v) = sp.edit().putInt("repeat", v.ordinal).apply()

    var speed: Float
        get() = sp.getFloat("speed", 1f).coerceIn(0.5f, 2f)
        set(v) = sp.edit().putFloat("speed", v.coerceIn(0.5f, 2f)).apply()

    /** Floating window opacity, 0.2..1. */
    var opacity: Float
        get() = sp.getFloat("opacity", 1f).coerceIn(0.2f, 1f)
        set(v) = sp.edit().putFloat("opacity", v.coerceIn(0.2f, 1f)).apply()

    var muted: Boolean
        get() = bool("muted", false)
        set(v) = putBool("muted", v)

    /** true = keep playing audio while docked, false = pause while docked. */
    var audioWhileDocked: Boolean
        get() = bool("audio_docked", true)
        set(v) = putBool("audio_docked", v)

    var aspectLock: Boolean
        get() = bool("aspect_lock", true)
        set(v) = putBool("aspect_lock", v)

    var videoMode: VideoMode
        get() = VideoMode.entries.getOrElse(sp.getInt("video_mode", 0)) { VideoMode.ORIGINAL }
        set(v) = sp.edit().putInt("video_mode", v.ordinal).apply()

    var positionLocked: Boolean
        get() = bool("pos_locked", false)
        set(v) = putBool("pos_locked", v)

    var audioOnly: Boolean
        get() = bool("audio_only", false)
        set(v) = putBool("audio_only", v)

    var resumeLast: Boolean
        get() = bool("resume_last", true)
        set(v) = putBool("resume_last", v)

    var controlsTimeoutMs: Int
        get() = sp.getInt("controls_timeout", 3000).coerceIn(1500, 10000)
        set(v) = sp.edit().putInt("controls_timeout", v).apply()

    /** 0 = TikTok player loaded directly (more control), 1 = TikTok player in an iframe (documented API only). */
    var tiktokEmbedMode: Int
        get() = sp.getInt("tt_mode", 0)
        set(v) = sp.edit().putInt("tt_mode", v).apply()

    // ---------------------------------------------------------------- window state

    private fun boxKey(prefix: String, landscape: Boolean) = prefix + if (landscape) "_land" else "_port"

    fun windowBox(landscape: Boolean): Box? = readBox(boxKey("win", landscape))
    fun saveWindowBox(landscape: Boolean, b: Box) = writeBox(boxKey("win", landscape), b)

    /** Size/position to go back to when leaving maximized. */
    fun restoreBox(landscape: Boolean): Box? = readBox(boxKey("restore", landscape))
    fun saveRestoreBox(landscape: Boolean, b: Box?) = if (b == null) sp.edit().remove(boxKey("restore", landscape)).apply() else writeBox(boxKey("restore", landscape), b)

    var maximized: Boolean
        get() = bool("maximized", false)
        set(v) = putBool("maximized", v)

    /** DockSide value; -1 when not docked. */
    var dockSide: Int
        get() = sp.getInt("dock_side", -1)
        set(v) = sp.edit().putInt("dock_side", v).apply()

    /** Vertical tab position as a fraction of screen height. */
    var dockTabFraction: Float
        get() = sp.getFloat("dock_tab_y", 0.4f)
        set(v) = sp.edit().putFloat("dock_tab_y", v.coerceIn(0f, 1f)).apply()

    fun resetWindow() {
        sp.edit().remove("win_land").remove("win_port").remove("restore_land").remove("restore_port")
            .remove("maximized").remove("dock_side").apply()
    }

    private fun readBox(k: String): Box? {
        val s = sp.getString(k, null) ?: return null
        val p = s.split(',').mapNotNull { it.toIntOrNull() }
        return if (p.size == 4 && p[2] > 0 && p[3] > 0) Box(p[0], p[1], p[2], p[3]) else null
    }

    private fun writeBox(k: String, b: Box) = sp.edit().putString(k, "${b.x},${b.y},${b.w},${b.h}").apply()

    // ---------------------------------------------------------------- playback state

    var lastVideoRowId: Long
        get() = sp.getLong("last_video", -1)
        set(v) = sp.edit().putLong("last_video", v).apply()

    var lastPositionMs: Long
        get() = sp.getLong("last_pos", 0)
        set(v) = sp.edit().putLong("last_pos", v).apply()

    fun saveQueueOrder(order: IntArray, pos: Int) {
        sp.edit().putString("q_order", order.joinToString(",")).putInt("q_pos", pos).apply()
    }

    fun queueOrder(): Pair<IntArray, Int>? {
        val s = sp.getString("q_order", null) ?: return null
        val arr = if (s.isEmpty()) IntArray(0) else s.split(',').mapNotNull { it.toIntOrNull() }.toIntArray()
        return Pair(arr, sp.getInt("q_pos", 0))
    }

    var onboardingDone: Boolean
        get() = bool("onboarding_done", false)
        set(v) = putBool("onboarding_done", v)
}
