package app.touchbot

import org.json.JSONObject
import java.io.File

/** Everything TouchBot knows about one game, keyed by its Android package name. */
data class GameProfile(
    val pkg: String,
    val label: String,
    /** Play guide written by the AI after researching the game online. */
    val guide: String = "",
    val threshold: Float = Trainer.DEFAULT_THRESHOLD,
    val moves: Int = 0,
    val moments: Int = 0,
    /** Leave-one-out match percentage, or -1 when not measured yet. */
    val agreement: Int = -1,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("pkg", pkg).put("label", label).put("guide", guide)
        .put("threshold", threshold.toDouble()).put("moves", moves)
        .put("moments", moments).put("agreement", agreement)

    companion object {
        fun fromJson(o: JSONObject) = GameProfile(
            pkg = o.getString("pkg"),
            label = o.optString("label", o.getString("pkg")),
            guide = o.optString("guide", ""),
            threshold = o.optDouble("threshold", Trainer.DEFAULT_THRESHOLD.toDouble()).toFloat(),
            moves = o.optInt("moves", 0),
            moments = o.optInt("moments", 0),
            agreement = o.optInt("agreement", -1),
        )
    }
}

class GameStore(private val root: File) {
    init { root.mkdirs() }

    private fun dirFor(pkg: String) = File(root, pkg.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "game" })

    fun load(pkg: String): GameProfile? = try {
        val f = File(dirFor(pkg), "profile.json")
        if (f.exists()) GameProfile.fromJson(JSONObject(f.readText())) else null
    } catch (e: Exception) {
        null
    }

    fun save(p: GameProfile) {
        val dir = dirFor(p.pkg).apply { mkdirs() }
        File(dir, "profile.json").writeText(p.toJson().toString())
    }

    fun list(): List<GameProfile> =
        (root.listFiles { f -> f.isDirectory } ?: emptyArray())
            .sortedByDescending { File(it, "profile.json").lastModified() }
            .mapNotNull { d ->
                try { GameProfile.fromJson(JSONObject(File(d, "profile.json").readText())) } catch (e: Exception) { null }
            }

    fun delete(pkg: String) {
        dirFor(pkg).deleteRecursively()
    }

    fun loadSamples(pkg: String): List<Sample> {
        val f = File(dirFor(pkg), "samples.bin")
        if (!f.exists()) return emptyList()
        return f.inputStream().buffered().use { SampleCodec.read(it) }
    }

    fun saveSamples(pkg: String, samples: List<Sample>) {
        val dir = dirFor(pkg).apply { mkdirs() }
        val tmp = File(dir, "samples.tmp")
        tmp.outputStream().buffered().use { SampleCodec.write(it, samples) }
        val dst = File(dir, "samples.bin")
        if (!tmp.renameTo(dst)) { tmp.copyTo(dst, overwrite = true); tmp.delete() }
    }
}
