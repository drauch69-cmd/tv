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
    /** Came from the link the page showed by default (so the same stream the player already has). */
    val isDefaultLink: Boolean = false,
)

/** Everything found on a page. Empty [sources] means no stream was found. */
data class StreamSet(
    val pageUrl: String,
    val sources: List<Source>,
    /** Links mode only: (link label, player frame URL) of each alternate link whose click swapped in a new player. */
    val linkPlayers: List<Pair<String, String>> = emptyList(),
    /** Links mode only: the player frame the page showed by default (so it can be skipped), or "". */
    val defaultPlayer: String = "",
    /** Why nothing was found, when the sniffer knows (display/log only). */
    val reason: String = "",
)

/** A stream the page requested, before it has been checked. */
data class StreamCandidate(
    val url: String,
    val headers: Map<String, String>,
    val referers: List<String>,
    /** Text of the page's alternate-link button that produced this stream (display only), or "". */
    val linkLabel: String = "",
    /** Found on the link the page showed by default (before any other link was clicked). */
    val isDefault: Boolean = true,
)

/**
 * Loads a page in a hidden WebView (with popups, off-site redirects and ad networks blocked),
 * nudges the page's player to start, collects every video stream it requests, checks them,
 * and reports the working ones ranked best first.
 */
class StreamSniffer(private val context: Context, private val holder: ViewGroup) {

    private val main = Handler(Looper.getMainLooper())

    /** Background link finder (see [sniff]): a light hidden page, and only summary lines in the report, prefixed "Links: ". */
    @Volatile private var linksMode = false
    /** Per-player run for an alternate link (see PlayerActivity): same page loader, but light (blocks all but playlists once one is seen). */
    @Volatile private var light = false
    @Volatile private var playlistSeen = false
    private var lastReason = ""
    private fun log(m: String) { if (!linksMode) Report.log(if (light) "Links: · $m" else m) }
    private fun linkLog(m: String) = Report.log(if (linksMode) "Links: $m" else m)
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

    // Alternate stream links ("Link 1 / Link 2 ..." buttons next to the player), see assets/source-switch.js.
    private var linkState = 0 // 0 = not looked yet, 1 = looking for the row, 2 = trying the links, 3 = done
    private var switcher: Switcher? = null
    private val linkTried = HashSet<Int>()
    private var linkCurrent = -1
    private var linkClickedAt = 0L
    private var linkFrameAt = 0L
    private var linkDeadline = 0L
    private var linkLoadedSinceClick = false
    private var linkTapped = false
    private var linkGot = 0
    // The goal of a click is the new player frame (see nextLink). onFrame may run off the main thread.
    @Volatile private var linkWatch = false
    @Volatile private var linkNewFrame: String? = null
    @Volatile private var linkPrevPlayer = ""
    private var linkTapDone = false
    private val linkPlayers = ArrayList<Pair<String, String>>()
    private var defaultPlayer = ""
    private var referer: String? = null
    private var curLabel = ""
    private var curDefault = true
    private var runStartedAt = 0L
    /** Page HTML for the stream-page snapshot (see [beginLinks]); null until the snapshot script answers. */
    private var streamHtml: String? = null

