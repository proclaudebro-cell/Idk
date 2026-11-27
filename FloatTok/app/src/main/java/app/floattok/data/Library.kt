package app.floattok.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList

object Source {
    const val EXPORT = "export"
    const val SHARE = "share"
    const val WEB = "web"
    const val PASTE = "paste"
    const val LOCAL = "local"

    fun label(s: String) = when (s) {
        EXPORT -> "Data export"
        SHARE -> "Shared"
        WEB -> "TikTok web"
        PASTE -> "Pasted link"
        LOCAL -> "Local file"
        else -> s
    }
}

object Kind {
    const val TIKTOK = "tiktok"
    const val TIKTOK_SHORT = "short"   // short link not yet resolved to a video id
    const val TIKTOK_PHOTO = "photo"
    const val LOCAL = "local"
    const val URL = "url"              // direct media URL (mp4/m3u8)
}

object MetaState {
    const val PENDING = 0
    const val OK = 1
    const val FAILED = 2
    const val UNAVAILABLE = 3
}

data class VideoItem(
    val id: Long,
    val key: String,
    val kind: String,
    val videoId: String?,
    val url: String,
    val title: String?,
    val author: String?,
    val thumbUrl: String?,
    val source: String,
    val collection: String?,
    val favDate: String?,
    val addedAt: Long,
    val durationMs: Long,
    val lastPosMs: Long,
    val metaState: Int,
) {
    val isTikTok get() = kind == Kind.TIKTOK || kind == Kind.TIKTOK_SHORT || kind == Kind.TIKTOK_PHOTO

    fun displayTitle(): String = title?.takeIf { it.isNotBlank() }?.trim()?.replace(Regex("\\s+"), " ") ?: when (kind) {
        Kind.LOCAL -> "Local video"
        Kind.URL -> url.substringAfterLast('/').substringBefore('?').ifBlank { "Video" }
        Kind.TIKTOK_SHORT -> "TikTok link"
        Kind.TIKTOK_PHOTO -> "TikTok photo post ${videoId ?: ""}".trim()
        else -> "TikTok video ${videoId ?: ""}".trim()
    }
}

class Library private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "floattok.db", null, 1) {

