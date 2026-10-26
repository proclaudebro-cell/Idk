package app.touchbot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A single automation step. [delay] is how long to wait before running it.
 *
 * Types: gesture (uses [points]), tap, long_press, swipe, text, enter, back, home,
 * recents, open_app (package in [text]), wait.
 */
data class Action(
    val type: String,
    val delay: Long = 0,
    val x: Float = 0f,
    val y: Float = 0f,
    val x2: Float = 0f,
    val y2: Float = 0f,
    val duration: Long = 0,
    val text: String? = null,
    val points: List<Pt>? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("delay", delay)
        when (type) {
            "tap" -> { put("x", x.toDouble()); put("y", y.toDouble()) }
            "double_tap" -> { put("x", x.toDouble()); put("y", y.toDouble()); put("duration", duration) }
            "long_press" -> { put("x", x.toDouble()); put("y", y.toDouble()); put("duration", duration) }
            "swipe" -> {
                put("x", x.toDouble()); put("y", y.toDouble())
                put("x2", x2.toDouble()); put("y2", y2.toDouble()); put("duration", duration)
            }
            "wait" -> put("duration", duration)
            "text", "open_app" -> put("text", text ?: "")
            "gesture" -> {
                put("duration", duration)
                val arr = JSONArray()
                points?.forEach { p ->
                    arr.put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble()).put(p.t))
                }
                put("points", arr)
            }
        }
    }

    companion object {
        fun fromJson(o: JSONObject): Action {
            val pts = o.optJSONArray("points")?.let { arr ->
                (0 until arr.length()).map {
                    val p = arr.getJSONArray(it)
                    Pt(p.getDouble(0).toFloat(), p.getDouble(1).toFloat(), p.getLong(2))
                }
            }
            return Action(
                type = o.getString("type"),
                delay = o.optLong("delay", 0),
                x = o.optDouble("x", 0.0).toFloat(),
                y = o.optDouble("y", 0.0).toFloat(),
                x2 = o.optDouble("x2", 0.0).toFloat(),
                y2 = o.optDouble("y2", 0.0).toFloat(),
                duration = o.optLong("duration", 0),
                text = if (o.has("text")) o.getString("text") else null,
                points = pts,
            )
        }
    }
}

/** A saved automation, with the screen size it was recorded on so it can be rescaled. */
data class Recording(
    val name: String,
    val screenW: Int,
    val screenH: Int,
    val actions: List<Action>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("screenW", screenW)
        put("screenH", screenH)
        put("actions", JSONArray().also { arr -> actions.forEach { arr.put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): Recording {
            val arr = o.getJSONArray("actions")
            return Recording(
                name = o.getString("name"),
                screenW = o.getInt("screenW"),
                screenH = o.getInt("screenH"),
                actions = (0 until arr.length()).map { Action.fromJson(arr.getJSONObject(it)) },
            )
        }
    }
}

class RecordingStore(context: Context) {
    private val dir = File(context.filesDir, "recordings").apply { mkdirs() }

    private fun fileFor(name: String) =
        File(dir, name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifEmpty { "recording" } + ".json")

    fun save(rec: Recording) {
        fileFor(rec.name).writeText(rec.toJson().toString())
    }

    fun load(name: String): Recording? = try {
        val f = fileFor(name)
        if (f.exists()) Recording.fromJson(JSONObject(f.readText())) else null
    } catch (e: Exception) {
        null
    }

    fun list(): List<Recording> =
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .mapNotNull { f ->
                try { Recording.fromJson(JSONObject(f.readText())) } catch (e: Exception) { null }
            }

    fun delete(name: String) {
        fileFor(name).delete()
    }

    fun rename(old: String, new: String): Boolean {
        val rec = load(old) ?: return false
        if (fileFor(new).exists() && fileFor(new) != fileFor(old)) return false
        delete(old)
        save(rec.copy(name = new))
        return true
    }
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("touchbot", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) = sp.edit().putString("apiKey", v.trim()).apply()
    var model: String
        get() = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("model", v.trim().ifEmpty { DEFAULT_MODEL }).apply()
    var loops: Int
        get() = sp.getInt("loops", 1)
        set(v) = sp.edit().putInt("loops", v).apply()
    var speed: Float
        get() = sp.getFloat("speed", 1f)
        set(v) = sp.edit().putFloat("speed", v).apply()
    var selected: String
        get() = sp.getString("selected", "") ?: ""
        set(v) = sp.edit().putString("selected", v).apply()
    var lastGoal: String
        get() = sp.getString("lastGoal", "") ?: ""
        set(v) = sp.edit().putString("lastGoal", v).apply()
    var panelX: Int
        get() = sp.getInt("panelX", 24)
        set(v) = sp.edit().putInt("panelX", v).apply()
    var panelY: Int
        get() = sp.getInt("panelY", 300)
        set(v) = sp.edit().putInt("panelY", v).apply()

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
    }
}
