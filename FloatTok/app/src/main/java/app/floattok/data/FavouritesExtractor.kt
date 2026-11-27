package app.floattok.data

import java.io.BufferedReader
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.io.Reader
import java.util.zip.ZipInputStream

/** One favourited (bookmarked) video found in a TikTok data export. */
data class FavouriteRecord(val link: String, val date: String?, val collection: String?)

class ExtractResult {
    /** Keyed by link so the same favourite listed twice (JSON + TXT in one ZIP) is counted once. */
    val records = LinkedHashMap<String, FavouriteRecord>()
    var likesSkipped = 0
    var otherLinksSkipped = 0
    var filesScanned = 0

    fun add(r: FavouriteRecord) {
        val existing = records[r.link]
        if (existing == null || (existing.collection == null && r.collection != null)) records[r.link] = r
    }
}

/**
 * Reads TikTok's "Download your data" export (ZIP, JSON or TXT) and pulls out Favourite Videos only.
 *
 * TikTok's export keeps likes under "Like List" -> "ItemFavoriteList" (a confusing name), and favourites under
 * "Favorite Videos" -> "FavoriteVideoList". Key names have changed between export versions, so matching is by
 * meaning (favourite/bookmark/collection + video) rather than exact spelling, and anything under a like key is
 * always excluded.
 */
object FavouritesExtractor {

    private val LINK_KEYS = listOf("link", "videolink", "url", "sharelink", "videourl", "weblink")
    private val DATE_KEYS = listOf("date", "favoritedate", "favouritedate", "time", "createtime", "savedate")
    private val COLLECTION_NAME_KEYS = listOf("collectionname", "foldername", "collection", "name", "title")

    private fun norm(k: String) = k.lowercase().filter { it in 'a'..'z' }

    fun isLikeKey(key: String): Boolean {
        val n = norm(key)
        return n.contains("like") || n == "itemfavoritelist" || n == "itemfavouritelist"
    }

    fun isCollectionKey(key: String): Boolean = norm(key).contains("collection")

    fun isFavouriteVideoKey(key: String): Boolean {
        val n = norm(key)
        if (n == "itemfavoritelist" || n == "itemfavouritelist") return false
        val fav = n.contains("favorit") || n.contains("favourit") || n.contains("bookmark")
        return fav && n.contains("video")
    }

    // ---------------------------------------------------------------- JSON

    private class Frame(val key: String?, val isArray: Boolean) {
        val scalars = HashMap<String, String>()
    }

    private class Collector(val result: ExtractResult) : JsonSax.Handler {
        private val stack = ArrayList<Frame>()

        private fun keys(): Sequence<String> = stack.asSequence().mapNotNull { it.key }
        private fun underLike() = keys().any { isLikeKey(it) }
        private fun underFavourites() = keys().any { isFavouriteVideoKey(it) || isCollectionKey(it) }
        private fun underCollection() = keys().any { isCollectionKey(it) }

        private fun collectionName(): String? {
            if (!underCollection()) return null
            for (i in stack.indices.reversed()) {
                val f = stack[i]
                for ((k, v) in f.scalars) {
                    if (norm(k) in COLLECTION_NAME_KEYS && v.isNotBlank() && !v.startsWith("http")) return v.trim()
                }
            }
            return null
        }

        override fun startObject(key: String?) { stack.add(Frame(key, false)) }
        override fun startArray(key: String?) { stack.add(Frame(key, true)) }
        override fun endArray() { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) }

        override fun endObject() {
            if (stack.isEmpty()) return
            val f = stack[stack.size - 1]
            val link = LINK_KEYS.firstNotNullOfOrNull { lk ->
                f.scalars.entries.firstOrNull { norm(it.key) == lk && it.value.contains("http") }?.value
            }
            if (link != null) {
                val date = DATE_KEYS.firstNotNullOfOrNull { dk -> f.scalars.entries.firstOrNull { norm(it.key) == dk }?.value }
                classify(link.trim(), date, f.scalars.entries.firstOrNull { norm(it.key) == "collection" || norm(it.key) == "collectionname" }?.value)
            }
            stack.removeAt(stack.size - 1)
        }

