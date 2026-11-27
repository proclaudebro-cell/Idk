package app.floattok.data

/**
 * Finds video links in shared text, pasted text and export files, and works out what each one points to.
 * Pure Kotlin so it can be unit tested on the JVM.
 */
object LinkParser {

    private val URL_REGEX = Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)

    private val TIKTOK_HOSTS = listOf("tiktok.com", "tiktokv.com", "tiktokv.us", "tiktokv.eu")
    private val SHORT_HOSTS = setOf("vm.tiktok.com", "vt.tiktok.com")

    private val ID_PATTERNS = listOf(
        Regex("""/video/(\d{6,25})"""),
        Regex("""/share/video/(\d{6,25})"""),
        Regex("""/player/v1/(\d{6,25})"""),
        Regex("""/embed/(?:v2/)?(\d{6,25})"""),
        Regex("""/v/(\d{6,25})(?:\.html)?"""),
        Regex("""[?&](?:item_id|share_item_id|aweme_id)=(\d{6,25})"""),
    )
    private val PHOTO_PATTERN = Regex("""/photo/(\d{6,25})""")

    private val DIRECT_MEDIA = Regex("""\.(mp4|m4v|webm|mkv|mov|3gp|m3u8)(\?.*)?$""", RegexOption.IGNORE_CASE)

    enum class Kind { TIKTOK, TIKTOK_SHORT, TIKTOK_PHOTO, DIRECT_MEDIA, OTHER }

    data class Parsed(val url: String, val kind: Kind, val videoId: String?)

    /** All http(s) URLs in [text], with trailing punctuation that is rarely part of a link removed. */
    fun extractUrls(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (m in URL_REGEX.findAll(text)) {
            var u = m.value
            while (u.isNotEmpty() && u.last() in ".,;:!?)]}>'\"") u = u.dropLast(1)
            if (u.length > 10) out.add(u)
        }
        return out.toList()
    }

    fun host(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        return afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfter('@').substringBefore(':').lowercase()
    }

    fun isTikTokHost(host: String): Boolean = TIKTOK_HOSTS.any { host == it || host.endsWith(".$it") }

    fun isShortLink(url: String): Boolean {
        val h = host(url)
        if (h in SHORT_HOSTS) return true
        if (isTikTokHost(h)) {
            val path = url.substringAfter(h, "").substringBefore('?')
            if (path.startsWith("/t/")) return true
        }
        return false
    }

    fun videoIdFrom(url: String): String? {
        if (!isTikTokHost(host(url))) return null
        for (p in ID_PATTERNS) p.find(url)?.let { return it.groupValues[1] }
        return null
    }

    fun parse(url: String): Parsed {
        val h = host(url)
        if (isTikTokHost(h)) {
            PHOTO_PATTERN.find(url)?.let { return Parsed(url, Kind.TIKTOK_PHOTO, it.groupValues[1]) }
            val id = videoIdFrom(url)
            if (id != null) return Parsed(url, Kind.TIKTOK, id)
            if (isShortLink(url)) return Parsed(url, Kind.TIKTOK_SHORT, null)
            return Parsed(url, Kind.OTHER, null)
        }
        if (DIRECT_MEDIA.containsMatchIn(url.substringBefore('#'))) return Parsed(url, Kind.DIRECT_MEDIA, null)
        return Parsed(url, Kind.OTHER, null)
    }

    /** Links in [text] that FloatTok can do something with (TikTok videos, short links, direct media). */
    fun usableLinks(text: String): List<Parsed> =
        extractUrls(text).map { parse(it) }.filter { it.kind != Kind.OTHER }

    fun canonicalVideoUrl(videoId: String) = "https://www.tiktok.com/@/video/$videoId"

    /** Dedupe key: one library entry per TikTok video regardless of which link form it came from. */
    fun keyFor(p: Parsed): String = when {
        p.videoId != null && p.kind == Kind.TIKTOK -> "tt:${p.videoId}"
        p.videoId != null && p.kind == Kind.TIKTOK_PHOTO -> "ttp:${p.videoId}"
        else -> "url:" + p.url.substringBefore('#')
    }

    fun tiktokKey(videoId: String) = "tt:$videoId"
}
