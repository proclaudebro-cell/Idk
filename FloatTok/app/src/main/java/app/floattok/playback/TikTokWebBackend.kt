package app.floattok.playback

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import app.floattok.data.VideoItem
import app.floattok.data.VideoMode
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Plays TikTok videos with TikTok's official embed player (https://www.tiktok.com/player/v1/{id}).
 *
 * Mode 0 ("direct") loads the player page itself, so FloatTok can also reach its <video> element for speed,
 * looping and fit/crop. Mode 1 ("iframe") embeds it in a local page and uses only TikTok's documented
 * postMessage API (play, pause, seekTo, mute, unMute; onPlayerReady, onStateChange, onCurrentTime, onPlayerError).
 */
@SuppressLint("SetJavaScriptEnabled")
class TikTokWebBackend(private val context: Context, private val embedMode: Int) : PlayerBackend {

    private val main = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private var passthrough = false
    private val container = object : FrameLayout(context) {
        // Normally the floating window handles every touch and the page never receives them.
        override fun onInterceptTouchEvent(ev: android.view.MotionEvent?) = !passthrough
    }.apply { setBackgroundColor(Color.BLACK) }
    private var web: WebView = createWebView()

    override val view: View get() = container
    override val supportsSpeed: Boolean get() = embedMode == 0
    override var listener: PlayerBackend.Listener? = null

    private var token = 0
    private var item: VideoItem? = null
    private var startMs = 0L
    private var wantPlay = true
    private var ready = false
    private var playing = false
    private var endedReported = false
    private var startApplied = false
    private var durationMs = 0L
    private var lastPosMs = 0L
    private var speed = 1f
    private var loop = false
    private var muted = false
    private var mode = VideoMode.ORIGINAL
    private var viewportW = 0
    private var viewportH = 0

    private val loadTimeout = Runnable {
        if (!ready) listener?.onError("This TikTok video didn't load. It may be private, deleted or region-locked.")
    }

