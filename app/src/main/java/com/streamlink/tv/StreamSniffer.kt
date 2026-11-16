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

    private val timeout = Runnable {
        if (done || verifying) return@Runnable
        if (candidates.isEmpty()) {
            Report.log("No video stream found within ${TIMEOUT_MS / 1000}s")
            finish(StreamSet(currentPage(), emptyList()))
        } else {
            verifyAndFinish()
        }
    }
    private val collect = Runnable { verifyAndFinish() }
    private val nudger = object : Runnable {
        override fun run() {
            if (done || verifying) return
            nudge()
            main.postDelayed(this, 2500)
        }
    }

    fun sniff(url: String, callback: (StreamSet) -> Unit) {
        cancel()
        if (isStreamUrl(url) || url.lowercase().substringBefore('?').endsWith(".mp4")) {
            Report.log("Link is a video file itself; playing it directly")
            callback(StreamSet(url, listOf(Source(url, emptyMap(), listOf(""), Report.host(url)))))
            return
        }
        done = false
        verifying = false
        generation++
        pageUrl = url
        nudgeCount = 0
        frameUrls.clear()
        candidates.clear()
        onResult = callback
        val wv = createWebView(url)
        webView = wv
        holder.addView(wv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        Report.log("Loading page")
        wv.loadUrl(url)
        main.postDelayed(timeout, TIMEOUT_MS)
        main.postDelayed(nudger, 3000)
    }

    fun cancel() {
        done = true
        verifying = false
        generation++
        onResult = null
        main.removeCallbacks(timeout)
        main.removeCallbacks(nudger)
        main.removeCallbacks(collect)
        destroyWebView()
    }

    private fun currentPage(): String = client?.siteUrl ?: pageUrl

    private fun finish(result: StreamSet) {
        if (done) return
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
                nudge()
            }
        }
        client = c
        wv.webViewClient = c
        return wv
    }

    /** Try to get the page's player going: muted autoplay, click common play buttons, tap the middle. */
    private fun nudge() {
        val wv = webView ?: return
        if (done || verifying) return
        nudgeCount++
        wv.evaluateJavascript(NUDGE_JS) { raw ->
            if (done || verifying || raw == null || raw == "null") return@evaluateJavascript
            try {
                val arr = JSONArray(JSONTokener(raw).nextValue() as String)
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
        if (nudgeCount % 2 == 0 && wv.width > 0) tap(wv, wv.width / 2f, wv.height / 2f)
    }

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
        private const val COLLECT_MS = 2_500L
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
              return JSON.stringify(out);
            })()
        """
    }
}
