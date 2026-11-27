package app.floattok.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import app.floattok.R
import app.floattok.data.ImageLoader
import app.floattok.data.Importer
import app.floattok.data.Kind
import app.floattok.data.Library
import app.floattok.data.MetaState
import app.floattok.data.MetadataFetcher
import app.floattok.data.Prefs
import app.floattok.data.Source
import app.floattok.data.VideoItem
import app.floattok.data.VideoMode
import app.floattok.overlay.OverlayService
import app.floattok.playback.PlaybackState
import app.floattok.playback.QueueOps
import app.floattok.playback.RepeatMode
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_LIBRARY = 0
        const val TAB_QUEUE = 1
        const val TAB_IMPORT = 2
        const val TAB_SETTINGS = 3
        private const val REQ_EXPORT = 10
        private const val REQ_LOCAL = 11
        private const val REQ_BACKUP_SAVE = 12
        private const val REQ_BACKUP_OPEN = 13
        private const val REQ_NOTIF = 20
    }

    private lateinit var prefs: Prefs
    private lateinit var lib: Library
    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()

    private lateinit var permCard: LinearLayout
    private lateinit var notifCard: LinearLayout
    private lateinit var pageHost: FrameLayout
    private lateinit var nowPlaying: TextView
    private val tabViews = ArrayList<LinearLayout>()
    private var currentTab = TAB_LIBRARY

    private lateinit var libraryPage: LibraryPage
    private lateinit var queuePage: QueuePage
    private lateinit var importPage: ImportPage
    private lateinit var settingsPage: SettingsPage

    private val libListener: () -> Unit = { scheduleRefresh() }
    private val playListener: () -> Unit = { onPlaybackChanged() }
    private val refreshRunnable = Runnable { libraryPage.reload(); queuePage.reload() }

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        lib = Library.get(this)
        window.setDecorFitsSystemWindows(false)
        setContentView(buildRoot())
        selectTab(intent?.getIntExtra(EXTRA_TAB, TAB_LIBRARY) ?: TAB_LIBRARY)
        lib.addListener(libListener)
        PlaybackState.add(playListener)
        if (!prefs.onboardingDone && lib.count() == 0) selectTab(TAB_IMPORT)
        prefs.onboardingDone = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra(EXTRA_TAB)) selectTab(intent.getIntExtra(EXTRA_TAB, TAB_LIBRARY))
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionCards()
        settingsPage.refresh()
        onPlaybackChanged()
        scheduleRefresh()
    }

    override fun onDestroy() {
        lib.removeListener(libListener)
        PlaybackState.remove(playListener)
        main.removeCallbacksAndMessages(null)
        bg.shutdown()
        super.onDestroy()
    }

    private fun scheduleRefresh() {
        main.removeCallbacks(refreshRunnable)
        main.postDelayed(refreshRunnable, 250)
    }

    private fun onPlaybackChanged() {
        val running = PlaybackState.running
        val id = PlaybackState.currentId
        nowPlaying.visibility = if (running && id != null) View.VISIBLE else View.GONE
        if (running && id != null) {
            bg.execute {
                val title = lib.get(id)?.displayTitle()
                main.post { nowPlaying.text = (if (PlaybackState.playing) "▶  " else "❚❚  ") + (title ?: "") }
            }
        }
        if (::queuePage.isInitialized) queuePage.onPlaybackChanged()
        if (::libraryPage.isInitialized) libraryPage.adapter.notifyDataSetChanged()
    }

    // ---------------------------------------------------------------- layout

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(16), dp(6))
        }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val logo = Ui.text(this, "", 24f, Ui.TEXT, bold = true).apply {
            text = android.text.SpannableStringBuilder().apply {
                append("Float")
                setSpan(android.text.style.ForegroundColorSpan(Ui.ACCENT), 0, 5, 0)
                append("Tok")
            }
        }
        titleCol.addView(logo)
        titleCol.addView(Ui.text(this, "Your TikTok favourites, floating over any app", 12f, Ui.TEXT_2))
        header.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val floatBtn = Ui.button(this, "Float  ▶", primary = true) { launchPlayer() }.apply {
            contentDescription = "Open the floating player"
        }
        header.addView(floatBtn)
        root.addView(header)

        nowPlaying = Ui.text(this, "", 12f, Ui.ACCENT).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(20), 0, dp(20), dp(6))
            visibility = View.GONE
            setOnClickListener { selectTab(TAB_QUEUE) }
        }
        root.addView(nowPlaying)

        permCard = Ui.card(this).apply {
            addView(Ui.text(this@MainActivity, "Allow FloatTok to float over other apps", 15f, Ui.TEXT, bold = true))
            addView(Ui.text(this@MainActivity,
                "The floating player needs Android's \"Display over other apps\" permission. " +
                    "Tap below, switch FloatTok on, then come back.", 13f, Ui.TEXT_2).apply { setPadding(0, dp(6), 0, dp(10)) })
            addView(Ui.button(this@MainActivity, "Open permission setting", primary = true) { openOverlaySettings() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(permCard, Ui.lpMatchWrap(this, 4, 6).apply { marginStart = dp(16); marginEnd = dp(16) })

        notifCard = Ui.card(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.text(this@MainActivity, "Allow notifications for playback controls", 13f, Ui.TEXT_2),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.button(this@MainActivity, "Allow") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF) })
        }
        root.addView(notifCard, Ui.lpMatchWrap(this, 0, 6).apply { marginStart = dp(16); marginEnd = dp(16) })

        pageHost = FrameLayout(this)
        root.addView(pageHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        libraryPage = LibraryPage()
        queuePage = QueuePage()
        importPage = ImportPage()
        settingsPage = SettingsPage()
        for (p in listOf(libraryPage.view, queuePage.view, importPage.view, settingsPage.view)) {
            pageHost.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        // Bottom tabs
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.SURFACE)
            setPadding(0, dp(4), 0, dp(4))
        }
        val tabs = listOf(R.drawable.ic_library to "Library", R.drawable.ic_queue to "Queue",
            R.drawable.ic_import to "Import", R.drawable.ic_tune to "Settings")
        tabs.forEachIndexed { i, (icon, label) ->
            val t = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(4))
                background = Ui.ripple(null, dp(20).toFloat())
                isClickable = true
                setOnClickListener { selectTab(i) }
                contentDescription = label
            }
            t.addView(Ui.icon(this, icon, Ui.TEXT_2, 22, 2).apply { background = null; isClickable = false })
            t.addView(Ui.text(this, label, 11f, Ui.TEXT_2))
            tabBar.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            tabViews.add(t)
        }
        root.addView(tabBar)
        return root
    }

    private fun selectTab(i: Int) {
        currentTab = i.coerceIn(0, 3)
        listOf(libraryPage.view, queuePage.view, importPage.view, settingsPage.view).forEachIndexed { idx, v ->
            v.visibility = if (idx == currentTab) View.VISIBLE else View.GONE
        }
        tabViews.forEachIndexed { idx, t ->
            val c = if (idx == currentTab) Ui.ACCENT else Ui.TEXT_2
            (t.getChildAt(0) as ImageView).imageTintList = android.content.res.ColorStateList.valueOf(c)
            (t.getChildAt(1) as TextView).setTextColor(c)
        }
        if (currentTab == TAB_QUEUE) queuePage.reload()
        if (currentTab == TAB_SETTINGS) settingsPage.refresh()
    }

    private fun refreshPermissionCards() {
        permCard.visibility = if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
        val needNotif = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        notifCard.visibility = if (needNotif && Settings.canDrawOverlays(this)) View.VISIBLE else View.GONE
    }

    private fun openOverlaySettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun ensureOverlay(): Boolean {
        if (Settings.canDrawOverlays(this)) return true
        AlertDialog.Builder(this)
            .setTitle("Permission needed")
            .setMessage("To play videos over other apps, allow FloatTok to \"Display over other apps\".")
            .setPositiveButton("Open setting") { _, _ -> openOverlaySettings() }
            .setNegativeButton("Cancel", null)
            .show()
        return false
    }

    private fun launchPlayer() {
        if (!ensureOverlay()) return
        bg.execute {
            val queue = lib.queueIds()
            val all = if (queue.isEmpty()) lib.query().map { it.id } else emptyList()
            main.post {
                when {
                    queue.isNotEmpty() -> OverlayService.start(this)
                    all.isNotEmpty() -> QueueOps.playList(this, all, 0)
                    else -> {
                        toast("Your library is empty. Import or share some TikTok videos first.")
                        selectTab(TAB_IMPORT)
                    }
                }
            }
        }
    }

    private fun playFrom(items: List<VideoItem>, index: Int) {
        if (!ensureOverlay()) return
        QueueOps.playList(this, items.map { it.id }, index)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun settingsChanged() {
        OverlayService.instance?.onSettingsChanged()
    }

    // ---------------------------------------------------------------- shared row view

    private fun thumbView(wDp: Int, hDp: Int) = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(Ui.SURFACE_2)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
        }
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(dp(wDp), dp(hDp))
    }

    private fun itemMeta(v: VideoItem): String = buildString {
        v.author?.let { append("@").append(it).append("  ·  ") }
        append(Source.label(v.source))
        v.collection?.let { append("  ·  ").append(it) }
        if (v.metaState == MetaState.UNAVAILABLE) append("  ·  unavailable")
    }

    private fun showItemActions(v: VideoItem, list: List<VideoItem>, index: Int) {
        val actions = arrayOf("Play now", "Play next", "Add to end of queue", "Set collection…", "Open in TikTok", "Remove from library")
        AlertDialog.Builder(this)
            .setTitle(v.displayTitle())
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> playFrom(list, index)
                    1 -> { QueueOps.insertNext(this, v.id); toast("Plays next") }
                    2 -> { QueueOps.append(this, listOf(v.id)); toast("Added to queue") }
                    3 -> askCollection(v)
                    4 -> openExternally(v)
                    5 -> confirm("Remove from library?", "This only removes it from FloatTok, not from TikTok.") { lib.delete(v.id) }
                }
            }
            .show()
    }

    private fun askCollection(v: VideoItem) {
        val input = EditText(this).apply {
            setText(v.collection ?: "")
            hint = "Collection name (empty to clear)"
            setSingleLine()
        }
        val existing = lib.collections()
        val b = AlertDialog.Builder(this).setTitle("Collection").setView(FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0); addView(input)
        })
            .setPositiveButton("Save") { _, _ -> lib.setCollection(v.id, input.text.toString()) }
            .setNegativeButton("Cancel", null)
        if (existing.isNotEmpty()) b.setNeutralButton("Pick existing") { _, _ ->
            AlertDialog.Builder(this).setItems(existing.toTypedArray()) { _, w -> lib.setCollection(v.id, existing[w]) }.show()
        }
        b.show()
    }

    private fun openExternally(v: VideoItem) {
        val url = if (v.videoId != null && v.isTikTok) "https://www.tiktok.com/@/video/${v.videoId}" else v.url
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            toast("No app can open this link")
        }
    }

    private fun confirm(title: String, msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg)
            .setPositiveButton("OK") { _, _ -> ok() }.setNegativeButton("Cancel", null).show()
    }

    // ================================================================ Library

    private inner class LibraryPage {
        val view = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        private val search = EditText(this@MainActivity)
        private val info = Ui.text(this@MainActivity, "", 12f, Ui.TEXT_3)
        private val chipRow = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        private val list = ListView(this@MainActivity)
        private val empty = Ui.text(this@MainActivity, "", 14f, Ui.TEXT_2)
        private var sourceFilter: String? = null
        private var collectionFilter: String? = null
        private var items: List<VideoItem> = emptyList()
        private var seq = 0
        private val sourceChips = ArrayList<Pair<TextView, String?>>()
        private lateinit var collectionChip: TextView

        val adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = items[position].id
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val row = (convertView as? LinearLayout) ?: makeRow()
                bindRow(row, items[position], position)
                return row
            }
        }

        init {
            search.apply {
                hint = "Search title, creator, collection"
                setHintTextColor(Ui.TEXT_3)
                setTextColor(Ui.TEXT)
                textSize = 14f
                setSingleLine()
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                inputType = InputType.TYPE_CLASS_TEXT
                background = Ui.rounded(Ui.SURFACE, dp(22).toFloat(), Ui.STROKE, dp(1))
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
                compoundDrawablePadding = dp(8)
                compoundDrawableTintList = android.content.res.ColorStateList.valueOf(Ui.TEXT_3)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) { scheduleRefresh() }
                })
            }
            view.addView(search, Ui.lpMatchWrap(this@MainActivity, 6, 6).apply { marginStart = dp(16); marginEnd = dp(16) })

            val scroller = HorizontalScrollView(this@MainActivity).apply { isHorizontalScrollBarEnabled = false }
            chipRow.setPadding(dp(16), 0, dp(16), 0)
            val sources = listOf(null to "All", Source.EXPORT to "Data export", Source.SHARE to "Shared",
                Source.WEB to "TikTok web", Source.PASTE to "Pasted", Source.LOCAL to "Local")
            for ((s, label) in sources) {
                val c = Ui.chip(this@MainActivity, label, s == null) { sourceFilter = s; updateChips(); reload() }
                sourceChips.add(c to s)
                chipRow.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
            }
            collectionChip = Ui.chip(this@MainActivity, "Collection ▾", false) { pickCollection() }
            chipRow.addView(collectionChip)
            scroller.addView(chipRow)
            view.addView(scroller)

            val actions = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(4))
            }
            actions.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            actions.addView(Ui.chip(this@MainActivity, "▶ Play all", false) { if (items.isNotEmpty()) playFrom(items, 0) })
            actions.addView(Ui.chip(this@MainActivity, "Shuffle", false) {
                if (items.isNotEmpty()) {
                    prefs.shuffle = true
                    settingsChanged()
                    playFrom(items, (items.indices).random())
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
            view.addView(actions)

            list.apply {
                divider = null
                selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                setAdapter(adapter)
                setOnItemClickListener { _, _, pos, _ -> playFrom(items, pos) }
                setOnItemLongClickListener { _, _, pos, _ -> showItemActions(items[pos], items, pos); true }
                clipToPadding = false
                setPadding(0, 0, 0, dp(12))
            }
            val frame = FrameLayout(this@MainActivity)
            frame.addView(list)
            empty.apply {
                gravity = Gravity.CENTER
                setPadding(dp(32), dp(32), dp(32), dp(32))
                text = "No favourites yet.\n\nShare a video from TikTok to FloatTok, paste links, or import your TikTok data export from the Import tab."
            }
            frame.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            view.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        private fun updateChips() {
            sourceChips.forEach { (c, s) -> Ui.styleChip(c, s == sourceFilter) }
            Ui.styleChip(collectionChip, collectionFilter != null)
            collectionChip.text = collectionFilter?.let { "$it ▾" } ?: "Collection ▾"
        }

        private fun pickCollection() {
            bg.execute {
                val cols = lib.collections()
                main.post {
                    if (isFinishing || isDestroyed) return@post
                    if (cols.isEmpty()) { toast("No collections yet. Long-press a video to set one."); return@post }
                    val names = arrayOf("All collections") + cols.toTypedArray()
                    AlertDialog.Builder(this@MainActivity).setTitle("Collection").setItems(names) { _, w ->
                        collectionFilter = if (w == 0) null else cols[w - 1]
                        updateChips(); reload()
                    }.show()
                }
            }
        }

        fun reload() {
            val q = search.text.toString()
            val s = sourceFilter
            val c = collectionFilter
            val my = ++seq
            bg.execute {
                val result = lib.query(q, s, c)
                val total = lib.count()
                main.post {
                    if (my != seq) return@post
                    items = result
                    adapter.notifyDataSetChanged()
                    empty.visibility = if (total == 0) View.VISIBLE else View.GONE
                    info.text = if (result.size == total) "$total videos · imported, not auto-synced" else "${result.size} of $total videos"
                }
            }
        }

        private fun makeRow(): LinearLayout {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(6), dp(4), dp(6))
                minimumHeight = dp(84)
            }
            row.addView(thumbView(54, 72))
            val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(4), 0) }
            col.addView(Ui.text(this@MainActivity, "", 14f, Ui.TEXT).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            col.addView(Ui.text(this@MainActivity, "", 12f, Ui.TEXT_3).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0) })
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Ui.icon(this@MainActivity, R.drawable.ic_more, Ui.TEXT_3, 20, 10).apply { contentDescription = "More actions" })
            return row
        }

        private fun bindRow(row: LinearLayout, v: VideoItem, pos: Int) {
            val thumb = row.getChildAt(0) as ImageView
            val col = row.getChildAt(1) as LinearLayout
            val title = col.getChildAt(0) as TextView
            val meta = col.getChildAt(1) as TextView
            val more = row.getChildAt(2) as ImageView
            val isCurrent = PlaybackState.running && PlaybackState.currentId == v.id
            title.text = v.displayTitle()
            title.setTextColor(if (isCurrent) Ui.ACCENT else Ui.TEXT)
            meta.text = itemMeta(v)
            more.setOnClickListener { showItemActions(v, items, pos) }
            ImageLoader.load(this@MainActivity, v, thumb, dp(72))
            MetadataFetcher.get(this@MainActivity).request(v)
        }
    }

    // ================================================================ Queue

    private inner class QueuePage {
        val view = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        private val header = Ui.text(this@MainActivity, "Queue", 16f, Ui.TEXT, bold = true)
        private lateinit var autoChip: TextView
        private lateinit var shuffleChip: TextView
        private lateinit var repeatChip: TextView
        private val list = ListView(this@MainActivity)
        private val empty = Ui.text(this@MainActivity, "The queue is empty.\nTap a video in your library to start playing.", 14f, Ui.TEXT_2)
        private var ids: List<Long> = emptyList()
        private var itemMap: Map<Long, VideoItem> = emptyMap()

        private val adapter = object : BaseAdapter() {
            override fun getCount() = ids.size
            override fun getItem(position: Int) = ids[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val row = (convertView as? LinearLayout) ?: makeRow()
                bindRow(row, position)
                return row
            }
        }

        init {
            val top = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(8), dp(12), dp(4))
            }
            top.addView(header, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(Ui.chip(this@MainActivity, "Clear", false) {
                confirm("Clear queue?", "Your library is not affected.") { QueueOps.clear(this@MainActivity); reload() }
            })
            view.addView(top)

            val chips = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(4), dp(16), dp(8)) }
            autoChip = Ui.chip(this@MainActivity, "Autoplay", prefs.autoplay) { prefs.autoplay = !prefs.autoplay; settingsChanged(); refreshChips() }
            shuffleChip = Ui.chip(this@MainActivity, "Shuffle", prefs.shuffle) { prefs.shuffle = !prefs.shuffle; settingsChanged(); refreshChips() }
            repeatChip = Ui.chip(this@MainActivity, "", false) { prefs.repeat = prefs.repeat.next(); settingsChanged(); refreshChips() }
            val lpc = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) } }
            chips.addView(autoChip, lpc()); chips.addView(shuffleChip, lpc()); chips.addView(repeatChip, lpc())
            view.addView(chips)

            list.apply {
                divider = null
                selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                setAdapter(adapter)
                setOnItemClickListener { _, _, pos, _ -> if (ensureOverlay()) QueueOps.playIndex(this@MainActivity, pos) }
            }
            val frame = FrameLayout(this@MainActivity)
            frame.addView(list)
            empty.gravity = Gravity.CENTER
            empty.setPadding(dp(32), dp(32), dp(32), dp(32))
            frame.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            view.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            refreshChips()
        }

        fun refreshChips() {
            Ui.styleChip(autoChip, prefs.autoplay)
            Ui.styleChip(shuffleChip, prefs.shuffle)
            repeatChip.text = when (prefs.repeat) { RepeatMode.OFF -> "Repeat: off"; RepeatMode.ONE -> "Repeat: video"; RepeatMode.ALL -> "Repeat: queue" }
            Ui.styleChip(repeatChip, prefs.repeat != RepeatMode.OFF)
        }

        fun onPlaybackChanged() { refreshChips(); adapter.notifyDataSetChanged() }

        fun reload() {
            bg.execute {
                val q = lib.queueIds()
                val map = lib.getMany(q)
                main.post {
                    ids = q
                    itemMap = map
                    header.text = "Queue · ${q.size}"
                    empty.visibility = if (q.isEmpty()) View.VISIBLE else View.GONE
                    adapter.notifyDataSetChanged()
                    refreshChips()
                }
            }
        }

        private fun makeRow(): LinearLayout {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(4), dp(4), dp(4))
            }
            row.addView(Ui.text(this@MainActivity, "", 12f, Ui.TEXT_3).apply { minWidth = dp(28); gravity = Gravity.CENTER })
            row.addView(thumbView(36, 48))
            row.addView(Ui.text(this@MainActivity, "", 14f, Ui.TEXT).apply {
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(10), 0, dp(4), 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Ui.text(this@MainActivity, "↑", 18f, Ui.TEXT_2).apply { setPadding(dp(10), dp(8), dp(10), dp(8)); contentDescription = "Move up" })
            row.addView(Ui.text(this@MainActivity, "↓", 18f, Ui.TEXT_2).apply { setPadding(dp(10), dp(8), dp(10), dp(8)); contentDescription = "Move down" })
            row.addView(Ui.icon(this@MainActivity, R.drawable.ic_close, Ui.TEXT_3, 18, 9).apply { contentDescription = "Remove from queue" })
            return row
        }

        private fun bindRow(row: LinearLayout, pos: Int) {
            val id = ids[pos]
            val v = itemMap[id]
            val isCurrent = PlaybackState.currentId == id || (!PlaybackState.running && prefs.lastVideoRowId == id)
            (row.getChildAt(0) as TextView).apply { text = if (isCurrent) "▶" else "${pos + 1}"; setTextColor(if (isCurrent) Ui.ACCENT else Ui.TEXT_3) }
            if (v != null) ImageLoader.load(this@MainActivity, v, row.getChildAt(1) as ImageView, dp(48))
            (row.getChildAt(2) as TextView).apply { text = v?.displayTitle() ?: "(removed)"; setTextColor(if (isCurrent) Ui.ACCENT else Ui.TEXT) }
            row.getChildAt(3).setOnClickListener { if (pos > 0) { QueueOps.move(this@MainActivity, pos, pos - 1); reload() } }
            row.getChildAt(4).setOnClickListener { if (pos < ids.size - 1) { QueueOps.move(this@MainActivity, pos, pos + 1); reload() } }
            row.getChildAt(5).setOnClickListener { QueueOps.remove(this@MainActivity, pos); reload() }
            row.setBackgroundColor(if (isCurrent) Ui.ACCENT_DIM else Color.TRANSPARENT)
        }
    }

    // ================================================================ Import

    private inner class ImportPage {
        val view = ScrollView(this@MainActivity)
        private val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(24)) }
        private val progress = ProgressBar(this@MainActivity).apply { isIndeterminate = true; visibility = View.GONE }
        private val status = Ui.text(this@MainActivity, "", 13f, Ui.ACCENT)
        private val paste = EditText(this@MainActivity)
        private val metaStatus = Ui.text(this@MainActivity, "", 12f, Ui.TEXT_3)

        init {
            view.addView(col)
            val statusRow = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            statusRow.addView(progress, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(8) })
            statusRow.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            col.addView(statusRow, Ui.lpMatchWrap(this@MainActivity, 0, 4))

            section("Automatic sync with TikTok",
                "TikTok's official APIs for third-party apps (Login Kit and the Display API) only expose your own profile " +
                    "and the videos you posted. They don't give apps access to your Favourites, so FloatTok can't sync them " +
                    "automatically or sign in to read them. Use one of the methods below instead. Every video shows how it was " +
                    "imported.")

            section("Share from TikTok", "In TikTok tap Share → More (or the ⋯ / \"Other\" option) → FloatTok. " +
                "The video is added to your library and you can play it straight away.")

            card("Paste links").apply {
                paste.apply {
                    hint = "Paste one or more TikTok links"
                    setHintTextColor(Ui.TEXT_3)
                    setTextColor(Ui.TEXT)
                    textSize = 14f
                    minLines = 3
                    gravity = Gravity.TOP
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_URI
                    background = Ui.rounded(Ui.SURFACE_2, dp(10).toFloat(), Ui.STROKE, dp(1))
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }
                addView(paste, Ui.lpMatchWrap(this@MainActivity, 8, 8))
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(Ui.button(this@MainActivity, "Add") { addPasted(false) })
                row.addView(Ui.button(this@MainActivity, "Add & play", primary = true) { addPasted(true) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
                addView(row)
            }

            card("Import your TikTok data export").apply {
                addView(body("1. In TikTok: Profile → ☰ → Settings and privacy → Account → Download your data.\n" +
                    "2. Choose JSON (or TXT), select Activity (or everything) and request it.\n" +
                    "3. When TikTok says it's ready, download the file, then choose it here (ZIP, JSON or TXT).\n\n" +
                    "Only Favourite Videos (and collections, if present) are imported. Liked videos are ignored."))
                addView(Ui.button(this@MainActivity, "Choose export file", primary = true) { pickExport() }, wrapLp(10))
            }

            card("TikTok in a browser window (experimental)").apply {
                addView(body("Sign in to TikTok's own website inside FloatTok, open your profile's Favourites tab (or a " +
                    "collection) and import the videos shown on the page. FloatTok never sees your password: you type it " +
                    "into TikTok's page. TikTok may block sign-in or change its site, so this may not always work."))
                addView(Ui.button(this@MainActivity, "Open TikTok web") {
                    startActivity(Intent(this@MainActivity, TikTokWebActivity::class.java))
                }, wrapLp(10))
            }

            card("Videos saved on this phone").apply {
                addView(body("Add video files you have saved (for example videos you downloaded from TikTok)."))
                addView(Ui.button(this@MainActivity, "Add video files") { pickLocal() }, wrapLp(10))
            }

            card("Titles and thumbnails").apply {
                addView(body("Imported links start without titles. FloatTok fetches them from TikTok's public embed info as you scroll, or all at once here."))
                addView(Ui.button(this@MainActivity, "Fetch all now") { fetchAll() }, wrapLp(10))
                addView(metaStatus, Ui.lpMatchWrap(this@MainActivity, 6, 0))
            }

            card("Backup").apply {
                addView(body("Save your library and queue to a file, or restore it (for example after reinstalling)."))
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(Ui.button(this@MainActivity, "Export backup") { saveBackup() })
                row.addView(Ui.button(this@MainActivity, "Restore backup") { openBackup() },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
                addView(row, wrapLp(10))
                addView(Ui.button(this@MainActivity, "Clear library…") {
                    confirm("Clear the whole library?", "All imported videos and the queue will be removed from FloatTok.") { lib.deleteAll() }
                }.apply { setTextColor(Ui.DANGER) }, wrapLp(10))
            }
        }

        private fun wrapLp(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

        private fun body(s: String) = Ui.text(this@MainActivity, s, 13f, Ui.TEXT_2).apply { setPadding(0, dp(6), 0, 0); setLineSpacing(0f, 1.15f) }

        private fun card(title: String): LinearLayout {
            val c = Ui.card(this@MainActivity)
            c.addView(Ui.text(this@MainActivity, title, 15f, Ui.TEXT, bold = true))
            col.addView(c, Ui.lpMatchWrap(this@MainActivity, 6, 6))
            return c
        }

        private fun section(title: String, text: String) { card(title).addView(body(text)) }

        fun busy(msg: String?) {
            progress.visibility = if (msg != null) View.VISIBLE else View.GONE
            if (msg != null) status.text = msg
        }

        fun done(msg: String) {
            busy(null)
            status.text = msg
            view.smoothScrollTo(0, 0)
        }

        private fun addPasted(play: Boolean) {
            val text = paste.text.toString()
            if (text.isBlank()) return
            busy("Adding…")
            bg.execute {
                val s = Importer.importText(this@MainActivity, text, Source.PASTE)
                main.post {
                    done(s.describe())
                    if (s.found > 0) paste.setText("")
                    if (play && s.ids.isNotEmpty() && ensureOverlay()) {
                        val idx = QueueOps.insertNext(this@MainActivity, s.ids.first())
                        if (s.ids.size > 1) QueueOps.append(this@MainActivity, s.ids.drop(1))
                        QueueOps.playIndex(this@MainActivity, idx)
                    }
                }
            }
        }

        private fun pickExport() {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/json", "text/plain", "application/octet-stream", "application/x-zip-compressed"))
            }
            startActivityForResult(i, REQ_EXPORT)
        }

        fun onExportPicked(uri: Uri) {
            busy("Reading export… large files can take a minute")
            bg.execute {
                val msg = try {
                    Importer.importExport(this@MainActivity, uri).describe()
                } catch (e: Exception) {
                    "Couldn't read that file: ${e.message ?: e.javaClass.simpleName}"
                }
                main.post { done(msg); if (!msg.startsWith("Couldn't")) MetadataFetcher.get(this@MainActivity).fetchAllPending { _, _ -> } }
            }
        }

        private fun pickLocal() {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            startActivityForResult(i, REQ_LOCAL)
        }

        fun onLocalPicked(uris: List<Uri>) {
            var added = 0
            for (u in uris) {
                try {
                    contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
                if (lib.addLocal(u.toString(), Importer.displayName(this@MainActivity, u)).isNew) added++
            }
            done("$added local video(s) added.")
        }

        private fun fetchAll() {
            metaStatus.text = "Starting…"
            MetadataFetcher.get(this@MainActivity).fetchAllPending { done, total ->
                main.post { metaStatus.text = if (total == 0) "Nothing left to fetch." else if (done >= total) "Done ($total checked)." else "$done of $total…" }
            }
        }

        private fun saveBackup() {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "floattok-library.json")
            }, REQ_BACKUP_SAVE)
        }

        private fun openBackup() {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }, REQ_BACKUP_OPEN)
        }

        fun onBackupSave(uri: Uri) {
            busy("Saving backup…")
            bg.execute {
                val msg = try {
                    contentResolver.openOutputStream(uri, "wt")?.use { lib.exportBackup(it) }
                    "Backup saved."
                } catch (e: Exception) { "Backup failed: ${e.message}" }
                main.post { done(msg) }
            }
        }

        fun onBackupOpen(uri: Uri) {
            busy("Restoring…")
            bg.execute {
                val msg = try {
                    val (a, d) = contentResolver.openInputStream(uri)!!.use { lib.importBackup(it) }
                    "Restored $a videos ($d already present)."
                } catch (e: Exception) { "Restore failed: ${e.message}" }
                main.post { done(msg) }
            }
        }
    }

    // ================================================================ Settings

    private inner class SettingsPage {
        val view = ScrollView(this@MainActivity)
        private val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(24)) }
        private val refreshers = ArrayList<() -> Unit>()

        init {
            view.addView(col)
            heading("Playback")
            switchRow("Autoplay next video", "On: the next video in the queue starts when one ends. Off: stop after the current video.",
                { prefs.autoplay }) { prefs.autoplay = it }
            switchRow("Shuffle", null, { prefs.shuffle }) { prefs.shuffle = it }
            segRow("Repeat", listOf("Off", "This video", "Queue"), { prefs.repeat.ordinal }) { prefs.repeat = RepeatMode.entries[it] }
            val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
            segRow("Speed (TikTok direct mode and local files)", speeds.map { "${it}×".replace(".0×", "×") },
                { speeds.indexOfFirst { s -> kotlin.math.abs(s - prefs.speed) < 0.01f }.coerceAtLeast(2) }) { prefs.speed = speeds[it] }
            switchRow("Mute", null, { prefs.muted }) { prefs.muted = it }
            switchRow("Resume last video and position", null, { prefs.resumeLast }) { prefs.resumeLast = it }
            switchRow("Audio only", "Shrinks the player to a small bar; the video keeps playing hidden.", { prefs.audioOnly }) { prefs.audioOnly = it }

            heading("Floating window")
            seekRow("Opacity", 20, 100, { (prefs.opacity * 100).roundToInt() }) { prefs.opacity = it / 100f }
            segRow("Video shape", listOf("Original", "Fit", "Crop"), { prefs.videoMode.ordinal }) { prefs.videoMode = VideoMode.entries[it] }
            switchRow("Aspect ratio lock", "Keep the window's shape while resizing (Fit and Crop modes). Original mode always follows the video.",
                { prefs.aspectLock }) { prefs.aspectLock = it }
            segRow("When docked to the edge", listOf("Keep playing", "Pause"), { if (prefs.audioWhileDocked) 0 else 1 }) { prefs.audioWhileDocked = it == 0 }
            switchRow("Lock size and position", null, { prefs.positionLocked }) { prefs.positionLocked = it }
            val timeouts = listOf(2000, 3000, 5000, 8000)
            segRow("Hide controls after", listOf("2 s", "3 s", "5 s", "8 s"), { timeouts.indexOf(prefs.controlsTimeoutMs).coerceAtLeast(1) }) {
                prefs.controlsTimeoutMs = timeouts[it]
            }
            col.addView(Ui.button(this@MainActivity, "Reset window size and position") {
                prefs.resetWindow(); toast("The player will open at its default size next time.")
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

            heading("TikTok player")
            segRow("Player mode", listOf("Direct", "Embedded"), { prefs.tiktokEmbedMode }) { prefs.tiktokEmbedMode = it }
            note("Both use TikTok's official embed player. Direct loads it on its own so FloatTok can change speed, " +
                "loop and fit/crop. Embedded uses only TikTok's documented player messages; try it if Direct misbehaves.")

            heading("Permissions")
            val permText = Ui.text(this@MainActivity, "", 13f, Ui.TEXT_2)
            col.addView(permText)
            refreshers.add {
                val overlay = Settings.canDrawOverlays(this@MainActivity)
                val notif = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                permText.text = "Display over other apps: ${if (overlay) "allowed" else "not allowed"}\nNotifications: ${if (notif) "allowed" else "not allowed"}"
            }
            col.addView(Ui.button(this@MainActivity, "Open overlay permission") { openOverlaySettings() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

            heading("About")
            val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
            note("FloatTok $version. A personal utility; not affiliated with TikTok. No ads, no accounts, no tracking. " +
                "Your library stays on this phone.\n\nTikTok doesn't let third-party apps read your Favourites, so videos " +
                "are imported (share, paste, data export, or TikTok's website) rather than synced.")
        }

        private fun heading(s: String) {
            col.addView(Ui.text(this@MainActivity, s.uppercase(), 12f, Ui.ACCENT, bold = true).apply { letterSpacing = 0.08f },
                Ui.lpMatchWrap(this@MainActivity, 18, 6))
        }

        private fun note(s: String) = col.addView(Ui.text(this@MainActivity, s, 12f, Ui.TEXT_3).apply { setLineSpacing(0f, 1.15f) }, Ui.lpMatchWrap(this@MainActivity, 4, 4))

        private fun switchRow(title: String, sub: String?, get: () -> Boolean, set: (Boolean) -> Unit) {
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
            val texts = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(Ui.text(this@MainActivity, title, 15f, Ui.TEXT))
            if (sub != null) texts.addView(Ui.text(this@MainActivity, sub, 12f, Ui.TEXT_3).apply { setPadding(0, dp(2), dp(8), 0) })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val sw = Switch(this@MainActivity)
            row.addView(sw)
            var updating = false
            sw.setOnCheckedChangeListener { _, checked -> if (!updating) { set(checked); settingsChanged(); queuePage.refreshChips() } }
            row.setOnClickListener { sw.toggle() }
            refreshers.add { updating = true; sw.isChecked = get(); updating = false }
            col.addView(row)
        }

        private fun segRow(title: String, options: List<String>, get: () -> Int, set: (Int) -> Unit) {
            col.addView(Ui.text(this@MainActivity, title, 15f, Ui.TEXT), Ui.lpMatchWrap(this@MainActivity, 8, 6))
            val scroller = HorizontalScrollView(this@MainActivity).apply { isHorizontalScrollBarEnabled = false }
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            val chips = options.mapIndexed { i, label ->
                Ui.chip(this@MainActivity, label, false) { set(i); settingsChanged(); refresh(); queuePage.refreshChips() }.also {
                    row.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
                }
            }
            scroller.addView(row)
            col.addView(scroller)
            refreshers.add { val sel = get(); chips.forEachIndexed { i, c -> Ui.styleChip(c, i == sel) } }
        }

        private fun seekRow(title: String, min: Int, max: Int, get: () -> Int, set: (Int) -> Unit) {
            val label = Ui.text(this@MainActivity, title, 15f, Ui.TEXT)
            col.addView(label, Ui.lpMatchWrap(this@MainActivity, 8, 0))
            val sb = SeekBar(this@MainActivity).apply { this.max = max - min }
            var updating = false
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    label.text = "$title  ${p + min}%"
                    if (fromUser && !updating) { set(p + min); settingsChanged() }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            col.addView(sb, Ui.lpMatchWrap(this@MainActivity, 2, 4))
            refreshers.add { updating = true; sb.progress = get() - min; label.text = "$title  ${get()}%"; updating = false }
        }

        fun refresh() = refreshers.forEach { it() }
    }

    // ---------------------------------------------------------------- results

    @Deprecated("Framework Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_EXPORT -> data.data?.let { importPage.onExportPicked(it) }
            REQ_LOCAL -> {
                val uris = ArrayList<Uri>()
                data.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri) }
                if (uris.isEmpty()) data.data?.let { uris.add(it) }
                importPage.onLocalPicked(uris)
            }
            REQ_BACKUP_SAVE -> data.data?.let { importPage.onBackupSave(it) }
            REQ_BACKUP_OPEN -> data.data?.let { importPage.onBackupOpen(it) }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissionCards()
    }
}
