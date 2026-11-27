package app.floattok.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import app.floattok.R
import app.floattok.data.Importer
import app.floattok.data.MetadataFetcher
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.Executors

/**
 * Experimental: TikTok's own website in a WebView. The user signs in on TikTok's page (FloatTok never sees the
 * password and stores no tokens), opens their Favourites tab or a collection, and imports the links shown.
 */
@SuppressLint("SetJavaScriptEnabled")
class TikTokWebActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private lateinit var web: WebView
    private lateinit var titleView: TextView
    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var desktopChip: TextView
    private lateinit var scrollBtn: TextView
    private var desktop = false
    private var defaultUa = ""
    private var scrolling = false

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        setContentView(buildUi())
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }
        web.loadUrl("https://www.tiktok.com/")
    }

    private fun goBack() {
        if (web.canGoBack()) web.goBack() else finish()
    }

    @Deprecated("Used below Android 13 only")
    override fun onBackPressed() = goBack()

    override fun onDestroy() {
        web.stopLoading()
        web.removeJavascriptInterface("FloatTokWeb")
        web.destroy()
        bg.shutdown()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(8), dp(4))
            setBackgroundColor(Ui.SURFACE)
        }
        top.addView(Ui.icon(this, R.drawable.ic_close, Ui.TEXT, 22, 9).apply { contentDescription = "Close"; setOnClickListener { finish() } })
        titleView = Ui.text(this, "TikTok", 14f, Ui.TEXT, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        top.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        desktopChip = Ui.chip(this, "Desktop", false) { toggleDesktop() }
        top.addView(desktopChip)
        top.addView(Ui.icon(this, R.drawable.ic_more, Ui.TEXT, 22, 9).apply { contentDescription = "More"; setOnClickListener { showMenu() } })
        root.addView(top)

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT) }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            defaultUa = settings.userAgentString
            addJavascriptInterface(WebBridge(), "FloatTokWeb")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme ?: return true
                    // Stay in the browser: ignore attempts to open the TikTok app (intent://, snssdk://).
                    return scheme != "https" && scheme != "http"
                }
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { bar.visibility = View.VISIBLE }
                override fun onPageFinished(view: WebView, url: String) { bar.visibility = View.GONE; updateTitle() }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) { bar.progress = newProgress }
                override fun onReceivedTitle(view: WebView, title: String?) { updateTitle() }
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.SURFACE)
            setPadding(dp(14), dp(8), dp(14), dp(10))
        }
        status = Ui.text(this, "Sign in on TikTok's page, open your profile → Favourites (bookmark icon) or a collection, then import.", 12f, Ui.TEXT_2)
        bottom.addView(status)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(8), 0, 0) }
        scrollBtn = Ui.button(this, "Scroll & collect") { toggleAutoScroll() }
        row.addView(scrollBtn)
        row.addView(Ui.button(this, "Import this page", primary = true) { collect() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        bottom.addView(row)
        root.addView(bottom)
        return root
    }

    private fun updateTitle() {
        titleView.text = web.title?.takeIf { it.isNotBlank() } ?: "TikTok"
    }

    private fun toggleDesktop() {
        desktop = !desktop
        web.settings.userAgentString = if (desktop)
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        else defaultUa
        web.settings.useWideViewPort = desktop
        web.settings.loadWithOverviewMode = desktop
        Ui.styleChip(desktopChip, desktop)
        web.reload()
    }

    private fun showMenu() {
        AlertDialog.Builder(this).setItems(arrayOf("Go to TikTok home", "Sign out and clear TikTok web data")) { _, w ->
            when (w) {
                0 -> web.loadUrl("https://www.tiktok.com/")
                1 -> {
                    CookieManager.getInstance().removeAllCookies(null)
                    WebStorage.getInstance().deleteAllData()
                    web.clearCache(true)
                    web.loadUrl("https://www.tiktok.com/")
                    Toast.makeText(this, "Signed out and cleared", Toast.LENGTH_SHORT).show()
                }
            }
        }.show()
    }

    // ---------------------------------------------------------------- collecting

    private val collectJs = """
(function(){
  window.__ftItems = window.__ftItems || {};
  var items = window.__ftItems;
  document.querySelectorAll('a[href*="/video/"], a[href*="/photo/"]').forEach(function(a){
    var h = a.href || ''; var m = h.match(/\/(video|photo)\/(\d{6,25})/); if (!m) return;
    if (items[m[2]]) return;
    var img = a.querySelector('img');
    var t = (img && img.alt) || a.getAttribute('title') || a.getAttribute('aria-label') || '';
    items[m[2]] = {href: h.split('?')[0], title: t.trim().slice(0, 300), thumb: img ? img.src : null};
  });
  return Object.keys(items).length;
})()
""".trimIndent()

    private val resultJs = """
(function(){
  var items = window.__ftItems || {};
  var out = Object.keys(items).map(function(k){ return items[k]; });
  var c = null, mm = location.pathname.match(/\/collection\/(.+)-(\d+)/);
  if (mm) { try { c = decodeURIComponent(mm[1]); } catch(e) { c = mm[1]; } }
  return JSON.stringify({items: out, collection: c, path: location.pathname});
})()
""".trimIndent()

    private inner class WebBridge {
        @JavascriptInterface
        fun onProgress(count: Int) { main.post { if (scrolling) status.text = "Scrolling… $count videos found so far" } }

        @JavascriptInterface
        fun onScrollDone(count: Int) { main.post { scrolling = false; scrollBtn.text = "Scroll & collect"; status.text = "Finished scrolling: $count videos found."; collect() } }
    }

    private fun toggleAutoScroll() {
        if (scrolling) {
            web.evaluateJavascript("window.__ftScroll=false;", null)
            scrolling = false
            scrollBtn.text = "Scroll & collect"
            return
        }
        scrolling = true
        scrollBtn.text = "Stop"
        val js = """
(function(){
  window.__ftScroll = true;
  var last = -1, same = 0, rounds = 0;
  function count(){ return ($collectJs); }
  function step(){
    if (!window.__ftScroll) return;
    var el = document.scrollingElement || document.documentElement;
    window.scrollTo(0, el.scrollHeight);
    var n = count(); FloatTokWeb.onProgress(n);
    if (n === last) same++; else { same = 0; last = n; }
    rounds++;
    if (same >= 6 || rounds > 900) { window.__ftScroll = false; FloatTokWeb.onScrollDone(n); return; }
    setTimeout(step, 1300);
  }
  step();
})();
""".trimIndent()
        web.evaluateJavascript(js, null)
    }

    private fun collect() {
        web.evaluateJavascript(collectJs) {
            web.evaluateJavascript(resultJs) { raw ->
                try {
                    val s = JSONTokener(raw).nextValue() as? String ?: throw IllegalStateException()
                    val o = JSONObject(s)
                    val arr = o.getJSONArray("items")
                    val list = (0 until arr.length()).map { i ->
                        val it = arr.getJSONObject(i)
                        Triple(it.getString("href"), it.optString("title").takeIf { t -> t.isNotBlank() },
                            if (it.isNull("thumb")) null else it.optString("thumb").takeIf { t -> t.startsWith("http") })
                    }
                    val detected = if (o.isNull("collection")) null else o.optString("collection")
                    confirmImport(list, detected, o.optString("path"))
                } catch (_: Exception) {
                    status.text = "Couldn't read this page. Make sure it has finished loading."
                }
            }
        }
    }

    private fun confirmImport(list: List<Triple<String, String?, String?>>, detected: String?, path: String) {
        if (list.isEmpty()) {
            status.text = "No videos found on this page yet. Open your Favourites tab and let it load (or use Scroll & collect)."
            return
        }
        val input = EditText(this).apply {
            hint = "Collection (optional)"
            setText(detected ?: "")
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Import ${list.size} videos?")
            .setMessage("Only import from your Favourites tab or a favourites collection: FloatTok can't tell which page this is ($path).")
            .setView(FrameLayout(this).apply { setPadding(dp(20), 0, dp(20), 0); addView(input) })
            .setPositiveButton("Import") { _, _ ->
                val col = input.text.toString().trim().ifBlank { null }
                bg.execute {
                    val s = Importer.importWeb(this, list, col)
                    main.post {
                        status.text = s.describe()
                        Toast.makeText(this, s.describe(), Toast.LENGTH_LONG).show()
                        MetadataFetcher.get(this).fetchAllPending { _, _ -> }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
