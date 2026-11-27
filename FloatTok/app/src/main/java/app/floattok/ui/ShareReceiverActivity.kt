package app.floattok.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import app.floattok.data.Importer
import app.floattok.data.Library
import app.floattok.data.Source
import app.floattok.playback.QueueOps
import java.io.File
import java.util.concurrent.Executors

/**
 * Receives TikTok's Share → FloatTok (text with a link), opened TikTok links, and shared video files.
 * Adds them to the library, then offers Play now / Add to queue.
 */
class ShareReceiverActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private lateinit var title: TextView
    private lateinit var message: TextView
    private lateinit var progress: ProgressBar
    private lateinit var buttons: LinearLayout
    private var ids: List<Long> = emptyList()

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        setContentView(buildUi())
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        bg.shutdown()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setOnClickListener { finish() } }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.SURFACE, dp(24).toFloat(), Ui.STROKE, dp(1))
            setPadding(dp(20), dp(18), dp(20), dp(18))
            isClickable = true
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            (sheet.layoutParams as FrameLayout.LayoutParams).bottomMargin = b.bottom + dp(12)
            sheet.requestLayout()
            insets
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        progress = ProgressBar(this).apply { isIndeterminate = true }
        header.addView(progress, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) })
        title = Ui.text(this, "Adding to FloatTok…", 17f, Ui.TEXT, bold = true)
        header.addView(title)
        sheet.addView(header)
        message = Ui.text(this, "", 13f, Ui.TEXT_2).apply { setPadding(0, dp(8), 0, dp(14)) }
        sheet.addView(message)
        buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        sheet.addView(buttons)
        root.addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dp(12), 0, dp(12), dp(12))
        })
        return root
    }

    private fun handle(intent: Intent?) {
        intent ?: return finish()
        progress.visibility = View.VISIBLE
        buttons.removeAllViews()
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.let { addText(it) } ?: finish()
            Intent.ACTION_SEND -> {
                val type = intent.type ?: ""
                if (type.startsWith("video/")) {
                    @Suppress("DEPRECATION")
                    (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { addVideos(listOf(it)) } ?: finish()
                } else {
                    val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_TEXT), intent.getStringExtra(Intent.EXTRA_SUBJECT),
                        intent.dataString).joinToString("\n")
                    addText(text)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: arrayListOf()
                if (uris.isEmpty()) finish() else addVideos(uris)
            }
            else -> finish()
        }
    }

    private fun addText(text: String) {
        bg.execute {
            val s = Importer.importText(this, text, Source.SHARE)
            main.post {
                ids = s.ids
                if (s.found == 0) showResult("No TikTok video found", "The shared text didn't contain a TikTok video link.", false)
                else showResult(if (s.added > 0) "Added to FloatTok" else "Already in your library",
                    if (s.found == 1) "Saved to your library as a shared video." else "${s.found} videos saved to your library.", true)
            }
        }
    }

    /** Shared video files are copied into app storage: the share permission doesn't last. */
    private fun addVideos(uris: List<Uri>) {
        title.text = "Saving video…"
        bg.execute {
            val lib = Library.get(this)
            val out = ArrayList<Long>()
            var failed = 0
            val dir = File(filesDir, "videos").apply { mkdirs() }
            for (u in uris) {
                try {
                    val name = Importer.displayName(this, u)
                    val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
                    val f = File(dir, "${System.currentTimeMillis()}_$safe")
                    contentResolver.openInputStream(u)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
                    out.add(lib.addLocal(Uri.fromFile(f).toString(), name).id)
                } catch (_: Exception) {
                    failed++
                }
            }
            main.post {
                ids = out
                if (out.isEmpty()) showResult("Couldn't save the video", "The shared file couldn't be read.", false)
                else showResult("Saved to FloatTok", "${out.size} video(s) added" + if (failed > 0) ", $failed failed." else ".", true)
            }
        }
    }

    private fun showResult(t: String, m: String, canPlay: Boolean) {
        progress.visibility = View.GONE
        title.text = t
        message.text = m
        buttons.removeAllViews()
        val lp = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) } }
        buttons.addView(Ui.button(this, "Done") { finish() }, lp())
        if (!canPlay || ids.isEmpty()) return
        buttons.addView(Ui.button(this, "Add to queue") {
            QueueOps.append(this, ids)
            finish()
        }, lp())
        if (!Settings.canDrawOverlays(this)) {
            message.text = m + "\n\nTo play over other apps, allow \"Display over other apps\" for FloatTok first."
            buttons.addView(Ui.button(this, "Allow overlay", primary = true) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }, lp())
            return
        }
        buttons.addView(Ui.button(this, "Play now", primary = true) {
            val idx = QueueOps.insertNext(this, ids.first())
            if (ids.size > 1) QueueOps.append(this, ids.drop(1))
            QueueOps.playIndex(this, idx)
            finish()
        }, lp())
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the overlay permission screen: refresh the buttons.
        if (ids.isNotEmpty() && progress.visibility == View.GONE) showResult(title.text.toString(), message.text.toString().substringBefore("\n\nTo play"), true)
    }
}
