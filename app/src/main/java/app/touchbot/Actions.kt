package app.touchbot

import org.json.JSONArray
import org.json.JSONObject

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
