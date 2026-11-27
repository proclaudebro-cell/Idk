package app.floattok.data

import android.content.Context
import android.webkit.WebSettings
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Small HttpURLConnection helpers. All calls block: use them off the main thread. */
object Net {
    @Volatile private var ua: String? = null

    fun userAgent(context: Context): String = ua ?: try {
        WebSettings.getDefaultUserAgent(context).also { ua = it }
    } catch (_: Throwable) {
        "Mozilla/5.0 (Linux; Android 16) FloatTok/1.0".also { ua = it }
    }

    class Response(val code: Int, val body: ByteArray, val finalUrl: String)

    fun get(context: Context, url: String, maxBytes: Int = 4 * 1024 * 1024, timeoutMs: Int = 12000): Response? {
        var current = url
        repeat(8) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", userAgent(context))
                setRequestProperty("Accept", "*/*")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return Response(code, ByteArray(0), current)
                    current = URL(URL(current), loc).toString()
                    return@repeat
                }
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                val bytes = stream?.use { s ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(16384)
                    while (true) {
                        val n = s.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > maxBytes) break
                    }
                    out.toByteArray()
                } ?: ByteArray(0)
                return Response(code, bytes, current)
            } catch (_: Exception) {
                return null
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    /** Follows redirects of a short link (vm.tiktok.com/...) and returns the final URL. */
    fun resolveRedirects(context: Context, url: String): String? = get(context, url, maxBytes = 64 * 1024)?.finalUrl
}

/**
 * Fills in titles, authors and thumbnails using TikTok's public oEmbed endpoint
 * (https://www.tiktok.com/oembed?url=...), and resolves short links to video ids.
 */
class MetadataFetcher private constructor(private val context: Context) {

    companion object {
        @Volatile private var instance: MetadataFetcher? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: MetadataFetcher(context.applicationContext).also { instance = it }
        }
    }

    private val lib = Library.get(context)
    private val exec = Executors.newFixedThreadPool(2)
    private val inFlight: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val pausedUntil = AtomicLong(0)

    /** Queue a background lookup for an item still missing metadata. Cheap to call repeatedly. */
    fun request(item: VideoItem) {
        if (item.metaState != MetaState.PENDING || !item.isTikTok) return
        if (System.currentTimeMillis() < pausedUntil.get()) return
        if (!inFlight.add(item.id)) return
        exec.execute {
            try { fetchNow(item) } finally { inFlight.remove(item.id) }
        }
    }

    /** Fetch everything still pending, reporting (done, total) on the calling executor thread. */
    fun fetchAllPending(progress: (Int, Int) -> Unit) {
        exec.execute {
            val items = lib.pendingMeta(100000)
            var done = 0
            for (it in items) {
                if (System.currentTimeMillis() < pausedUntil.get()) Thread.sleep(pausedUntil.get() - System.currentTimeMillis())
                if (inFlight.add(it.id)) {
                    try { fetchNow(it) } finally { inFlight.remove(it.id) }
                }
                done++
                progress(done, items.size)
            }
            progress(items.size, items.size)
        }
    }

    /**
     * Blocking lookup. For short links this also resolves the video id; returns the (possibly merged) item.
     */
    fun fetchNow(item: VideoItem): VideoItem? {
        var id = item.id
        var url = item.url
        var videoId = item.videoId

        if (item.kind == Kind.TIKTOK_SHORT) {
            // oEmbed accepts many link forms and reports the id as embed_product_id.
            val o = oembed(url)
            val fromOembed = o?.optString("embed_product_id")?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
            val vid = fromOembed ?: Net.resolveRedirects(context, url)?.let { final ->
                LinkParser.videoIdFrom(final)?.also { url = final }
            }
            if (vid == null) {
                lib.setMetaState(id, MetaState.FAILED)
                return lib.get(id)
            }
            videoId = vid
            id = lib.resolveShortLink(id, vid, if (LinkParser.videoIdFrom(url) == vid) url else LinkParser.canonicalVideoUrl(vid))
            if (o != null && (o.has("title") || o.has("thumbnail_url"))) {
                save(id, o)
                return lib.get(id)
            }
            url = lib.get(id)?.url ?: url
        }

        val candidates = LinkedHashSet<String>()
        if (videoId != null && LinkParser.host(url).endsWith("tiktok.com")) candidates.add(url)
        if (videoId != null) candidates.add(LinkParser.canonicalVideoUrl(videoId))
        candidates.add(url)
        var anyResponse = false
        for (c in candidates) {
            val o = oembed(c) ?: continue
            anyResponse = true
            if (o.has("title") || o.has("author_name") || o.has("thumbnail_url")) {
                save(id, o)
                return lib.get(id)
            }
        }
        lib.setMetaState(id, if (anyResponse) MetaState.UNAVAILABLE else MetaState.FAILED)
        return lib.get(id)
    }

    private fun save(id: Long, o: JSONObject) {
        lib.updateMeta(
            id,
            o.optString("title").takeIf { it.isNotBlank() },
            o.optString("author_name").takeIf { it.isNotBlank() },
            o.optString("thumbnail_url").takeIf { it.isNotBlank() },
            MetaState.OK,
        )
    }

    private fun oembed(url: String): JSONObject? {
        val r = Net.get(context, "https://www.tiktok.com/oembed?url=" + URLEncoder.encode(url, "UTF-8"), maxBytes = 256 * 1024)
            ?: return null
        if (r.code == 429) {
            pausedUntil.set(System.currentTimeMillis() + 30_000)
            return null
        }
        if (r.code != 200) return if (r.code in 400..499) JSONObject() else null
        return try {
            val o = JSONObject(String(r.body, Charsets.UTF_8))
            if (o.has("status_msg") && !o.has("title")) JSONObject() else o
        } catch (_: Exception) {
            null
        }
    }
}