    companion object {
        @Volatile private var instance: Library? = null
        fun get(context: Context): Library =
            instance ?: synchronized(this) { instance ?: Library(context).also { instance = it } }
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun changed() { main.post { listeners.forEach { it() } } }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE videos (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                key TEXT NOT NULL UNIQUE,
                kind TEXT NOT NULL,
                video_id TEXT,
                url TEXT NOT NULL,
                title TEXT,
                author TEXT,
                thumb_url TEXT,
                source TEXT NOT NULL,
                collection TEXT,
                fav_date TEXT,
                added_at INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                last_pos_ms INTEGER NOT NULL DEFAULT 0,
                meta_state INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE INDEX idx_videos_added ON videos(added_at DESC, id)")
        db.execSQL("CREATE TABLE queue (pos INTEGER PRIMARY KEY, video INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    private fun row(c: Cursor) = VideoItem(
        id = c.getLong(0), key = c.getString(1), kind = c.getString(2), videoId = c.getString(3),
        url = c.getString(4), title = c.getString(5), author = c.getString(6), thumbUrl = c.getString(7),
        source = c.getString(8), collection = c.getString(9), favDate = c.getString(10), addedAt = c.getLong(11),
        durationMs = c.getLong(12), lastPosMs = c.getLong(13), metaState = c.getInt(14),
    )

    private val cols = "id,key,kind,video_id,url,title,author,thumb_url,source,collection,fav_date,added_at,duration_ms,last_pos_ms,meta_state"

    // ---------------------------------------------------------------- adding

    data class AddResult(val id: Long, val isNew: Boolean)

    private fun kindFor(p: LinkParser.Parsed) = when (p.kind) {
        LinkParser.Kind.TIKTOK -> Kind.TIKTOK
        LinkParser.Kind.TIKTOK_SHORT -> Kind.TIKTOK_SHORT
        LinkParser.Kind.TIKTOK_PHOTO -> Kind.TIKTOK_PHOTO
        LinkParser.Kind.DIRECT_MEDIA -> Kind.URL
        LinkParser.Kind.OTHER -> Kind.URL
    }

    private fun addInternal(db: SQLiteDatabase, p: LinkParser.Parsed, source: String, collection: String?,
                            favDate: String?, title: String?, thumb: String?, addedAt: Long): AddResult {
        val key = LinkParser.keyFor(p)
        val existing = db.rawQuery("SELECT id, collection, title, thumb_url FROM videos WHERE key=?", arrayOf(key)).use {
            if (it.moveToFirst()) arrayOf(it.getLong(0).toString(), it.getString(1), it.getString(2), it.getString(3)) else null
        }
        if (existing != null) {
            val id = existing[0]!!.toLong()
            val cv = ContentValues()
            if (existing[1] == null && collection != null) cv.put("collection", collection)
            if (existing[2] == null && title != null) cv.put("title", title)
            if (existing[3] == null && thumb != null) cv.put("thumb_url", thumb)
            if (cv.size() > 0) db.update("videos", cv, "id=?", arrayOf(id.toString()))
            return AddResult(id, false)
        }
        val cv = ContentValues().apply {
            put("key", key)
            put("kind", kindFor(p))
            put("video_id", p.videoId)
            put("url", p.url)
            put("title", title)
            put("thumb_url", thumb)
            put("source", source)
            put("collection", collection)
            put("fav_date", favDate)
            put("added_at", addedAt)
            put("meta_state", if (p.kind == LinkParser.Kind.DIRECT_MEDIA) MetaState.OK else MetaState.PENDING)
        }
        return AddResult(db.insertOrThrow("videos", null, cv), true)
    }

    fun add(p: LinkParser.Parsed, source: String, collection: String? = null, favDate: String? = null,
            title: String? = null, thumb: String? = null): AddResult {
        val r = addInternal(writableDatabase, p, source, collection, favDate, title, thumb, System.currentTimeMillis())
        changed()
        return r
    }

    data class BulkEntry(val parsed: LinkParser.Parsed, val collection: String?, val favDate: String?,
                         val title: String? = null, val thumb: String? = null)

    /** Adds many entries in one transaction. Entries keep their given order (first = shown first). */
    fun addAll(entries: List<BulkEntry>, source: String): Pair<Int, Int> {
        val db = writableDatabase
        var added = 0
        var dup = 0
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            for (e in entries) {
                if (addInternal(db, e.parsed, source, e.collection, e.favDate, e.title, e.thumb, now).isNew) added++ else dup++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed()
        return Pair(added, dup)
    }

    fun addLocal(uri: String, title: String?): AddResult {
        val db = writableDatabase
        val key = "local:$uri"
        db.rawQuery("SELECT id FROM videos WHERE key=?", arrayOf(key)).use {
            if (it.moveToFirst()) return AddResult(it.getLong(0), false)
        }
        val cv = ContentValues().apply {
            put("key", key); put("kind", Kind.LOCAL); put("url", uri); put("title", title)
            put("source", Source.LOCAL); put("added_at", System.currentTimeMillis()); put("meta_state", MetaState.OK)
        }
        val id = db.insertOrThrow("videos", null, cv)
        changed()
        return AddResult(id, true)
    }

    // ---------------------------------------------------------------- reading

    fun query(search: String? = null, source: String? = null, collection: String? = null): List<VideoItem> {
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        if (!search.isNullOrBlank()) {
            where.add("(title LIKE ? OR author LIKE ? OR collection LIKE ? OR video_id LIKE ? OR url LIKE ?)")
            val q = "%" + search.trim().replace("%", "").replace("_", "") + "%"
            repeat(5) { args.add(q) }
        }
        if (source != null) { where.add("source=?"); args.add(source) }
        if (collection != null) { where.add("collection=?"); args.add(collection) }
        val sql = "SELECT $cols FROM videos" + (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) +
            " ORDER BY added_at DESC, id ASC"
        return readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            val out = ArrayList<VideoItem>(c.count)
            while (c.moveToNext()) out.add(row(c))
            out
        }
    }

    fun get(id: Long): VideoItem? =
        readableDatabase.rawQuery("SELECT $cols FROM videos WHERE id=?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) row(it) else null
        }

    fun getMany(ids: Collection<Long>): Map<Long, VideoItem> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Long, VideoItem>(ids.size)
        ids.chunked(500).forEach { chunk ->
            readableDatabase.rawQuery("SELECT $cols FROM videos WHERE id IN (${chunk.joinToString(",")})", null).use { c ->
                while (c.moveToNext()) row(c).let { out[it.id] = it }
            }
        }
        return out
    }

    fun findByKey(key: String): VideoItem? =
        readableDatabase.rawQuery("SELECT $cols FROM videos WHERE key=?", arrayOf(key)).use {
            if (it.moveToFirst()) row(it) else null
        }

    fun collections(): List<String> =
        readableDatabase.rawQuery("SELECT DISTINCT collection FROM videos WHERE collection IS NOT NULL ORDER BY collection COLLATE NOCASE", null).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out.add(c.getString(0))
            out
        }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM videos", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun pendingMeta(limit: Int): List<VideoItem> =
        readableDatabase.rawQuery(
            "SELECT $cols FROM videos WHERE meta_state=? AND kind IN (?,?,?) ORDER BY added_at DESC, id ASC LIMIT $limit",
            arrayOf(MetaState.PENDING.toString(), Kind.TIKTOK, Kind.TIKTOK_SHORT, Kind.TIKTOK_PHOTO)
        ).use { c ->
            val out = ArrayList<VideoItem>()
            while (c.moveToNext()) out.add(row(c))
            out
        }

    // ---------------------------------------------------------------- updating

