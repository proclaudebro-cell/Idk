package app.touchbot

import android.content.Context
import org.json.JSONObject
import java.io.File

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
    /** Package of the game most recently watched or studied. */
    var game: String
        get() = sp.getString("game", "") ?: ""
        set(v) = sp.edit().putString("game", v).apply()
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