    private val timeout = Runnable {
        if (done || verifying) return@Runnable
        if (candidates.isEmpty()) {
            lastReason = "no video stream found within ${timeoutMs / 1000}s"
            if (linksMode) linkLog("the page showed no stream within ${timeoutMs / 1000}s")
            else log("No video stream found within ${timeoutMs / 1000}s")
            val wv = webView
            if (linksMode && wv != null) {
                // Snapshot what the page showed for "Save sample", then finish (2 s at most).
                val gen = generation
                var finished = false
                val end = Runnable { if (!finished && gen == generation) { finished = true; finish(result(emptyList())) } }
                main.postDelayed(end, 2_000)
                try {
                    wv.evaluateJavascript(IndexReader.SNAP_JS) { raw ->
                        streamHtml = try { if (raw == null || raw == "null") "" else org.json.JSONTokener(raw).nextValue() as? String ?: "" } catch (_: Exception) { "" }
                        if (gen == generation) streamSnapshot(wv, "no stream")
                        main.removeCallbacks(end)
                        end.run()
                    }
                } catch (_: Exception) {
                    main.removeCallbacks(end)
                    end.run()
                }
                return@Runnable
            }
            finish(result(emptyList()))
        } else {
            verifyAndFinish()
        }
    }
    /** The first stream was seen and had a moment to be joined by its siblings: look for alternate links, then check. */
    private val collect = Runnable { if (linksMode) beginLinks() else verifyAndFinish() }
    private val linkTick = object : Runnable {
        override fun run() { linkStep() }
    }
    private val settle = object : Runnable {
        override fun run() {
            if (done || verifying || clickPhase != 1) return
            pollSettle()
        }
    }
    private val tapRow = Runnable {
        val wv = webView ?: return@Runnable
        if (done || verifying || candidates.isNotEmpty() || pageChanged() || wv.width <= 0) return@Runnable
        log("The click did not open anything; tapping the row")
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

    /**
     * [collectLinks]: also click the page's alternate-link buttons and return their streams. Only for the
     * background run beside a playing stream: the page is kept light (no playback, no media segments).
     * [referer]: sent with the first request (a player URL opened on its own; the stream page it came from).
     * [light]: a per-player run for an alternate link: the page's site memory is left alone, the time is
     * [LIGHT_TIMEOUT_MS], and once a playlist is seen everything else is blocked. Players may still start.
     */
    fun sniff(url: String, collectLinks: Boolean = false, referer: String? = null, light: Boolean = false, callback: (StreamSet) -> Unit) {
        cancel()
        linksMode = collectLinks
        this.light = light
        this.referer = referer
        lastReason = ""
        runStartedAt = SystemClock.uptimeMillis()
        playlistSeen = false
        val ref = ClickRef.parse(url)
        val target = ref?.pageUrl ?: ClickRef.pagePart(url) // the page itself, without any "#sl-click=" fragment
        if (isStreamUrl(target) || target.lowercase().substringBefore('?').endsWith(".mp4")) {
            log("Link is a video file itself; playing it directly")
            if (linksMode) linkLog("done in ${runSecs()}s")
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
        resetLinks()
        val base = if (light) LIGHT_TIMEOUT_MS else if (SiteMemory.shouldShorten(context, siteHost)) {
            log("The stream wasn't found on this site the last ${SiteMemory.fails(context, siteHost)} times; looking for ${SiteMemory.SHORT_TIMEOUT_MS / 1000} s only")
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
        log(if (ref != null) "Loading the list page (the event has no link of its own, it has to be clicked)" else "Loading page")
        if (!referer.isNullOrBlank()) wv.loadUrl(target, mapOf("Referer" to referer)) else wv.loadUrl(target)
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
        main.removeCallbacks(linkTick)
        main.removeCallbacks(settle)
        main.removeCallbacks(tapRow)
        destroyWebView()
    }

    private fun currentPage(): String = client?.siteUrl ?: pageUrl

    private fun runSecs(): String = String.format(java.util.Locale.US, "%.1f", (SystemClock.uptimeMillis() - runStartedAt) / 1000.0)

    private fun result(sources: List<Source>) =
        StreamSet(currentPage(), sources, linkPlayers.toList(), defaultPlayer, if (sources.isEmpty()) lastReason else "")

    private fun finish(result: StreamSet) {
        if (done) return
        if (!linksMode && !light) SiteMemory.record(context, siteHost, found = result.sources.isNotEmpty())
        if (linksMode) linkLog("done in ${runSecs()}s")
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
        val referers = (listOfNotNull(requestReferer) + frames + listOfNotNull(webView?.url, currentPage(), referer))
            .filter { it.startsWith("http") }
            .distinct()
            .take(5) + ""
        candidates[url] = StreamCandidate(url, headers, referers, curLabel, curDefault)
        log("Found stream: ${Report.short(url)}")
        if (candidates.size == 1 && linkState == 0) main.postDelayed(collect, COLLECT_MS)
        if (candidates.size >= MAX_CANDIDATES) verifyAndFinish()
    }

    private fun verifyAndFinish() {
        if (done || verifying) return
        verifying = true
        main.removeCallbacks(timeout)
        main.removeCallbacks(nudger)
        main.removeCallbacks(collect)
        main.removeCallbacks(linkTick)
        main.removeCallbacks(settle)
        main.removeCallbacks(tapRow)
        val list = candidates.values.toList()
        val page = currentPage()
        val gen = generation
        log("Checking ${list.size} stream(s)…")
        thread(isDaemon = true, name = "stream-check") {
            val sources = try {
                Verifier.rank(list) { m -> log(m) }
            } catch (e: Exception) {
                log("Checking failed: ${e.javaClass.simpleName}")
                list.map { Source(it.url, it.headers, it.referers, LinkOrder.sourceLabel(it.linkLabel, Report.host(it.url), "unchecked", true), it.isDefault) }
            }
            main.post {
                if (gen != generation || done) return@post // cancelled meanwhile
                verifying = false
                finish(result(sources).copy(pageUrl = page))
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
                WebViewCompat.addDocumentStartJavaScript(wv, SILENT_JS, setOf("*"))
                WebViewCompat.addDocumentStartJavaScript(wv, SitePlayerActivity.OVERLAY_JS, setOf("*"))
                WebViewCompat.addDocumentStartJavaScript(wv, loadAsset(context, "player-start.js"), setOf("*"))
            } catch (e: Exception) {
                log("Start helper not added: ${e.javaClass.simpleName}")
            }
        }
        val c = object : SafeWebViewClient(url) {
            override fun onFrame(url: String) {
                if (!AdBlock.isBlockedHost(Report.host(url))) frameUrls.add(url)
                if (linkWatch && linkNewFrame == null && LinkOrder.isNewPlayerFrame(url, linkPrevPlayer)) linkNewFrame = url
            }

            override fun onResource(request: WebResourceRequest) {
                val u = request.url.toString()
                if (isStreamUrl(u)) {
                    playlistSeen = true
                    val headers = HashMap(request.requestHeaders ?: emptyMap())
                    main.post { found(u, headers) }
                }
            }

            override fun blockResource(request: WebResourceRequest): Boolean =
                // Once a playlist is known, nothing else is needed (until the next link click): block
                // all but playlists (also video pieces disguised as pictures, which have no video extension).
                (linksMode || light) && playlistSeen && !isStreamUrl(request.url.toString())

            override fun onLoaded() {
                if (linkState == 2) linkLoadedSinceClick = true
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
        val script = try { loadClickScript(context) } catch (e: Exception) { log("Click script missing"); lastReason = "click script missing"; finish(result(emptyList())); return }
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
                    log("Could not find the event's row on the list page ($why)")
                    lastReason = "event row not found on the list page ($why)"
                    finish(result(emptyList()))
                }
                return@evaluateJavascript
            }
            clickPhase = 2
            val iw = (o["iw"] as? Double) ?: 0.0
            val scale = if (iw > 0 && wv.width > 0) wv.width / iw.toFloat() else wv.resources.displayMetrics.density
            clickX = ((o["x"] as? Double) ?: 0.0).toFloat() * scale
            clickY = ((o["y"] as? Double) ?: 0.0).toFloat() * scale
            log("Opened the event by clicking its row on the list page (matched by ${o["how"]})")
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
            if (nudgeCount == 2) log(if (playerX > 0) "Tapping the middle of the player" else "No player box seen; tapping the middle of the page")
            tap(wv, x, y)
        }
    }

    private var playerX = -1f
    private var playerY = -1f

    /** Background run without a switcher: nothing to add, skip checking the stream the player already has. */
    private fun finishEmpty() = finish(result(emptyList()))

    private fun resetLinks() {
        main.removeCallbacks(linkTick)
        linkState = 0
        linkWatch = false
        linkNewFrame = null
        linkPrevPlayer = ""
        linkPlayers.clear()
        defaultPlayer = ""
        switcher = null
        linkTried.clear()
        linkCurrent = -1
        linkGot = 0
        curLabel = ""
        curDefault = true
    }

    /** First stream seen: look for a row of alternate links next to the player; none (or not usable) means check what we have. */
    private fun beginLinks() {
        if (done || verifying) return
        val wv = webView
        if (linkState != 0 || wv == null) { finishEmpty(); return }
        val script = try { loadAsset(context, "source-switch.js") } catch (e: Exception) { finishEmpty(); return }
        linkState = 1
        val gen = generation
        // The page snapshot for "Save sample" is queued before "find", so its answer is in by the time "find" answers.
        streamHtml = null
        try {
            wv.evaluateJavascript(IndexReader.SNAP_JS) { raw ->
                if (gen != generation) return@evaluateJavascript
                streamHtml = try { if (raw == null || raw == "null") "" else org.json.JSONTokener(raw).nextValue() as? String ?: "" } catch (_: Exception) { "" }
            }
        } catch (_: Exception) {
        }
        wv.evaluateJavascript("($script)('find')") { raw ->
            if (done || verifying || gen != generation) return@evaluateJavascript
            val sw = LinkOrder.parseFind(raw)
            streamSnapshot(wv, if (sw != null) "${sw.count} links" else "no link buttons")
            if (sw == null) {
                linkState = 3
                linkLog(LinkOrder.noLinksLine(raw))
                finishEmpty()
                return@evaluateJavascript
            }
            startLinks(sw)
        }
    }

    /** Keeps the stream page for "Save sample": picture now, HTML from [streamHtml]. Never blocks the link phase. */
    private fun streamSnapshot(wv: WebView, note: String) {
        try {
            val image = try { picture(wv) } catch (_: Throwable) { null }
            SampleRecorder.streamPage(currentPage(), streamHtml ?: "", image, note)
        } catch (_: Throwable) {
        }
    }

    /** Draws the WebView (1280x720) into a small JPEG; same as IndexReader.picture. */
    private fun picture(wv: WebView): ByteArray? {
        val w = wv.width
        val h = wv.height
        if (w <= 0 || h <= 0) return null
        val scale = 640f / w
        val bmp = android.graphics.Bitmap.createBitmap(640, (h * scale).toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.RGB_565)
        val canvas = android.graphics.Canvas(bmp)
        canvas.scale(scale, scale)
        wv.draw(canvas)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun startLinks(sw: Switcher) {
        switcher = sw
        linkState = 2
        val active = if (sw.active >= 0) (sw.active + 1).toString() else "unknown"
        linkLog("Found ${sw.count} stream links on the page (active: $active): " + sw.labels.joinToString(" | ") { it.take(30) })
        if (sw.active >= 0) {
            linkTried.add(sw.active)
            val activeLabel = sw.labels[sw.active]
            for ((u, c) in candidates.entries.toList()) if (c.isDefault && c.linkLabel.isEmpty()) candidates[u] = c.copy(linkLabel = activeLabel)
        }
        defaultPlayer = synchronized(frameUrls) { frameUrls.lastOrNull() } ?: ""
        linkPrevPlayer = defaultPlayer
        linkDeadline = SystemClock.uptimeMillis() + LINK_TOTAL_MS
        // The link phase brings its own time; this only guards against it hanging.
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, LINK_TOTAL_MS + 3_000)  // backstop: 3 s over the link-phase cap
        nextLink()
    }

    private fun nextLink() {
        if (done || verifying || linkState != 2) return
        val sw = switcher
        val wv = webView
        val idx = if (sw == null) null else LinkOrder.nextToTry(sw.count, linkTried)
        if (sw == null || wv == null || idx == null || SystemClock.uptimeMillis() > linkDeadline - LINK_MIN_LEFT_MS) {
            endLinks()
            return
        }
        val script = try { loadAsset(context, "source-switch.js") } catch (e: Exception) { endLinks(); return }
        linkTried.add(idx)
        playlistSeen = false
        linkCurrent = idx
        curLabel = sw.labels[idx]
        curDefault = false
        linkFrameAt = 0L
        linkLoadedSinceClick = false
        linkTapped = false
        linkTapDone = false
        linkNewFrame = null
        linkWatch = true
        linkClickedAt = SystemClock.uptimeMillis()
        val gen = generation
        wv.evaluateJavascript("($script)('click $idx')") { raw ->
            if (done || verifying || gen != generation || linkState != 2) return@evaluateJavascript
            val ok = try {
                val first = MiniJson.parse(raw ?: "null")
                ((if (first is String) MiniJson.parse(first) else first) as? Map<*, *>)?.get("ok") == true
            } catch (_: Exception) { false }
            if (!ok) {
                linkLog("Could not click link ${idx + 1}")
                main.post { nextLink() }
            } else {
                main.postDelayed(linkTick, LINK_TICK_MS)
            }
        }
    }

    /** Every 300 ms while a clicked link is given its chance to swap in a new player frame. */
    private fun linkStep() {
        if (done || verifying || linkState != 2) return
        val now = SystemClock.uptimeMillis()
        val gotPlayer = linkNewFrame != null
        if (gotPlayer && linkFrameAt == 0L) linkFrameAt = now
        // A short grace after the frame, so a playlist the page asks for right away is kept too.
        val finished = (gotPlayer && now - linkFrameAt >= LINK_GRACE_MS) || now - linkClickedAt >= LINK_WAIT_MS || now >= linkDeadline
        if (!finished) {
            if (!gotPlayer && !linkTapped && !linkLoadedSinceClick && now - linkClickedAt >= LINK_TAP_AFTER_MS) {
                linkTapped = true
                tapLink(linkCurrent)
            }
            main.postDelayed(linkTick, LINK_TICK_MS)
            return
        }
        linkWatch = false
        val frame = linkNewFrame
        val label = curLabel.ifEmpty { "no label" }
        if (frame != null) {
            linkGot++
            linkPlayers.add(curLabel.ifEmpty { "Link ${linkCurrent + 1}" } to frame)
            linkPrevPlayer = frame
            linkLog("Link ${linkCurrent + 1} ($label): player ${Report.host(frame)}")
        } else {
            linkLog("Link ${linkCurrent + 1} ($label): no new player")
        }
        if (linkLoadedSinceClick) refindAfterLoad() else nextLink()
    }

    /** The click opened another page of the site: find the row again on it (same number of links) and go on. */
    private fun refindAfterLoad() {
        val wv = webView
        val script = try { loadAsset(context, "source-switch.js") } catch (e: Exception) { null }
        if (wv == null || script == null) { endLinks(); return }
        val gen = generation
        wv.evaluateJavascript("($script)('find')") { raw ->
            if (done || verifying || gen != generation || linkState != 2) return@evaluateJavascript
            val sw = LinkOrder.parseFind(raw)
            if (sw == null || sw.count != switcher?.count) {
                log("The links changed after opening another page; stopping there")
                endLinks()
            } else {
                switcher = sw
                nextLink()
            }
        }
    }

    private fun tapLink(i: Int) {
        val wv = webView ?: return
        val sw = switcher ?: return
        if (i !in 0 until sw.count || sw.iw <= 0f || wv.width <= 0) return
        val scale = wv.width / sw.iw
        val x = sw.xs[i] * scale
        val y = sw.ys[i] * scale
        if (x <= 0f || y <= 0f || y >= wv.height) return
        linkLog("The click showed no new player; tapping link ${i + 1}")
        linkTapDone = true
        tap(wv, x, y)
    }

    private fun endLinks() {
        linkState = 3
        main.removeCallbacks(linkTick)
        linkWatch = false
        val n = (switcher?.count ?: 1) - 1
        linkLog("Tried the other links: $linkGot of $n gave a new player")
        verifyAndFinish()
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
        private const val MAX_CANDIDATES = 12
        private val SEGMENT_EXTENSIONS = listOf(".ts", ".m4s", ".aac", ".mp4", ".m4a", ".m4v", ".mp3", ".webm", ".cmfv", ".cmfa", ".fmp4")

        /** A media segment (not a playlist): the background link finder never downloads these. */
        fun isSegmentUrl(url: String): Boolean {
            if (isStreamUrl(url)) return false
            val path = url.lowercase().substringBefore('?').substringBefore('#')
            return SEGMENT_EXTENSIONS.any { path.endsWith(it) }
        }

        /** Alternate links: the most time they may add in total, how long to wait for each, and the pause after a new stream appears. */
        const val LINK_TOTAL_MS = 35_000L
        /** How long a clicked link is given to swap in a new player frame. */
        private const val LINK_WAIT_MS = 6_000L
        /** Time for one per-player run ([sniff] with light = true). */
        const val LIGHT_TIMEOUT_MS = 15_000L
        private const val LINK_GRACE_MS = 900L
        private const val LINK_TAP_AFTER_MS = 3_500L
        private const val LINK_TICK_MS = 300L
        private const val LINK_MIN_LEFT_MS = 2_500L

        fun isStreamUrl(url: String): Boolean {
            val u = url.lowercase()
            if (!u.startsWith("http://") && !u.startsWith("https://")) return false
            val path = u.substringBefore('?').substringBefore('#')
            if (path.endsWith(".js") || path.endsWith(".css")) return false
            return u.contains("m3u8") || path.endsWith(".mpd") || u.contains(".mpd?")
        }

        /**
         * The hidden loader must never be heard, in any frame (a player frame from another site that
         * autoplays with sound too): every video/audio element is forced muted and can't be unmuted.
         * Also tells the overlay remover not to unmute on a click.
         */
        private const val SILENT_JS = """
            (function(){
              window.__slKeepMuted = 1;
              try {
                var P = HTMLMediaElement.prototype;
                var d = Object.getOwnPropertyDescriptor(P, 'muted');
                if (d && d.set) Object.defineProperty(P, 'muted', { get: d.get, set: function(){ d.set.call(this, true); }, configurable: true });
                var op = P.play;
                P.play = function(){ try { d.set.call(this, true); } catch (e) {} return op.apply(this, arguments); };
              } catch (e) {}
              var hush = function(e){ try { var t = e.target; if (t && t.tagName && /^(VIDEO|AUDIO)$/.test(t.tagName)) t.muted = true; } catch (x) {} };
              document.addEventListener('play', hush, true);
              document.addEventListener('playing', hush, true);
              document.addEventListener('volumechange', hush, true);
            })();
        """

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
                for(var pn=e;pn&&pn!==document.body;pn=pn.parentElement){var pp=getComputedStyle(pn).position;if(pp==='fixed'||pp==='sticky')return;}
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
