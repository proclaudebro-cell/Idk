package app.floattok.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Async thumbnail loading with a memory LRU and a small disk cache. */
object ImageLoader {
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newFixedThreadPool(3)
    private val mem = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val retriedIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    private fun cacheKey(item: VideoItem) = item.key + "|" + (item.thumbUrl ?: "")

    fun cached(item: VideoItem): Bitmap? = mem.get(cacheKey(item))

    fun load(context: Context, item: VideoItem, target: ImageView, sizePx: Int) {
        val key = cacheKey(item)
        target.tag = key
        mem.get(key)?.let { target.setImageBitmap(it); return }
        target.setImageDrawable(null)
        if (item.thumbUrl == null && item.kind != Kind.LOCAL) return
        val app = context.applicationContext
        exec.execute {
            val bmp = try { fetch(app, item, sizePx) } catch (_: Throwable) { null }
            if (bmp != null) {
                mem.put(key, bmp)
                main.post { if (target.tag == key) target.setImageBitmap(bmp) }
            }
        }
    }

    /** Blocking; for the media notification. */
    fun loadBlocking(context: Context, item: VideoItem, sizePx: Int): Bitmap? {
        val key = cacheKey(item)
        mem.get(key)?.let { return it }
        return try { fetch(context.applicationContext, item, sizePx)?.also { mem.put(key, it) } } catch (_: Throwable) { null }
    }

    private fun diskFile(context: Context, item: VideoItem): File {
        val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
        val md = MessageDigest.getInstance("SHA-1").digest(cacheKey(item).toByteArray())
        return File(dir, md.joinToString("") { "%02x".format(it) } + ".jpg")
    }

    private fun fetch(context: Context, item: VideoItem, sizePx: Int): Bitmap? {
        if (item.kind == Kind.LOCAL) {
            return try {
                context.contentResolver.loadThumbnail(Uri.parse(item.url), Size(sizePx, sizePx * 16 / 9), null)
            } catch (_: Throwable) { null }
        }
        val url = item.thumbUrl ?: return null
        val file = diskFile(context, item)
        if (file.exists() && file.length() > 0) decode(file.readBytes(), sizePx)?.let { return it }
        val r = Net.get(context, url, maxBytes = 3 * 1024 * 1024) ?: return null
        if (r.code != 200) {
            // TikTok thumbnail URLs are signed and expire; ask for fresh metadata once per session.
            if (r.code in 400..499 && retriedIds.add(item.id)) {
                Library.get(context).setMetaState(item.id, MetaState.PENDING)
            }
            return null
        }
        val bmp = decode(r.body, sizePx) ?: return null
        try {
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } catch (_: Exception) {}
        return bmp
    }

    private fun decode(bytes: ByteArray, sizePx: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0) return null
        var sample = 1
        while (opts.outWidth / (sample * 2) >= sizePx) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