    fun updateMeta(id: Long, title: String?, author: String?, thumb: String?, state: Int) {
        val cv = ContentValues().apply {
            if (title != null) put("title", title)
            if (author != null) put("author", author)
            if (thumb != null) put("thumb_url", thumb)
            put("meta_state", state)
        }
        writableDatabase.update("videos", cv, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun setMetaState(id: Long, state: Int) {
        writableDatabase.update("videos", ContentValues().apply { put("meta_state", state) }, "id=?", arrayOf(id.toString()))
    }

    /**
     * A short link turned out to be video [videoId]. If that video is already in the library the short-link row is
     * merged into it (queue references move too) and the surviving id is returned.
     */
    fun resolveShortLink(id: Long, videoId: String, canonicalUrl: String): Long {
        val db = writableDatabase
        val key = LinkParser.tiktokKey(videoId)
        db.beginTransaction()
        try {
            val existing = db.rawQuery("SELECT id FROM videos WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getLong(0) else null }
            val result = if (existing != null && existing != id) {
                db.execSQL("UPDATE queue SET video=? WHERE video=?", arrayOf(existing, id))
                db.delete("videos", "id=?", arrayOf(id.toString()))
                existing
            } else {
                val cv = ContentValues().apply {
                    put("key", key); put("kind", Kind.TIKTOK); put("video_id", videoId); put("url", canonicalUrl)
                }
                db.update("videos", cv, "id=?", arrayOf(id.toString()))
                id
            }
            db.setTransactionSuccessful()
            changed()
            return result
        } finally {
            db.endTransaction()
        }
    }

    fun setCollection(id: Long, name: String?) {
        writableDatabase.update("videos", ContentValues().apply { put("collection", name?.trim()?.ifBlank { null }) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun savePosition(id: Long, posMs: Long, durationMs: Long) {
        val cv = ContentValues().apply {
            put("last_pos_ms", posMs.coerceAtLeast(0))
            if (durationMs > 0) put("duration_ms", durationMs)
        }
        writableDatabase.update("videos", cv, "id=?", arrayOf(id.toString()))
    }

    fun delete(id: Long) {
        val db = writableDatabase
        db.delete("videos", "id=?", arrayOf(id.toString()))
        db.delete("queue", "video=?", arrayOf(id.toString()))
        changed()
    }

    fun deleteAll() {
        writableDatabase.execSQL("DELETE FROM videos")
        writableDatabase.execSQL("DELETE FROM queue")
        changed()
    }

    // ---------------------------------------------------------------- queue

    fun queueIds(): List<Long> = readableDatabase.rawQuery(
        "SELECT q.video FROM queue q JOIN videos v ON v.id = q.video ORDER BY q.pos", null
    ).use { c ->
        val out = ArrayList<Long>(c.count)
        while (c.moveToNext()) out.add(c.getLong(0))
        out
    }

    fun saveQueue(ids: List<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM queue")
            val st = db.compileStatement("INSERT INTO queue(pos, video) VALUES (?, ?)")
            ids.forEachIndexed { i, id -> st.bindLong(1, i.toLong()); st.bindLong(2, id); st.executeInsert(); st.clearBindings() }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------------------------------------------------------------- backup

    fun exportBackup(out: OutputStream) {
        val arr = JSONArray()
        for (v in query()) {
            arr.put(JSONObject().apply {
                put("key", v.key); put("kind", v.kind); put("video_id", v.videoId); put("url", v.url)
                put("title", v.title); put("author", v.author); put("source", v.source)
                put("collection", v.collection); put("fav_date", v.favDate); put("added_at", v.addedAt)
            })
        }
        val root = JSONObject().put("app", "FloatTok").put("version", 1).put("videos", arr).put("queue", JSONArray(queueIds().mapNotNull { get(it)?.key }))
        out.write(root.toString().toByteArray(Charsets.UTF_8))
    }

    /** Returns (added, duplicates). Local-file entries are skipped: their permissions don't survive a reinstall. */
    fun importBackup(input: InputStream): Pair<Int, Int> {
        val root = JSONObject(String(input.readBytes(), Charsets.UTF_8))
        val arr = root.getJSONArray("videos")
        val db = writableDatabase
        var added = 0
        var dup = 0
        db.beginTransaction()
        try {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val kind = o.optString("kind")
                if (kind == Kind.LOCAL) continue
                val key = o.getString("key")
                val exists = db.rawQuery("SELECT 1 FROM videos WHERE key=?", arrayOf(key)).use { it.moveToFirst() }
                if (exists) { dup++; continue }
                val cv = ContentValues().apply {
                    put("key", key); put("kind", kind); put("video_id", o.optStringOrNull("video_id"))
                    put("url", o.getString("url")); put("title", o.optStringOrNull("title")); put("author", o.optStringOrNull("author"))
                    put("source", o.optString("source", Source.PASTE)); put("collection", o.optStringOrNull("collection"))
                    put("fav_date", o.optStringOrNull("fav_date")); put("added_at", o.optLong("added_at", System.currentTimeMillis()))
                    put("meta_state", MetaState.PENDING)
                }
                db.insert("videos", null, cv)
                added++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed()
        return Pair(added, dup)
    }

    private fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k) || !has(k)) null else optString(k)
}
