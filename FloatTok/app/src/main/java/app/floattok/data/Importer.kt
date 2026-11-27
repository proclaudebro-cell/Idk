package app.floattok.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/** Turns shared text, pasted text and export files into library entries. */
object Importer {

    data class Summary(
        val found: Int,
        val added: Int,
        val duplicates: Int,
        val likesSkipped: Int = 0,
        val unsupported: Int = 0,
        val firstId: Long? = null,
        val ids: List<Long> = emptyList(),
    ) {
        fun describe(): String = buildString {
            if (found == 0) {
                append("No TikTok video links found.")
            } else {
                append("$added added")
                if (duplicates > 0) append(", $duplicates already in library")
                append(".")
            }
            if (likesSkipped > 0) append(" Ignored $likesSkipped liked videos (only favourites are imported).")
            if (unsupported > 0) append(" Skipped $unsupported unsupported links.")
        }
    }

    /** Links from shared or pasted text. Each is added individually so ids come back in order. */
    fun importText(context: Context, text: String, source: String): Summary {
        val lib = Library.get(context)
        val links = LinkParser.usableLinks(text)
        val unsupported = LinkParser.extractUrls(text).size - links.size
        var added = 0
        var dup = 0
        val ids = ArrayList<Long>()
        for (p in links) {
            val r = lib.add(p, source)
            if (r.isNew) added++ else dup++
            ids.add(r.id)
        }
        return Summary(links.size, added, dup, unsupported = unsupported.coerceAtLeast(0), firstId = ids.firstOrNull(), ids = ids)
    }

    fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment ?: "file"
    }

    /** TikTok data export (ZIP / JSON / TXT). Only Favourite Videos are imported. Blocking. */
    fun importExport(context: Context, uri: Uri): Summary {
        val name = displayName(context, uri)
        val result = ExtractResult()
        context.contentResolver.openInputStream(uri)?.use { FavouritesExtractor.fromStream(name, it, result) }
            ?: throw IllegalStateException("Couldn't open $name")
        val entries = ArrayList<Library.BulkEntry>()
        var unsupported = 0
        for (r in result.records.values) {
            val p = LinkParser.parse(r.link)
            if (p.kind == LinkParser.Kind.OTHER) { unsupported++; continue }
            entries.add(Library.BulkEntry(p, r.collection, r.date))
        }
        val (added, dup) = Library.get(context).addAll(entries, Source.EXPORT)
        return Summary(entries.size, added, dup, result.likesSkipped, unsupported)
    }

    /** Entries collected from a TikTok web page the user was viewing. */
    fun importWeb(context: Context, items: List<Triple<String, String?, String?>>, collection: String?): Summary {
        val entries = items.mapNotNull { (link, title, thumb) ->
            val p = LinkParser.parse(link)
            if (p.kind == LinkParser.Kind.TIKTOK || p.kind == LinkParser.Kind.TIKTOK_PHOTO) Library.BulkEntry(p, collection, null, title, thumb) else null
        }
        val (added, dup) = Library.get(context).addAll(entries, Source.WEB)
        return Summary(entries.size, added, dup)
    }
}
