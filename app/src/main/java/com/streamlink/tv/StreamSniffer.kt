package com.streamlink.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONTokener
import java.util.Collections
import kotlin.concurrent.thread

/** One playable stream: its URL, the headers to send, and which pages to claim as referer (best first). */
data class Source(
    val url: String,
    val headers: Map<String, String>,
    val referers: List<String>,
    val label: String,
)

/** Everything found on a page. Empty [sources] means no stream was found. */
data class StreamSet(val pageUrl: String, val sources: List<Source>)

/** A stream the page requested, before it has been checked. */
data class StreamCandidate(val url: String, val headers: Map<String, String>, val referers: List<String>)

/**
 * Loads a page in a hidden WebView (with popups, off-site redirects and ad networks blocked),
 * nudges the page's player to start, collects every video stream it requests, checks them,
 * and reports the working ones ranked best first.
 */
class StreamSniffer(private val context: Context, private val holder: ViewGroup) {

    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var client: SafeWebViewClient? = null
    private var done = true
    private var verifying = false
    private var generation = 0
    private var pageUrl = ""
    private var nudgeCount = 0
    private val frameUrls = Collections.synchronizedList(mutableListOf<String>())
    private val candidates = LinkedHashMap<String, StreamCandidate>()
    private var onResult: ((StreamSet) -> Unit)? = null

    // Click-only list rows ("<page>#sl-click=..."): the page is loaded without the fragment, settled, and the row clicked.
    private var clickRef: ClickRef? = null
    private var clickPhase = 0 // 0 = not a click link or not started, 1 = waiting for the list page to settle, 2 = clicked
    private var clickLoadedAt = 0L
    private var clickAttempts = 0
    private var clickSig: String? = null
    private var clickUnchanged = 0
    private var clickX = 0f
    private var clickY = 0f
    private var timeoutMs = TIMEOUT_MS
    private var siteHost = ""

    private val timeout = Runnable {
        if (done || verifying) return@Runnable
        if (candidates.isEmpty()) {
            Report.log("No video stream found within ${timeoutMs / 1000}s")
            finish(StreamSet(currentPage(), emptyList()))
        } else {
            verifyAndFinish()
        }
    }
    private val collect = Runnable { verifyAndFinish() }
    private val settle = object : Runnable {
        override fun run() {
            if (done || verifying || clickPhase != 1) return
            pollSettle()
        }
    }
    private val tapRow = Runnable {
        val wv = webView ?: return@Runnable
        if (done || verifying || candidates.isNotEmpty() || pageChanged() || wv.width <= 0) return@Runnable
        Report.log("The click did not open anything; tapping the row")
        tap(wv, clickX, clickY)
    }
    private val nudger = object : Runnable {
        override fun run() {
            if (done || verifying) return
            nudge()
            main.postDelayed(this, 2500)
        }
    }

    /** Read-only progress for the waiting screen: 0 = idle/loading the page, 1 = page loaded and looking, 2 = stream seen, checking it. */
    val stage: Int
        get() = when {
            done -> 0
            verifying || candidates.isNotEmpty() -> 2
            client?.firstPageLoaded == true -> 1
            else -> 0
        }