    private fun createWebView(): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.javaScriptCanOpenWindowsAutomatically = false
        setBackgroundColor(Color.BLACK)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isFocusable = false
        isFocusableInTouchMode = false
        addJavascriptInterface(Bridge(), "FloatTokBridge")
        webViewClient = Client()
        webChromeClient = WebChromeClient()
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        container.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        applyViewportScale(this)
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame || request.isRedirect) return false
            val u = request.url.toString()
            // Stay on the player: block script navigation to TikTok pages (author links, "watch on TikTok").
            return !u.contains("/player/")
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (embedMode == 0 && url.contains("/player/v1/")) view.evaluateJavascript(controllerJs(token), null)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Chromium renderer died: replace the WebView instead of letting the app crash.
            container.removeView(view)
            view.destroy()
            web = createWebView()
            ready = false
            listener?.onError("The video player crashed and was restarted.")
            return true
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onEvent(eventToken: Int, type: String, json: String) {
            main.post { if (eventToken == token) handle(type, json) }
        }
    }

    // ---------------------------------------------------------------- loading

    override fun load(item: VideoItem, startMs: Long, autoplay: Boolean) {
        val id = item.videoId ?: run { listener?.onError("This link hasn't been resolved to a TikTok video yet."); return }
        token++
        this.item = item
        this.startMs = startMs
        wantPlay = autoplay
        ready = false
        playing = false
        endedReported = false
        startApplied = startMs <= 0
        durationMs = item.durationMs
        lastPosMs = 0
        main.removeCallbacks(loadTimeout)
        main.postDelayed(loadTimeout, 25_000)
        val url = playerUrl(id)
        if (embedMode == 0) {
            web.loadUrl(url)
        } else {
            web.loadDataWithBaseURL("https://floattok.local/", iframeHtml(url, token), "text/html", "utf-8", null)
        }
    }

    private fun playerUrl(id: String): String {
        // TikTok builds its player lazily and ignores commands until playback starts, so always autoplay
        // and pause straight away if the user didn't ask to play.
        val p = linkedMapOf(
            "autoplay" to "1", "loop" to "0", "controls" to "0", "progress_bar" to "0", "play_button" to "0",
            "volume_control" to "0", "fullscreen_button" to "0", "timestamp" to "0", "music_info" to "0",
            "description" to "0", "rel" to "0", "native_context_menu" to "0", "closed_caption" to "0",
        )
        if (muted) p["muted"] = "1"
        return "https://www.tiktok.com/player/v1/$id?" + p.entries.joinToString("&") { "${it.key}=${it.value}" }
    }

    // ---------------------------------------------------------------- events

    private fun handle(type: String, json: String) {
        when (type) {
            "tt:onPlayerReady" -> markReady()
            "tt:onStateChange" -> when (json.trim().toIntOrNull()) {
                1 -> { markReady(); listener?.onBuffering(false); setPlaying(true) }
                2 -> setPlaying(false)
                3 -> listener?.onBuffering(true)
                0 -> onEndedEvent()
            }
            "tt:onCurrentTime" -> obj(json)?.let { o ->
                markReady()
                progress((o.optDouble("currentTime", 0.0) * 1000).toLong(), (o.optDouble("duration", 0.0) * 1000).toLong())
            }
            "tt:onPlayerError", "tt:onError" -> {
                val code = obj(json)?.optInt("errorCode", 0) ?: 0
                if (code == 3002) {
                    // Autoplay refused: retry muted so at least the video runs; the user can unmute.
                    js("window.__ft&&__ft.mute(true);window.__ft&&__ft.play()")
                } else {
                    listener?.onError("TikTok couldn't play this video" + if (code != 0) " (error $code)." else ".")
                }
            }
            "v:attached", "v:loadedmetadata" -> obj(json)?.let { o ->
                val w = o.optInt("w"); val h = o.optInt("h")
                if (w > 0 && h > 0) listener?.onVideoSize(w, h)
                markReady()
                progress((o.optDouble("t") * 1000).toLong(), (o.optDouble("d") * 1000).toLong())
                if (o.optBoolean("p")) setPlaying(true)
            }
            "v:play", "v:playing" -> { listener?.onBuffering(false); setPlaying(true) }
            "v:pause" -> obj(json)?.let { if (!it.optBoolean("e")) setPlaying(false) }
            "v:waiting" -> listener?.onBuffering(true)
            "v:ended" -> onEndedEvent()
            "v:time" -> obj(json)?.let { o ->
                progress((o.optDouble("t") * 1000).toLong(), (o.optDouble("d") * 1000).toLong())
                val p = o.optBoolean("p")
                if (p != playing && !o.optBoolean("e")) setPlaying(p)
            }
            "v:error" -> listener?.onError("This TikTok video failed to play.")
        }
    }

    private fun obj(json: String): JSONObject? = try { JSONObject(json) } catch (_: Exception) { null }

    private fun markReady() {
        if (ready) return
        ready = true
        main.removeCallbacks(loadTimeout)
        applyAll()
        if (!startApplied && startMs > 0) {
            startApplied = true
            seekTo(startMs)
        }
        if (wantPlay) play() else pause()
        listener?.onReady(durationMs)
    }

    private fun setPlaying(p: Boolean) {
        if (p && !wantPlay && ready) {
            // The embed autoplays; honour a paused start.
            js("window.__ft&&__ft.pause()")
            return
        }
        if (p) endedReported = false
        if (playing == p) return
        playing = p
        listener?.onPlayingChanged(p)
    }

    private fun progress(pos: Long, dur: Long) {
        if (dur > 0) durationMs = dur
        lastPosMs = pos
        listener?.onProgress(pos, durationMs)
    }

    private fun onEndedEvent() {
        if (loop) {
            // Iframe mode can't set video.loop, so restart by hand.
            seekTo(0); play(); return
        }
        if (endedReported) return
        endedReported = true
        playing = false
        listener?.onPlayingChanged(false)
        listener?.onEnded()
    }

    // ---------------------------------------------------------------- commands

    private fun js(code: String) = web.evaluateJavascript(code, null)

    override fun play() {
        wantPlay = true
        js("window.__ft&&__ft.play()")
    }

    override fun pause() {
        wantPlay = false
        js("window.__ft&&__ft.pause()")
    }

    override fun seekTo(ms: Long) {
        endedReported = false
        js("window.__ft&&__ft.seek(${ms / 1000.0})")
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed
        js("window.__ft&&__ft.rate($speed)")
    }

    override fun setLoop(loop: Boolean) {
        this.loop = loop
        js("window.__ft&&__ft.loop($loop)")
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        js("window.__ft&&__ft.mute($muted)")
    }

    override fun setVideoMode(mode: VideoMode) {
        this.mode = mode
        js("window.__ft&&__ft.fit('${if (mode == VideoMode.CROP) "cover" else "contain"}')")
    }

    private fun applyAll() {
        setSpeed(speed); setLoop(loop); setMuted(muted); setVideoMode(mode)
    }

    override fun setViewport(width: Int, height: Int) {
        viewportW = width
        viewportH = height
        applyViewportScale(web)
    }

    /**
     * TikTok's player page lays out poorly below ~300 CSS px wide. For small windows the WebView is laid out at
     * a comfortable size and scaled down, so a thumbnail-sized window still shows the whole video.
     */
    private fun applyViewportScale(w: WebView) {
        val minPx = (300 * density).roundToInt()
        if (viewportW <= 0 || viewportH <= 0) return
        val scale = if (viewportW < minPx) viewportW.toFloat() / minPx else 1f
        val lp = w.layoutParams as FrameLayout.LayoutParams
        val lw = if (scale < 1f) minPx else ViewGroup.LayoutParams.MATCH_PARENT
        val lh = if (scale < 1f) (viewportH / scale).roundToInt() else ViewGroup.LayoutParams.MATCH_PARENT
        if (lp.width != lw || lp.height != lh) {
            lp.width = lw
            lp.height = lh
            w.layoutParams = lp
        }
        w.pivotX = 0f
        w.pivotY = 0f
        w.scaleX = scale
        w.scaleY = scale
    }

    override fun setTouchPassthrough(enabled: Boolean) { passthrough = enabled }

    override fun stop() {
        token++
        main.removeCallbacks(loadTimeout)
        ready = false
        playing = false
        item = null
        web.loadUrl("about:blank")
    }

    override fun release() {
        stop()
        main.removeCallbacksAndMessages(null)
        web.removeJavascriptInterface("FloatTokBridge")
        container.removeView(web)
        web.destroy()
        listener = null
    }

    // ---------------------------------------------------------------- page scripts

    private fun controllerJs(t: Int) = """
(function(){
  var TOKEN=$t;
  if (window.__ft && window.__ftToken===TOKEN) return;
  window.__ftToken=TOKEN;
  var B=window.FloatTokBridge;
  var st={rate:1,loop:false,muted:false,fit:'contain'};
  var vid=null;
  function send(t,v){try{B.onEvent(TOKEN,t,JSON.stringify(v===undefined?null:v));}catch(e){}}
  function post(type,value){var m={'x-tiktok-player':true,type:type};if(typeof value==='number')m.value=value;try{window.postMessage(m,'*');}catch(e){}}
  var CMDS={play:1,pause:1,seekTo:1,mute:1,unMute:1};
  window.addEventListener('message',function(e){var d=e.data;if(!d||typeof d!=='object'||!d['x-tiktok-player']||CMDS[d.type])return;send('tt:'+d.type,d.value===undefined?null:d.value);});
  function info(){if(!vid)return null;return {t:vid.currentTime||0,d:isFinite(vid.duration)?vid.duration:0,p:!vid.paused&&!vid.ended,e:vid.ended,w:vid.videoWidth||0,h:vid.videoHeight||0};}
  function css(){var s=document.getElementById('__ftcss');if(!s){s=document.createElement('style');s.id='__ftcss';(document.head||document.documentElement).appendChild(s);}
    s.textContent='html,body{background:#000 !important;overflow:hidden !important;} video{object-fit:'+st.fit+' !important;}';}
  function attach(v){vid=v;try{v.playbackRate=st.rate;v.loop=st.loop;v.muted=st.muted;}catch(e){}
    ['play','playing','pause','ended','waiting','loadedmetadata','error'].forEach(function(n){v.addEventListener(n,function(){send('v:'+n,info());});});
    send('v:attached',info());}
  setInterval(function(){var v=document.querySelector('video');if(v&&v!==vid)attach(v);
    if(vid){try{if(vid.playbackRate!==st.rate)vid.playbackRate=st.rate;if(vid.loop!==st.loop)vid.loop=st.loop;}catch(e){}send('v:time',info());}},500);
  window.__ft={
    play:function(){if(vid){var p=vid.play();if(p&&p.catch)p.catch(function(){post('play');});}else post('play');},
    pause:function(){if(vid)vid.pause();else post('pause');},
    seek:function(s){if(vid){try{vid.currentTime=s;}catch(e){post('seekTo',s);}}else post('seekTo',s);},
    rate:function(r){st.rate=r;if(vid)vid.playbackRate=r;},
    loop:function(b){st.loop=b;if(vid)vid.loop=b;},
    mute:function(b){st.muted=b;if(vid)vid.muted=b;post(b?'mute':'unMute');},
    fit:function(f){st.fit=f;css();}
  };
  css();
  send('ready',null);
})();
""".trimIndent()

    private fun iframeHtml(src: String, t: Int) = """
<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{border:0;width:100%;height:100%;display:block}</style></head>
<body><iframe id="p" src="$src" allow="autoplay; encrypted-media; fullscreen; picture-in-picture"></iframe>
<script>
var TOKEN=$t,B=window.FloatTokBridge,f=document.getElementById('p');
function post(type,value){var m={'x-tiktok-player':true,type:type};if(typeof value==='number')m.value=value;f.contentWindow.postMessage(m,'*');}
window.addEventListener('message',function(e){var d=e.data;if(!d||typeof d!=='object'||!d['x-tiktok-player'])return;
  try{B.onEvent(TOKEN,'tt:'+d.type,JSON.stringify(d.value===undefined?null:d.value));}catch(x){}});
window.__ft={play:function(){post('play')},pause:function(){post('pause')},seek:function(s){post('seekTo',s)},
  rate:function(){},loop:function(){},mute:function(b){post(b?'mute':'unMute')},fit:function(){}};
</script></body></html>
""".trimIndent()
}