        override fun scalar(key: String?, value: String?) {
            if (value == null || stack.isEmpty()) return
            val f = stack[stack.size - 1]
            if (!f.isArray && key != null) {
                f.scalars[key] = value
            } else if (f.isArray && value.startsWith("http")) {
                // A bare list of links: ["https://...", ...]
                classify(value.trim(), null, null)
            }
        }

        private fun classify(link: String, date: String?, ownCollection: String?) {
            when {
                underLike() -> result.likesSkipped++
                underFavourites() -> result.add(FavouriteRecord(link, date?.trim(), ownCollection?.trim() ?: collectionName()))
                else -> result.otherLinksSkipped++
            }
        }
    }

    fun fromJson(reader: Reader, result: ExtractResult) {
        result.filesScanned++
        JsonSax(reader, Collector(result)).parse()
    }

    // ---------------------------------------------------------------- TXT

    enum class TxtKind { FAVOURITES, LIKES, OTHER, UNKNOWN }

    fun txtKindForName(name: String): TxtKind {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val n = norm(base)
        return when {
            isLikeKey(base) -> TxtKind.LIKES
            isFavouriteVideoKey(base) || n.contains("collection") -> TxtKind.FAVOURITES
            n.contains("favorit") || n.contains("favourit") -> TxtKind.OTHER // favourite sounds/effects/hashtags
            else -> TxtKind.UNKNOWN
        }
    }

    /**
     * TikTok's TXT export lists entries as "Date: ..." / "Link: ..." line pairs.
     * [treatUnknownAsFavourites] is true when the user picked this single file themselves.
     */
    fun fromTxt(name: String, text: String, result: ExtractResult, treatUnknownAsFavourites: Boolean) {
        result.filesScanned++
        val kind = txtKindForName(name)
        val take = kind == TxtKind.FAVOURITES || (kind == TxtKind.UNKNOWN && treatUnknownAsFavourites)
        var date: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("Date:", ignoreCase = true)) {
                date = line.substringAfter(':').trim()
                continue
            }
            for (u in LinkParser.extractUrls(line)) {
                when {
                    take -> result.add(FavouriteRecord(u, date, null))
                    kind == TxtKind.LIKES -> result.likesSkipped++
                    else -> result.otherLinksSkipped++
                }
            }
            if (line.startsWith("Link:", ignoreCase = true)) date = null
        }
    }

    // ---------------------------------------------------------------- ZIP / dispatch

    private const val MAX_TXT_BYTES = 64L * 1024 * 1024

    fun fromZip(input: InputStream, result: ExtractResult) {
        val zip = ZipInputStream(input)
        while (true) {
            val e = zip.nextEntry ?: break
            if (e.isDirectory) continue
            val name = e.name
            val lower = name.lowercase()
            val nonClosing = object : FilterInputStream(zip) { override fun close() {} }
            when {
                lower.endsWith(".json") -> fromJson(BufferedReader(InputStreamReader(nonClosing, Charsets.UTF_8)), result)
                lower.endsWith(".txt") -> {
                    val kind = txtKindForName(name)
                    if (kind == TxtKind.FAVOURITES || kind == TxtKind.LIKES) {
                        fromTxt(name, readLimited(nonClosing), result, treatUnknownAsFavourites = false)
                    }
                }
            }
            zip.closeEntry()
        }
    }

    /** Detects ZIP / JSON / TXT from the content and parses accordingly. */
    fun fromStream(displayName: String, input: InputStream, result: ExtractResult) {
        val pb = PushbackInputStream(input, 4)
        val head = ByteArray(4)
        val n = pb.read(head)
        if (n > 0) pb.unread(head, 0, n)
        val isZip = n >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        if (isZip) {
            fromZip(pb, result)
            return
        }
        val reader = BufferedReader(InputStreamReader(pb, Charsets.UTF_8))
        reader.mark(8192)
        var c: Int
        do { c = reader.read() } while (c != -1 && (c.toChar().isWhitespace() || c == 0xFEFF))
        reader.reset()
        if (c == '{'.code || c == '['.code) fromJson(reader, result)
        else fromTxt(displayName, readLimited(reader), result, treatUnknownAsFavourites = true)
    }

    private fun readLimited(input: InputStream): String {
        val bytes = input.readBytes()
        if (bytes.size > MAX_TXT_BYTES) throw IOException("Text file is too large")
        return String(bytes, Charsets.UTF_8)
    }

    private fun readLimited(reader: Reader): String {
        val sb = StringBuilder()
        val buf = CharArray(8192)
        while (true) {
            val r = reader.read(buf)
            if (r < 0) break
            sb.append(buf, 0, r)
            if (sb.length > MAX_TXT_BYTES) throw IOException("Text file is too large")
        }
        return sb.toString()
    }
}