    fun sniff(url: String, callback: (StreamSet) -> Unit) {
        cancel()
        val ref = ClickRef.parse(url)
        val target = ref?.pageUrl ?: ClickRef.pagePart(url) // the page itself, without any "#sl-click=" fragment
        if (isStreamUrl(target) || target.lowercase().substringBefore('?').endsWith(".mp4")) {
            Report.log("Link is a video file itself; playing it directly")
            callback(StreamSet(target, listOf(Source(target, emptyMap(), listOf(""), Report.host(target)))))
            return
        }
        done = false
        verifying = false
        generation++
        pageUrl = target
        clickRef = ref
        clickPhase = 0
        clickAttempts = 0
        clickSig = null
        clickUnchanged = 0
        siteHost = Report.host(target)
        val base = if (SiteMemory.shouldShorten(context, siteHost)) {
            Report.log("The stream wasn't found on this site the last ${SiteMemory.fails(context, siteHost)} times; looking for ${SiteMemory.SHORT_TIMEOUT_MS / 1000} s only")
            SiteMemory.SHORT_TIMEOUT_MS
        } else TIMEOUT_MS
        timeoutMs = if (ref != null) base + CLICK_EXTRA_MS else base
        nudgeCount = 0
        playerX = -1f
        playerY = -1f
        frameUrls.clear()
        candidates.clear()
        onResult = callback
        val wv = createWebView(target)
        webView = wv
        holder.addView(wv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        Report.log(if (ref != null) "Loading the list page (the event has no link of its own, it has to be clicked)" else "Loading page")
        wv.loadUrl(target)
        main.postDelayed(timeout, timeoutMs)
        main.postDelayed(nudger, FIRST_NUDGE_MS)
    }

    fun cancel() {
        done = true
        verifying = false
        generation++
        onResult = null
        main.removeCallbacks(timeout)
        main.removeCallbacks(nudger)
        main.removeCallbacks(collect)
        main.removeCallbacks(settle)
        main.removeCallbacks(tapRow)
        destroyWebView()
    }

    private fun currentPage(): String = client?.siteUrl ?: pageUrl

    private fun finish(result: StreamSet) {
        if (done) return
        SiteMemory.record(context, siteHost, found = result.sources.isNotEmpty())
        val cb = onResult
        cancel()
        cb?.invoke(result)
    }

    private fun destroyWebView() {
        val wv = webView ?: return
        webView = null
        client = null
        wv.stopLoading()
        wv.loadUrl("about:blank")
        holder.removeView(wv)
        wv.destroy()
    }

    /** Called on the main thread for each stream the page asks for. */
    private fun found(url: String, requestHeaders: Map<String, String>) {
        if (done || verifying || candidates.containsKey(url)) return
        if (AdBlock.isLocalHost(Report.host(url))) return
        val ua = webView?.settings?.userAgentString
        val headers = LinkedHashMap<String, String>()
        var requestReferer: String? = null
        for ((k, v) in requestHeaders) {
            when {
                k.equals("Referer", true) -> requestReferer = v
                // X-Requested-With names this app; some sites answer it with 403.
                k.equals("Range", true) || k.equals("Accept-Encoding", true) || k.equals("Origin", true) ||
                    k.equals("X-Requested-With", true) -> {}
                else -> headers[k] = v
            }
        }
        if (headers.keys.none { it.equals("User-Agent", true) } && ua != null) headers["User-Agent"] = ua
        CookieManager.getInstance().getCookie(url)?.let { if (it.isNotBlank()) headers["Cookie"] = it }
        // Candidates for "which page asked for this video": the embedded player frames (newest
        // first), then the page itself, then no referer at all.
        val frames = synchronized(frameUrls) { frameUrls.toList() }.asReversed()
        val referers = (listOfNotNull(requestReferer) + frames + listOfNotNull(webView?.url, currentPage()))
            .filter { it.startsWith("http") }
            .distinct()
            .take(5) + ""
        candidates[url] = StreamCandidate(url, headers, referers)
        Report.log("Found stream: ${Report.short(url)}")
        if (candidates.size == 1) main.postDelayed(collect, COLLECT_MS)
        if (candidates.size >= MAX_CANDIDATES) verifyAndFinish()
    }

    private fun verifyAndFinish() {
        if (done || verifying) return
        verifying = true
        main.removeCallbacks(timeout)
        main.removeCallbacks(nudger)
        main.removeCallbacks(collect)
        main.removeCallbacks(settle)
        main.removeCallbacks(tapRow)
        val list = candidates.values.toList()
        val page = currentPage()
        val gen = generation
        Report.log("Checking ${list.size} stream(s)…")
        thread(isDaemon = true, name = "stream-check") {
            val sources = try {
                Verifier.rank(list)
            } catch (e: Exception) {
                Report.log("Checking failed: ${e.javaClass.simpleName}")
                list.map { Source(it.url, it.headers, it.referers, Report.host(it.url) + " · unchecked") }
            }
            main.post {
                if (gen != generation || done) return@post // cancelled meanwhile
                verifying = false
                finish(StreamSet(page, sources))
            }
        }
    }

    private fun createWebView(url: String): WebView {
        val wv = WebView(context)
        wv.isFocusable = false
        wv.isFocusableInTouchMode = false
        SafeWeb.configure(wv)
        wv.webChromeClient = SafeChromeClient()
        // In every frame (the player's own frame from another site too): clear invisible ad layers over
        // the video, and press the player's start button, found by shape and position.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                // The hidden loader must stay silent: tells the overlay remover not to unmute on a click.
                WebViewCompat.addDocumentStartJavaScript(wv, "window.__slKeepMuted=1;", setOf("*"))
                WebViewCompat.addDocumentStartJavaScript(wv, SitePlayerActivity.OVERLAY_JS, setOf("*"))
                WebViewCompat.addDocumentStartJavaScript(wv, loadAsset(context, "player-start.js"), setOf("*"))
            } catch (e: Exception) {
                Report.log("Start helper not added: ${e.javaClass.simpleName}")
            }
        }
        val c = object : SafeWebViewClient(url) {
            override fun onFrame(url: String) {
                if (!AdBlock.isBlockedHost(Report.host(url))) frameUrls.add(url)
            }

            override fun onResource(request: WebResourceRequest) {
                val u = request.url.toString()
                if (isStreamUrl(u)) {
                    val headers = HashMap(request.requestHeaders ?: emptyMap())
                    main.post { found(u, headers) }
                }
            }

            override fun onLoaded() {
                if (clickRef != null && clickPhase == 0) {
                    // list page of a click-only event: let it settle, then click the row (nudging first could tap another row)
                    clickPhase = 1
                    clickLoadedAt = System.currentTimeMillis()
                    main.postDelayed(settle, 1000)
                    return
                }
                nudge()
            }
        }
        client = c
        wv.webViewClient = c
        return wv
    }

    /** True once the web view is no longer on the list page we loaded (the click navigated). */
    private fun pageChanged(): Boolean {
        val cur = webView?.url ?: return false
        return ClickRef.pagePart(cur).trimEnd('/') != pageUrl.trimEnd('/')
    }

    private fun pollSettle() {
        val wv = webView ?: return
        val gen = generation
        wv.evaluateJavascript(SIGNATURE_JS) { raw ->
            if (done || verifying || gen != generation || clickPhase != 1) return@evaluateJavascript
            val sig = (try { MiniJson.parse(raw ?: "null") as? String } catch (_: Exception) { null }) ?: ""
            if (sig == clickSig) clickUnchanged++ else { clickUnchanged = 0; clickSig = sig }
            val waited = System.currentTimeMillis() - clickLoadedAt
            if (clickUnchanged >= 2 || waited >= CLICK_SETTLE_MAX_MS) clickRow() else main.postDelayed(settle, 1000)
        }
    }

    /** Finds the row on the (settled) list page and clicks it; retries a few times if the list is not there yet. */
    private fun clickRow() {
        val wv = webView ?: return
        val ref = clickRef ?: return
        val gen = generation
        val script = try { loadClickScript(context) } catch (e: Exception) { Report.log("Click script missing"); finish(StreamSet(currentPage(), emptyList())); return }
        clickAttempts++
        wv.evaluateJavascript("($script)(${org.json.JSONObject.quote(ClickRef.specJson(ref))})") { raw ->
            if (done || verifying || gen != generation) return@evaluateJavascript
            val o = try { MiniJson.parse(MiniJson.parse(raw ?: "null") as? String ?: "") as? Map<*, *> } catch (_: Exception) { null }
            if (o == null || o["ok"] != true) {
                val why = (o?.get("why") as? String) ?: "no answer from the page"
                val waited = System.currentTimeMillis() - clickLoadedAt
                if (clickAttempts < CLICK_MAX_ATTEMPTS && waited < CLICK_SETTLE_MAX_MS + 10_000) {
                    main.postDelayed({ if (!done && !verifying && gen == generation && clickPhase == 1) clickRow() }, 2000)
                } else {
                    Report.log("Could not find the event's row on the list page ($why)")
                    finish(StreamSet(currentPage(), emptyList()))
                }
                return@evaluateJavascript
            }
            clickPhase = 2
            val iw = (o["iw"] as? Double) ?: 0.0
            val scale = if (iw > 0 && wv.width > 0) wv.width / iw.toFloat() else wv.resources.displayMetrics.density
            clickX = ((o["x"] as? Double) ?: 0.0).toFloat() * scale
            clickY = ((o["y"] as? Double) ?: 0.0).toFloat() * scale
            Report.log("Opened the event by clicking its row on the list page (matched by ${o["how"]})")
            main.postDelayed(tapRow, 3000)
            nudge()
        }
    }

    /** Try to get the page's player going: muted autoplay, click common play buttons, tap the middle. */
    private fun nudge() {
        val wv = webView ?: return
        if (done || verifying || clickPhase == 1) return
        nudgeCount++
        wv.evaluateJavascript(NUDGE_JS) { raw ->
            if (done || verifying || raw == null || raw == "null") return@evaluateJavascript
            try {
                val o = org.json.JSONObject(JSONTokener(raw).nextValue() as String)
                val iw = o.optDouble("iw", 0.0)
                if (iw > 0 && o.has("px") && wv.width > 0) {
                    val scale = wv.width / iw.toFloat()
                    playerX = (o.optDouble("px") * scale).toFloat()
                    playerY = (o.optDouble("py") * scale).toFloat()
                }
                val arr = o.getJSONArray("v")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val src = o.optString("src")
                    val live = o.isNull("dur") && o.optInt("ready") >= 1
                    val long = !o.isNull("dur") && o.optDouble("dur") > 600
                    if (src.startsWith("http") && (isStreamUrl(src) || live || long)) {
                        found(src, emptyMap())
                    }
                }
            } catch (_: Exception) {
            }
        }
        // Players inside iframes can't be scripted from here, so tap the middle of the page on
        // every other nudge. Any popup or redirect that tap triggers is blocked.
        // (On a click-only list page that is still showing the list, a tap in the middle would open some other event.)
        // Aim at the middle of the player when the page shows one (a video or a big frame), else the page.
        val onList = clickRef != null && !pageChanged()
        if (nudgeCount % 2 == 0 && wv.width > 0 && !onList) {
            val x = if (playerX > 0) playerX else wv.width / 2f
            val y = if (playerY > 0) playerY else wv.height / 2f
            if (nudgeCount == 2) Report.log(if (playerX > 0) "Tapping the middle of the player" else "No player box seen; tapping the middle of the page")
            tap(wv, x, y)
        }
    }

    private var playerX = -1f
    private var playerY = -1f

    private fun tap(view: WebView, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0)
        view.dispatchTouchEvent(down)
        view.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    companion object {
        const val TIMEOUT_MS = 25_000L
        /** Extra time for click-only events: the list page has to settle (<= 12s) before the click. */
        const val CLICK_EXTRA_MS = 15_000L
        private const val CLICK_SETTLE_MAX_MS = 12_000L
        private const val CLICK_MAX_ATTEMPTS = 4
        private const val SIGNATURE_JS =
            "(function(){var b=document.body;if(!b)return '0:0';return b.innerText.length+':'+b.getElementsByTagName('*').length;})()"

        @Volatile private var clickScript: String? = null
        private val assetCache = java.util.concurrent.ConcurrentHashMap<String, String>()

        private fun loadAsset(context: Context, name: String): String =
            assetCache.getOrPut(name) { context.applicationContext.assets.open(name).bufferedReader().use { it.readText() } }

        private fun loadClickScript(context: Context): String {
            clickScript?.let { return it }
            val text = context.applicationContext.assets.open("click-row.js").bufferedReader().use { it.readText() }
            clickScript = text
            return text
        }
        /** After the first stream is seen, how long to wait for more before checking them. */
        private const val COLLECT_MS = 1_200L
        private const val FIRST_NUDGE_MS = 1_500L
        private const val MAX_CANDIDATES = 8

        fun isStreamUrl(url: String): Boolean {
            val u = url.lowercase()
            if (!u.startsWith("http://") && !u.startsWith("https://")) return false
            val path = u.substringBefore('?').substringBefore('#')
            if (path.endsWith(".js") || path.endsWith(".css")) return false
            return u.contains("m3u8") || path.endsWith(".mpd") || u.contains(".mpd?")
        }

        private const val NUDGE_JS = """
            (function(){
              var out=[];
              document.querySelectorAll('video').forEach(function(v){
                try{ v.muted=true; var p=v.play(); if(p&&p.catch)p.catch(function(){}); }catch(e){}
                var s=v.currentSrc||v.src||'';
                out.push({src:s, dur:(isFinite(v.duration)?v.duration:null), ready:v.readyState});
              });
              ['.vjs-big-play-button','.jw-icon-display','.jw-display-icon-container',
               '.plyr__control--overlaid','.fp-play','.play-button','.playbtn','#play',
               'button[aria-label="Play"]','button[title="Play"]','[class*="play-btn"]']
              .forEach(function(sel){
                document.querySelectorAll(sel).forEach(function(e){ try{e.click();}catch(x){} });
              });
              var best=null, ba=0;
              document.querySelectorAll('video,iframe').forEach(function(e){
                var r=e.getBoundingClientRect(), cs=getComputedStyle(e);
                if(cs.visibility==='hidden'||cs.display==='none')return;
                var L=Math.max(0,r.left),T=Math.max(0,r.top),R=Math.min(innerWidth,r.right),B=Math.min(innerHeight,r.bottom);
                var a=(R-L)*(B-T);
                if(R>L&&B>T&&a>=40000&&a>ba){ba=a;best={x:(L+R)/2,y:(T+B)/2};}
              });
              var res={v:out, iw:innerWidth};
              if(best){res.px=best.x;res.py=best.y;}
              return JSON.stringify(res);
            })()
        """
    }
}
