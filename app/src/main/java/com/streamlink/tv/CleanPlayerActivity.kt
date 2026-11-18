package com.streamlink.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * The "clean browser player": plays the already-found stream in a browser engine with hls.js, on
 * a blank page of ours (assets/clean.html), with none of the site's ads, overlays or chat.
 * Middle step of the fallback chain: built-in player -> this -> the site's own page.
 *
 * Every request the page makes outside our own assets is intercepted and fetched natively with
 * the same network stack and headers as the built-in player (see [CleanFetcher]); the web view
 * itself never talks to the network.
 *
 * Remote: OK / play-pause toggles, Back returns to the link screen. Phone commands: toggle, play,
 * pause, live, reload, stop, quality, source, site.
 */
class CleanPlayerActivity : Activity() {

    companion object {
        private const val EXTRA_SOURCE = "source"
        /** If the page hasn't started playing by now, give up (the page's own watchdog is 25s). */
        private const val NO_START_MS = 40_000L

        fun intent(ctx: Context, sourceIndex: Int = 0): Intent =
            Intent(ctx, CleanPlayerActivity::class.java).putExtra(EXTRA_SOURCE, sourceIndex)
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var cover: TextView
    private var web: WebView? = null
    private var assets: WebViewAssetLoader? = null

    private var set: StreamSet? = null
    private var sourceIndex = 0
    private var fetcher: CleanFetcher? = null
    /** Bumped on every (re)load, so late callbacks from a previous page load are ignored. */
    @Volatile private var gen = 0
    private var started = false
    private var leaving = false
    private val fetchLogged = AtomicInteger(0)
    private val fetchFailures = AtomicInteger(0)

    private class PageState(
        val playing: Boolean, val buffering: Boolean, val live: Boolean,
        val levels: List<String>, val quality: Int,
    )
    private var page: PageState? = null

    private val handler: (String, String?) -> Unit = { c, v -> main.post { onCommand(c, v) } }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val s = Session.current
        if (s == null || s.sources.isEmpty()) return finish()
        set = s
        sourceIndex = intent.getIntExtra(EXTRA_SOURCE, 0).coerceIn(0, s.sources.size - 1)

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        setContentView(root)

        val w = try {
            WebView(this)
        } catch (t: Throwable) {
            Report.log("Clean browser player: no browser engine available (${(t.message ?: t.javaClass.simpleName).take(100)})")
            return openSite("the browser engine isn't available")
        }
        web = w
        assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        // Same hardening as every web view of the app, for our own page.
        SafeWeb.configure(w)
        w.settings.allowFileAccess = false
        w.settings.allowContentAccess = false
        w.isFocusable = false
        w.isFocusableInTouchMode = false
        w.setBackgroundColor(Color.BLACK)
        w.addJavascriptInterface(Bridge(), "SL")
        w.webChromeClient = object : SafeChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage?): Boolean {
                if (m != null && m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    val text = m.message().take(160)
                    Report.logOnce("clean-console-${text.hashCode()}", "Clean page console: $text")
                }
                return true
            }
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return true
                val ok = CleanProxy.isAssetUrl(u.host, u.path) && u.scheme == "https"
                if (!ok) Report.logOnce("clean-nav-${u.host}", "Clean player: blocked a navigation to ${u.host}")
                return !ok
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                request?.let { intercept(it) }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                main.post { fail("the browser engine crashed") }
                return true
            }
        }
        root.addView(w, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        cover = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
        }
        root.addView(cover, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        Session.mode = "clean"
        if (Session.listId == null || Session.listItemUrl == null || Session.title.isBlank()) Session.title = Report.host(s.pageUrl)
        Session.snapshot = null
        Session.commandHandler = handler
        main.post(snapshotTick)
        load()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val s = Session.current ?: return
        if (web == null) return
        set = s
        sourceIndex = intent.getIntExtra(EXTRA_SOURCE, 0).coerceIn(0, (s.sources.size - 1).coerceAtLeast(0))
        Session.mode = "clean"
        if (Session.listId == null || Session.listItemUrl == null || Session.title.isBlank()) Session.title = Report.host(s.pageUrl)
        Session.commandHandler = handler
        load()
    }

    override fun onStop() {
        super.onStop()
        finish()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        web?.let {
            it.stopLoading()
            it.loadUrl("about:blank")
            root.removeView(it)
            it.destroy()
        }
        web = null
        if (Session.commandHandler === handler) {
            Session.commandHandler = null
            Session.mode = "idle"
            Session.snapshot = null
            AppStatus.text = "Waiting for a link"
        }
        super.onDestroy()
    }

    // ---- Loading ----

    private fun load() {
        val s = set ?: return
        val src = s.sources.getOrNull(sourceIndex) ?: return openSite("no playable source")
        val w = web ?: return
        gen++
        started = false
        page = null
        fetchLogged.set(0)
        fetchFailures.set(0)
        fetcher = CleanFetcher(src)
        val referer = src.referers.firstOrNull() ?: ""
        Report.log(
            "Clean browser player: source ${sourceIndex + 1}/${s.sources.size} (${src.label}), " +
                if (referer.isEmpty()) "no referer" else "referer ${Report.short(referer)}"
        )
        Report.logOnce("clean-net-${ChromeNet.engine != null}", "Clean player network: ${ChromeNet.describe()}")
        AppStatus.text = "Starting ${Session.title} (clean browser player)…"
        showCover("Loading…")
        main.removeCallbacks(noStart)
        main.postDelayed(noStart, NO_START_MS)
        w.loadUrl(CleanProxy.PAGE_URL)
    }

    private val noStart = Runnable {
        if (!started) fail("no picture after ${NO_START_MS / 1000}s")
    }

    private fun fail(reason: String) {
        if (leaving) return
        Report.log("Clean browser player failed: $reason")
        openSite(reason)
    }

    private fun openSite(why: String) {
        if (leaving) return
        leaving = true
        main.removeCallbacksAndMessages(null)
        val pageUrl = Session.current?.pageUrl ?: set?.pageUrl
        if (pageUrl == null) return finish()
        Report.log("Switching to the site's own player ($why)")
        startActivity(SitePlayerActivity.intent(this, pageUrl))
        finish()
    }

    // ---- Requests from the page ----

    private fun status(code: Int, cors: Map<String, String> = emptyMap()): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", code, CleanProxy.reason(code), cors, ByteArrayInputStream(ByteArray(0)))

    /** Runs on a background thread. Our assets come from the app, everything else is fetched natively. */
    private fun intercept(request: WebResourceRequest): WebResourceResponse {
        val url = request.url
        val scheme = url.scheme?.lowercase()
        val host = url.host
        if (host.equals(CleanProxy.ASSET_HOST, true)) {
            if (scheme == "https" && url.path in CleanProxy.ASSET_PATHS) {
                val r = assets?.shouldInterceptRequest(url)
                if (r != null) return r
                Report.logOnce("clean-asset-${url.path}", "Clean player: missing app file ${url.path}")
            }
            return status(404)
        }
        if ((scheme != "http" && scheme != "https") || request.isForMainFrame) {
            Report.logOnce("clean-block-$scheme-$host", "Clean player: blocked a request to ${url.toString().take(80)}")
            return status(403)
        }
        if (AdBlock.isLocalHost(host)) {
            Report.logOnce("clean-local-$host", "Blocked the page from reaching the TV itself ($host)")
            return status(403)
        }
        if (AdBlock.isBlockedHost(host)) {
            Report.countBlocked(host)
            return status(403)
        }
        val cors = CleanProxy.corsHeaders()
        val method = request.method?.uppercase() ?: "GET"
        if (method == "OPTIONS") {
            // CORS preflight (hls.js only sends one if it adds headers).
            cors["Access-Control-Allow-Methods"] = "GET, HEAD, OPTIONS"
            cors["Access-Control-Allow-Headers"] =
                request.requestHeaders?.entries?.firstOrNull { it.key.equals("Access-Control-Request-Headers", true) }?.value ?: "*"
            cors["Access-Control-Max-Age"] = "600"
            return status(204, cors)
        }
        if (method != "GET" && method != "HEAD") return status(405, cors)
        val f = fetcher ?: return status(503, cors)

        val range = request.requestHeaders?.entries?.firstOrNull { it.key.equals("Range", true) }?.value
        val result = f.fetch(url.toString(), range, method == "HEAD")
        val what = Report.short(url.toString())
        if (result.error != null) {
            val n = fetchFailures.incrementAndGet()
            if (n <= 8) Report.log("  clean: failed $what: ${result.error}")
            else if (n % 25 == 0) Report.log("  clean: $n requests failed so far (latest $what: ${result.error})")
        } else if (fetchLogged.incrementAndGet() <= 8) {
            val rate = if (result.millis > 0) result.bytes * 8.0 / result.millis / 1000.0 else 0.0
            Report.log(String.format(java.util.Locale.US, "  clean: got %s: %,d bytes in %.1fs (%.1f Mbps)", what, result.bytes, result.millis / 1000.0, rate))
        }
        return result.response
    }

    // ---- Bridge from the page (runs on a WebView thread) ----

    private inner class Bridge {
        @JavascriptInterface
        fun config(): String {
            val src = set?.sources?.getOrNull(sourceIndex)
            return JSONObject().put("gen", gen).put("url", src?.url ?: "").toString()
        }

        @JavascriptInterface
        fun log(msg: String?) {
            Report.log("  clean page: ${msg.orEmpty().take(300)}")
        }

        @JavascriptInterface
        fun started(g: Int) {
            if (g != gen) return
            main.post {
                if (g != gen || started) return@post
                started = true
                main.removeCallbacks(noStart)
                cover.visibility = View.GONE
                Report.log("Playback started (clean browser player)")
                AppStatus.text = "Playing ${Session.title} (clean browser player)"
                Toast.makeText(this@CleanPlayerActivity, "Clean browser player", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun failed(g: Int, reason: String?) {
            if (g != gen) return
            main.post { if (g == gen) fail(reason ?: "unknown") }
        }
    }

    // ---- Phone remote ----

    private fun js(code: String) {
        web?.evaluateJavascript("window.SLC && $code", null)
    }

    private fun onCommand(c: String, v: String?) {
        when (c) {
            "toggle", "play", "pause", "live" -> js("SLC.$c()")
            "quality" -> js("SLC.quality(${v?.toIntOrNull() ?: -1})")
            "source" -> v?.toIntOrNull()?.let { switchSource(it) }
            "reload" -> {
                Report.log("Reloading the clean browser player (requested from phone)")
                load()
            }
            "stop" -> finish()
            "site" -> openSite("requested from phone")
        }
        pollPage()
    }

    private fun switchSource(i: Int) {
        val s = set ?: return
        if (i !in s.sources.indices) return
        if (s.sources[i].openPage) { Report.log("Source ${i + 1} opens as its own page; not played here"); return }
        Report.log("Switching to source ${i + 1}")
        sourceIndex = i
        load()
    }

    private val snapshotTick = object : Runnable {
        override fun run() {
            pollPage()
            main.postDelayed(this, 1000)
        }
    }

    /** Ask the page how it's doing, then publish that for the phone. */
    private fun pollPage() {
        val w = web ?: return
        val g = gen
        w.evaluateJavascript("window.SLC ? SLC.state() : null") { r ->
            if (g != gen) return@evaluateJavascript
            page = try {
                if (r == null || r == "null") null else JSONObject(r).let { o ->
                    val lv = o.optJSONArray("levels") ?: JSONArray()
                    PageState(
                        o.optBoolean("playing"), o.optBoolean("buffering"), o.optBoolean("live"),
                        (0 until lv.length()).map { lv.optString(it) }, o.optInt("quality", -1),
                    )
                }
            } catch (_: Exception) {
                null
            }
            publishSnapshot()
        }
    }

    private fun publishSnapshot() {
        val p = page
        Session.snapshot = PlayerSnapshot(
            playing = p?.playing == true,
            buffering = p?.buffering ?: !started,
            live = p?.live == true,
            qualities = p?.levels ?: emptyList(),
            quality = p?.quality ?: -1,
            sources = set?.sources?.map { it.label } ?: emptyList(),
            source = sourceIndex,
            tunneling = false,
            smoothLive = true,
        )
    }

    // ---- Screen ----

    private fun showCover(text: String) {
        cover.text = text
        cover.visibility = View.VISIBLE
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val cmd = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "toggle"
            KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
            KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause"
            else -> null
        }
        if (cmd != null) {
            if (event.action == KeyEvent.ACTION_UP) onCommand(cmd, null)
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