/**
 * Minimal streaming JSON reader (SAX style). Keeps memory flat for exports that are hundreds of MB,
 * and has no Android dependencies so it runs in unit tests.
 */
class JsonSax(reader: Reader, private val h: Handler) {
    interface Handler {
        fun startObject(key: String?)
        fun endObject()
        fun startArray(key: String?)
        fun endArray()
        /** Strings, numbers and booleans arrive as text; JSON null arrives as null. */
        fun scalar(key: String?, value: String?)
    }

    private val r = if (reader is BufferedReader) reader else BufferedReader(reader, 65536)
    private var peeked = -2
    private val sb = StringBuilder()

    private fun read(): Int {
        if (peeked != -2) { val p = peeked; peeked = -2; return p }
        return r.read()
    }

    private fun peek(): Int {
        if (peeked == -2) peeked = r.read()
        return peeked
    }

    private fun skipWs(): Int {
        while (true) {
            val c = peek()
            if (c == -1) return -1
            if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code || c == 0xFEFF) { read(); continue }
            return c
        }
    }

    private fun fail(msg: String): Nothing = throw IOException("Invalid JSON: $msg")

    fun parse() {
        if (skipWs() == -1) return
        value(null)
    }

    private fun value(key: String?) {
        when (val c = skipWs()) {
            '{'.code -> obj(key)
            '['.code -> arr(key)
            '"'.code -> h.scalar(key, string())
            -1 -> fail("unexpected end")
            else -> {
                sb.setLength(0)
                while (true) {
                    val d = peek()
                    if (d == -1 || d == ','.code || d == '}'.code || d == ']'.code || d <= ' '.code) break
                    sb.append(read().toChar())
                }
                val lit = sb.toString()
                if (lit.isEmpty()) fail("unexpected character '${c.toChar()}'")
                h.scalar(key, if (lit == "null") null else lit)
            }
        }
    }

    private fun obj(key: String?) {
        read() // {
        h.startObject(key)
        if (skipWs() == '}'.code) { read(); h.endObject(); return }
        while (true) {
            if (skipWs() != '"'.code) fail("expected name")
            val name = string()
            if (skipWs() != ':'.code) fail("expected ':'")
            read()
            value(name)
            when (skipWs()) {
                ','.code -> read()
                '}'.code -> { read(); h.endObject(); return }
                else -> fail("expected ',' or '}'")
            }
        }
    }

    private fun arr(key: String?) {
        read() // [
        h.startArray(key)
        if (skipWs() == ']'.code) { read(); h.endArray(); return }
        while (true) {
            value(null)
            when (skipWs()) {
                ','.code -> read()
                ']'.code -> { read(); h.endArray(); return }
                else -> fail("expected ',' or ']'")
            }
        }
    }

    private fun string(): String {
        read() // opening quote
        sb.setLength(0)
        while (true) {
            val c = read()
            when (c) {
                -1 -> fail("unterminated string")
                '"'.code -> return sb.toString()
                '\\'.code -> {
                    when (val e = read()) {
                        '"'.code -> sb.append('"')
                        '\\'.code -> sb.append('\\')
                        '/'.code -> sb.append('/')
                        'b'.code -> sb.append('\b')
                        'f'.code -> sb.append('\u000C')
                        'n'.code -> sb.append('\n')
                        'r'.code -> sb.append('\r')
                        't'.code -> sb.append('\t')
                        'u'.code -> {
                            var v = 0
                            repeat(4) {
                                val d = read()
                                val digit = Character.digit(d, 16)
                                if (d == -1 || digit < 0) fail("bad unicode escape")
                                v = v * 16 + digit
                            }
                            sb.append(v.toChar())
                        }
                        else -> fail("bad escape '${e.toChar()}'")
                    }
                }
                else -> sb.append(c.toChar())
            }
        }
    }
}
