package com.streamlink.tv

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZoneId
import kotlin.concurrent.thread

/**
 * Loads an index page in a hidden, locked-down WebView, waits for it to settle, and reads its
 * events: with a site recipe (recipes.json on GitHub) if one matches, otherwise automatically
 * (assets/list-extract.js). The holder must be laid out (not GONE), like StreamSniffer's.
 */
class IndexReader(private val context: Context, private val holder: ViewGroup) {

    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var client: SafeWebViewClient? = null
    private var done = true
    private var generation = 0
    private var onResult: ((ListResult) -> Unit)? = null
    private var requestUrl = ""
    private var startedAt = 0L
    private var firstLoadedAt = 0L
    private var lastSignature: String? = null
    private var unchanged = 0
    private var polling = false
    private var extracting = false
    private var retried = false
    private var slowRetried = false
    private var expandTried = false // the "show more" presses run once per page load, never on the retry paths
    private var followedFrom: String? = null // the list page's own URL once we moved on to an embedded frame
    private var deadlineAt = 0L
    private var recipes: List<ListRecipes.Recipe>? = null // null while still being fetched
    private var snapshotting = false
    /** When the settle polling started (first paint, page loaded, or [EARLY_POLL_MS] after the load began). */
    private var pollFrom = 0L
    /** Bumped on every new page load (read or follow), so a stale "start polling" timer does nothing. */
    private var loadSeq = 0
    /** Events key of the last look (C2), how many looks ran, and whether the current expand step ends in a look. */
    private var lookKey: String? = null
    private var looks = 0
    private var lookMode = false
    /** Pictures, fonts and media answered empty for this read (counted on the web view's network thread). */
    private val blocked = java.util.concurrent.atomic.AtomicInteger(0)
    /** Quick reads (QuickRead.kt): how this read goes, which half is running, and what the quick half found. */
    private var mode = QuickLogic.Mode.FULL
    private var quickPhase = false
    private var quickItems: List<ListItem>? = null
    private var quickFellBack = false
    private var quickExtractStarted = false

    private val overallTimeout = Runnable {
        if (done) return@Runnable
        if (quickPhase) { quickFailed("took too long"); return@Runnable }
        if (firstLoadedAt != 0L && !extracting) {
            Report.log("List: page still changing after ${(System.currentTimeMillis() - startedAt) / 1000}s; reading what is there")
            extract(allowRetry = false)
            main.postDelayed(hardStop, 4000)
        } else if (!extracting) {
            finish(fail("The page did not load in time"))
        } else {
            main.postDelayed(hardStop, 4000)
        }
    }
    private val hardStop = Runnable { if (!done) finish(fail("Reading the page took too long")) }
    private val poll = object : Runnable {
        override fun run() {
            if (done || extracting) return
            pollOnce()
        }
    }

    fun read(url: String, callback: (ListResult) -> Unit) {
        cancel()
        val scheme = Uri.parse(url).scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            val r = ListResult(url, "", emptyList(), "automatic", "Not a web address: $url")
            main.post { callback(r) }
            return
        }
        quickItems = null
        quickFellBack = false
        if (!feedTried(url, callback)) startRead(url, callback)
    }

    /**
     * A site with a "feed" recipe: download its data file and map it to events (FeedRecipe), no browser at all.
     * Returns false (nothing started) when there's no recipe in the saved copy; the page read runs then. A failing or
     * empty data file falls back to the page read. One read at a time: the same generation/done rules as a page read.
     */
    private fun feedTried(url: String, callback: (ListResult) -> Unit): Boolean {
        val recipe = try { ListRecipes.match(ListRecipes.saved(context), Uri.parse(url).host) } catch (_: Exception) { null }
        val feed = recipe?.feed ?: return false
        val feedUrl = FeedRecipe.feedUrl(feed, url)
        if (feedUrl == null) { Report.log("List: data file address for ${Report.host(url)} needs a part this list's address doesn't have; reading the page"); return false }
        done = false
        generation++
        val gen = generation
        onResult = callback
        requestUrl = url
        startedAt = System.currentTimeMillis()
        Report.log("List: loading ${Report.host(url)} from the site's data file")
        main.postDelayed(feedTimeout, FEED_MS)
        thread {
            val items = try {
                val text = QuickFetch.download(context, feedUrl, json = true, referer = url).html
                FeedRecipe.items(text, feed, url, System.currentTimeMillis())
            } catch (e: Exception) {
                main.post { if (!done && gen == generation) feedFailed(url, callback, e.message?.take(60) ?: e.javaClass.simpleName) }
                null
            }
            if (items != null) main.post {
                if (done || gen != generation) return@post
                if (items.isEmpty()) { feedFailed(url, callback, "no events in it"); return@post }
                main.removeCallbacks(feedTimeout)
                val secs = (System.currentTimeMillis() - startedAt) / 1000.0
                Report.log(String.format(java.util.Locale.US, "List: read %d events from the site's data file for %s in %.1fs", items.size, Report.host(url), secs))
                val now = System.currentTimeMillis()
                val counts = items.groupingBy { ListStatus.of(it, now) }.eachCount()
                Report.log("List: by status " + counts.entries.joinToString(", ") { "${it.key} ${it.value}" })
                complete(ListResult(url, "", items, "recipe:${recipe.host}", null))
            }
        }
        return true
    }

    private val feedTimeout = Runnable { if (!done && onResult != null) feedFailed(requestUrl, onResult!!, "took too long") }

    private fun feedFailed(url: String, callback: (ListResult) -> Unit, why: String) {
        main.removeCallbacks(feedTimeout)
        Report.log("List: data file failed for ${Report.host(url)} ($why); reading the page")
        generation++ // a late data-file answer is ignored
        startRead(url, callback)
    }

    private fun startRead(url: String, callback: (ListResult) -> Unit) {
        mode = try { QuickLogic.mode(QuickReads.verdict(context, Uri.parse(url).host), System.currentTimeMillis()) } catch (_: Exception) { QuickLogic.Mode.FULL }
        begin(url, callback, quick = mode != QuickLogic.Mode.FULL, keepStart = false)
    }

    /** Starts one half of a read: the quick one (downloaded HTML, no scripts) or the full browser read. */
    private fun begin(url: String, callback: (ListResult) -> Unit, quick: Boolean, keepStart: Boolean) {
        done = false
        generation++
        val gen = generation
        onResult = callback
        requestUrl = url
        if (!keepStart) startedAt = System.currentTimeMillis()
        quickPhase = quick
        quickExtractStarted = false
        firstLoadedAt = 0L
        lastSignature = null
        unchanged = 0
        polling = false
        extracting = false
        retried = false
        slowRetried = false
        expandTried = false
        followedFrom = null
        recipes = null
        snapshotting = false
        pollFrom = 0L
        lookKey = null
        looks = 0
        lookMode = false
        blocked.set(0)
        val wv = createWebView(url)
        webView = wv
        // A fixed desktop-ish size, so pages lay out their list and not a phone menu.
        holder.addView(wv, ViewGroup.LayoutParams(1280, 720))
        if (quick) {
            Report.log("List: loading ${Report.host(url)} the quick way" + if (mode == QuickLogic.Mode.TEST) " (test against the full read)" else "")
            deadlineAt = System.currentTimeMillis() + QUICK_MS
            main.postDelayed(overallTimeout, QUICK_MS)
            thread {
                val page = try { QuickFetch.download(context, url) } catch (e: Exception) {
                    val why = e.message?.take(60) ?: e.javaClass.simpleName
                    main.post { if (!done && gen == generation) quickFailed(why) }
                    null
                }
                if (page != null) {
                    val html = QuickLogic.stripScripts(page.html)
                    main.post {
                        if (done || gen != generation) return@post
                        client?.siteUrl = page.url
                        try { wv.loadDataWithBaseURL(page.url, html, "text/html", "UTF-8", page.url) } catch (_: Throwable) { quickFailed("could not lay out the page") }
                    }
                }
            }
        } else {
            Report.log("List: loading ${Report.host(url)}")
            wv.loadUrl(url)
            schedulePollStart()
            deadlineAt = (if (keepStart) System.currentTimeMillis() else startedAt) + OVERALL_MS
            main.postDelayed(overallTimeout, deadlineAt - System.currentTimeMillis())
        }
        thread {
            val loaded = ListRecipes.load(context)
            main.post { if (!done && gen == generation) recipes = loaded }
        }
    }

    fun cancel() {
        done = true
        generation++
        onResult = null
        main.removeCallbacks(feedTimeout)
        main.removeCallbacks(overallTimeout)
        main.removeCallbacks(hardStop)
        main.removeCallbacks(poll)
        destroyWebView()
    }

    /** The quick half could not be used: the full browser read follows (the test keeps "found nothing"). */
    private fun quickFailed(why: String) {
        Report.log("List: the quick way didn't work ($why); reading with the browser")
        if (mode == QuickLogic.Mode.TEST) quickItems = emptyList() else quickFellBack = true
        restartFull()
    }

    /** Ends the quick half and starts the full browser read of the same page, keeping the callback and start time. */
    private fun restartFull() {
        val cb = onResult ?: return
        main.removeCallbacks(overallTimeout)
        main.removeCallbacks(hardStop)
        main.removeCallbacks(poll)
        destroyWebView()
        begin(requestUrl, cb, quick = false, keepStart = true)
    }

    /** After a full read with events: saves whether the quick way works for this site, from the test or a fallback. */
    private fun learnQuick(full: List<ListItem>) {
        val host = Uri.parse(requestUrl).host
        val h = Report.host(requestUrl)
        if (full.isEmpty()) {
            // Nothing either way: don't test this site on every read; the browser it is, tested again in a week.
            val q = quickItems
            if (mode == QuickLogic.Mode.TEST && q != null && q.none { !ClickRef.isClickUrl(it.url) }) {
                QuickReads.set(context, host, false)
                Report.log("List: nothing found either way on $h; using the browser (tested again in a week)")
            }
            return
        }
        if (mode == QuickLogic.Mode.TEST) {
            val q = quickItems ?: return
            val games = ScoreFeed.latest()
            val ok = QuickLogic.matches(full, q) { f -> try { ScoreMatch.find(f.title, f.startMillis, games) != null } catch (_: Exception) { false } }
            QuickReads.set(context, host, ok)
            Report.log(if (ok) "List: the quick way finds the same events on $h (${q.size} vs ${full.size}); using it from now on"
                else "List: the quick way misses events on $h (${q.size} vs ${full.size}); this site keeps the browser")
        } else if (mode == QuickLogic.Mode.QUICK && quickFellBack) {
            QuickReads.set(context, host, false)
            Report.log("List: the quick way stopped working on $h; this site keeps the browser")
        }
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

    private fun currentPage(): String = client?.siteUrl ?: requestUrl

    /** Pushes the overall deadline out to at least [atLeastMs] after [startedAt] (never pulls it in). */
    private fun extendDeadline(atLeastMs: Long) {
        val target = startedAt + atLeastMs
        if (done || target <= deadlineAt) return
        deadlineAt = target
        main.removeCallbacks(overallTimeout)
        main.postDelayed(overallTimeout, maxOf(target - System.currentTimeMillis(), 1000L))
    }

    /** Result when the page we moved on to (an embedded frame) could not be loaded: the list page just has no events we can read. */
    private fun noEventsOnOriginal(): ListResult {
        val page = followedFrom ?: currentPage()
        Report.log("List: the embedded frame could not be read")
        return ListResult(page, "", emptyList(), "automatic", ListResult.NO_EVENTS, noEvents = true)
    }

    private fun fail(msg: String): ListResult {
        Report.log("List: $msg")
        return ListResult(currentPage(), "", emptyList(), "automatic", msg)
    }

    private fun finish(result: ListResult) {
        if (done) return
        // The page could not be opened either way during a test: no re-test on every read; try again in a day.
        if (!quickPhase && mode == QuickLogic.Mode.TEST && result.error != null && !result.noEvents && quickItems?.isEmpty() == true) {
            QuickReads.set(context, Uri.parse(requestUrl).host, false,
                System.currentTimeMillis() - QuickLogic.BROWSER_RECHECK_MS + 24 * 60 * 60_000L)
            Report.log("List: ${Report.host(requestUrl)} couldn't be opened either way; testing the quick way again in a day")
        }
        val n = blocked.get()
        if (n > 0) Report.log("List: blocked $n heavy files")
        val wv = webView
        // A2: a snapshot (picture + page copy, on the main thread) only when the read failed or found nothing.
        // A successful read leaves the last failure's snapshot in place for "Save sample".
        if (wv == null || snapshotting || !shouldSnapshot(result)) {
            complete(result)
            return
        }
        // Keep a snapshot of what the hidden browser saw, for "Save sample" (diagnosing pages we misread).
        snapshotting = true
        val gen = generation
        val note = if (result.error != null) result.error else "${result.items.size} events"
        val page = currentPage()
        val image = try { picture(wv) } catch (t: Throwable) { null }
        val guard = Runnable { if (gen == generation) complete(result) }
        main.postDelayed(guard, 3000)
        try {
            wv.evaluateJavascript(SNAP_JS) { raw ->
                main.removeCallbacks(guard)
                try {
                    val html = if (raw == null || raw == "null") "" else org.json.JSONTokener(raw).nextValue() as? String ?: ""
                    SampleRecorder.page(page, html, image, note)
                } catch (t: Throwable) {
                    SampleRecorder.page(page, "", image, note)
                }
                if (gen == generation) complete(result)
            }
        } catch (t: Throwable) {
            main.removeCallbacks(guard)
            complete(result)
        }
    }

    private fun complete(result: ListResult) {
        if (done) return
        val cb = onResult
        cancel()
        cb?.invoke(result)
    }

    /** Draws the WebView (1280x720) into a small JPEG. */
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

    private fun createWebView(url: String): WebView {
        val wv = WebView(context)
        wv.isFocusable = false
        wv.isFocusableInTouchMode = false
        SafeWeb.configure(wv)
        // This hidden reader never plays anything.
        wv.settings.mediaPlaybackRequiresUserGesture = true
        wv.webChromeClient = SafeChromeClient()
        val c = object : SafeWebViewClient(url) {
            override fun onLoaded() {
                if (webView === wv) pageLoaded() // never an old web view being cleared after the quick half
            }

            /** C1: first paint; start looking without waiting for slow ads and trackers to finish loading. */
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                main.post {
                    if (done || quickPhase || webView !== wv) return@post
                    if (firstLoadedAt == 0L) firstLoadedAt = System.currentTimeMillis()
                    startPolling()
                }
            }

            /** A1: pictures, fonts and media are answered empty (never the page itself; image links come from attributes). */
            override fun blockResource(request: WebResourceRequest): Boolean {
                // Quick half: only style sheets load (layout needs them); no scripts, frames, pictures or fonts.
                if (quickPhase && !request.isForMainFrame) {
                    val a = request.requestHeaders?.entries?.firstOrNull { it.key.equals("Accept", true) }?.value ?: ""
                    val css = a.contains("text/css") || (request.url?.path ?: "").lowercase().endsWith(".css")
                    if (!css) { blocked.incrementAndGet(); return true }
                    return false
                }
                val accept = request.requestHeaders?.entries?.firstOrNull { it.key.equals("Accept", true) }?.value
                if (!ListReadLogic.isHeavy(accept, request.url?.path, request.isForMainFrame)) return false
                blocked.incrementAndGet()
                return true
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true && !done && firstLoadedAt == 0L) {
                    main.post { finish(if (followedFrom != null) noEventsOnOriginal() else fail("Could not open the page (${error?.description ?: "network error"})")) }
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?) {
                val code = errorResponse?.statusCode ?: return
                if (request?.isForMainFrame == true && code >= 400 && !done && firstLoadedAt == 0L) {
                    main.post { finish(if (followedFrom != null) noEventsOnOriginal() else fail("The page answered HTTP $code")) }
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                main.post { finish(fail("The page crashed the web view")) }
                return true
            }
        }
        client = c
        wv.webViewClient = c
        return wv
    }

    private fun pageLoaded() {
        if (done) return
        if (quickPhase) {
            // The downloaded page and its style sheets are in: read it once (nothing on it changes).
            if (!quickExtractStarted) { quickExtractStarted = true; extract(allowRetry = false) }
            return
        }
        val now = System.currentTimeMillis()
        if (firstLoadedAt == 0L) firstLoadedAt = now
        // a (re)load restarts the "has it stopped changing" count
        lastSignature = null
        unchanged = 0
        startPolling()
    }

    /** C1: polling also starts [EARLY_POLL_MS] after the load began, if neither first paint nor the full load came yet. */
    private fun schedulePollStart() {
        val gen = generation
        val seq = ++loadSeq
        main.postDelayed({ if (!done && gen == generation && seq == loadSeq) startPolling() }, EARLY_POLL_MS)
    }

    private fun startPolling() {
        if (done || polling) return
        polling = true
        if (pollFrom == 0L) pollFrom = System.currentTimeMillis()
        main.postDelayed(poll, POLL_MS)
    }

    /**
     * One look at the page. Settled = unchanged for 2 polls in a row, or 15 s of polling: then read it (old rule).
     * C2: from [LOOK_AFTER_MS] of polling on, the extractor itself runs at each poll (at most [ListReadLogic.MAX_LOOKS]
     * times); two equal non-empty results in a row finish the read even on pages that never stop changing.
     * Nothing is read while the document is still "loading".
     */
    private fun pollOnce() {
        val wv = webView ?: return
        val gen = generation
        wv.evaluateJavascript(SIGNATURE_JS) { raw ->
            if (done || gen != generation || extracting) return@evaluateJavascript
            val full = (try { MiniJson.parse(raw ?: "null") as? String } catch (_: Exception) { null }) ?: ""
            val state = full.substringBefore('|', "")
            val sig = full.substringAfter('|')
            if (state == "loading") {
                lastSignature = null
                unchanged = 0
                main.postDelayed(poll, POLL_MS)
                return@evaluateJavascript
            }
            if (sig == lastSignature) unchanged++ else { unchanged = 0; lastSignature = sig }
            val waited = System.currentTimeMillis() - pollFrom
            when {
                unchanged >= 2 || waited >= SETTLE_MAX_MS -> { lookMode = false; expandThenExtract() }
                waited >= LOOK_AFTER_MS && looks < ListReadLogic.MAX_LOOKS -> { lookMode = true; expandThenExtract() }
                else -> main.postDelayed(poll, POLL_MS)
            }
        }
    }

    /**
     * Once per page load, right after it settles: presses "show more" buttons (assets/list-expand.js, top frame),
     * waits for the page to settle again (cap [EXPAND_SETTLE_MS]) and then extracts. Nothing pressed = no delay.
     * If a press changes the address, the normal settle polling takes over on the new page.
     */
    private fun expandThenExtract() {
        val wv = webView ?: return
        if (done || extracting) return
        if (expandTried) { afterExpand(); return }
        expandTried = true
        val script = try { loadAsset(context, "list-expand.js") } catch (_: Exception) { afterExpand(); return }
        val gen = generation
        val urlBefore = try { wv.url } catch (_: Throwable) { null }
        try {
            wv.evaluateJavascript("($script)()") { raw ->
                if (done || gen != generation || extracting) return@evaluateJavascript
                val pressed = try {
                    val inner = MiniJson.parse(raw ?: "null") as? String
                    ((MiniJson.parse(inner ?: "") as? Map<*, *>)?.get("pressed") as? Double)?.toInt() ?: 0
                } catch (_: Exception) { 0 }
                if (pressed <= 0) { afterExpand(); return@evaluateJavascript }
                Report.log("List: pressed $pressed 'show more' button(s)")
                lastSignature = null
                unchanged = 0
                val until = System.currentTimeMillis() + EXPAND_SETTLE_MS
                main.postDelayed({ settleAfterPress(gen, urlBefore, until) }, EXPAND_POLL_MS)
            }
        } catch (_: Throwable) {
            afterExpand()
        }
    }

    private fun settleAfterPress(gen: Int, urlBefore: String?, until: Long) {
        val wv = webView ?: return
        if (done || gen != generation || extracting) return
        val urlNow = try { wv.url } catch (_: Throwable) { urlBefore }
        if (urlNow != urlBefore) {
            // the press navigated the main frame: do not press again; settle and read the new page as usual
            Report.log("List: a press changed the page address; reading the new page")
            lastSignature = null
            unchanged = 0
            main.removeCallbacks(poll)
            main.postDelayed(poll, POLL_MS)
            return
        }
        wv.evaluateJavascript(SIGNATURE_JS) { raw ->
            if (done || gen != generation || extracting) return@evaluateJavascript
            val sig = (try { MiniJson.parse(raw ?: "null") as? String } catch (_: Exception) { null }) ?: ""
            if (sig == lastSignature) unchanged++ else { unchanged = 0; lastSignature = sig }
            if (unchanged >= 2 || System.currentTimeMillis() >= until) {
                afterExpand()
            } else {
                main.postDelayed({ settleAfterPress(gen, urlBefore, until) }, EXPAND_POLL_MS)
            }
        }
    }

    private fun afterExpand() { if (lookMode) look() else extract(allowRetry = true) }

    /**
     * Runs the extractor once (recipe and learned row shapes included) and hands the parsed result to [then]
     * (null = unreadable). Waits briefly for the recipe list first; [again] restarts the caller then.
     */
    private fun runExtractor(again: () -> Unit, then: (Extraction?, ListRecipes.Recipe?) -> Unit) {
        val wv = webView ?: return
        if (done || extracting) return
        // The recipe list may still be downloading; give it a moment (the overall timeout bounds this).
        if (recipes == null && System.currentTimeMillis() - startedAt < OVERALL_MS - 6000) {
            main.postDelayed({ if (!done) again() }, 300)
            return
        }
        extracting = true
        val gen = generation
        val pageHost = Uri.parse(currentPage()).host
        val reqHost = Uri.parse(requestUrl).host
        val recipe = recipes?.let { ListRecipes.match(it, pageHost) ?: ListRecipes.match(it, reqHost) }
        val script = try { loadScript(context) } catch (e: Exception) { finish(fail("Reader script missing")); return }
        // A recipe may hold only a time zone (no row selector): then the rows are read automatically.
        val rowRecipe = if (recipe != null && recipe.item != null) recipe else null
        val arg = if (rowRecipe != null) JSONObject.quote(rowRecipe.toJson()) else "null"
        val zone = recipe?.zone ?: ZoneId.systemDefault()
        val shapes = (LearnedShapes.forHost(context, pageHost) + LearnedShapes.forHost(context, reqHost)).distinct()
        val learnedArg = if (shapes.isEmpty()) "null" else JSONObject.quote(org.json.JSONArray(shapes).toString())
        wv.evaluateJavascript("($script)($arg, $learnedArg)") { raw ->
            if (done || gen != generation) return@evaluateJavascript
            val ex = try {
                parseExtraction(MiniJson.parse(raw ?: "null") as? String ?: "", currentPage(), rowRecipe?.host, zone = zone)
            } catch (e: Exception) {
                null
            }
            then(ex, recipe)
        }
    }

    private fun extract(allowRetry: Boolean) {
        runExtractor({ extract(allowRetry) }) { ex, _ -> handle(ex, allowRetry) }
    }

    /** C2: one look at the events while the page may still be changing; two equal non-empty looks in a row finish the read. */
    private fun look() {
        runExtractor({ look() }) { ex, _ ->
            looks++
            val items = ex?.result?.items
            val key = if (items == null) "" else ListReadLogic.eventsKey(items)
            if (ex != null && ListReadLogic.stable(lookKey, key)) {
                val secs = (System.currentTimeMillis() - startedAt) / 1000.0
                Report.log(String.format(java.util.Locale.US, "List: events stable after %.1fs (%d looks)", secs, looks))
                handle(ex, allowRetry = false)
            } else {
                lookKey = key.ifEmpty { null }
                extracting = false
                main.removeCallbacks(poll)
                main.postDelayed(poll, LOOK_POLL_MS)
            }
        }
    }

    /** What to do with one extraction: retry paths for an empty result (frame, scroll, slow page), else report and finish. */
    private fun handle(ex: Extraction?, allowRetry: Boolean) {
        val wv = webView ?: return
        val gen = generation
        val reqHost = Uri.parse(requestUrl).host
        if (quickPhase) {
            val items = ex?.result?.items
            if (mode == QuickLogic.Mode.TEST) {
                quickItems = items ?: emptyList()
                Report.log("List: quick way found ${quickItems?.size ?: 0} events in ${String.format(java.util.Locale.US, "%.1f", (System.currentTimeMillis() - startedAt) / 1000.0)}s; now the full read to compare")
                restartFull()
                return
            }
            // Rows without links of their own (section headings) are not a real find on a quick read.
            if (items.isNullOrEmpty() || items.all { ClickRef.isClickUrl(it.url) }) {
                quickFailed(if (items.isNullOrEmpty()) "found nothing" else "found only headings")
                return
            }
        }
        run {
            val parsed = ex?.result
            if (ex == null || parsed == null) {
                finish(fail("The page could not be read"))
                return
            }
            if (parsed.items.isEmpty() && allowRetry) {
                val pick = if (followedFrom == null) FramePick.pick(ex.frames, currentPage()) else null
                // 1. a big embedded frame holds the page's content: read that instead
                if (pick != null && pick.strong) {
                    follow(pick.url, ex.debug)
                    return
                }
                // 2. some lists load more when scrolled; scroll once and look again
                if (!retried) {
                    retried = true
                    extracting = false
                    Report.log("List: nothing found; scrolling once and retrying")
                    wv.evaluateJavascript("window.scrollTo(0, document.body ? document.body.scrollHeight : 0)", null)
                    lastSignature = null
                    unchanged = 0
                    main.postDelayed({ if (!done && gen == generation) extract(allowRetry = true) }, 2500)
                    return
                }
                // 3. a nearly empty page or one still showing a loading indicator: give it more time
                if (!slowRetried && (ex.textLen < SHORT_TEXT || ex.loading)) {
                    slowRetried = true
                    extracting = false
                    Report.log("List: page looks slow (${ex.textLen} chars of text${if (ex.loading) ", loading indicator" else ""}); waiting ${SLOW_WAIT_MS / 1000}s and retrying")
                    extendDeadline(SLOW_OVERALL_MS)
                    lastSignature = null
                    unchanged = 0
                    main.postDelayed({ if (!done && gen == generation) extract(allowRetry = true) }, SLOW_WAIT_MS)
                    return
                }
                // 4. still nothing: the largest embedded frame is the best remaining guess
                if (pick != null) {
                    follow(pick.url, ex.debug)
                    return
                }
            }
            val secs = (System.currentTimeMillis() - startedAt) / 1000.0
            Report.log(
                String.format(java.util.Locale.US, "List: read %d events from %s (%s%s) in %.1fs",
                    parsed.items.size, Report.host(parsed.pageUrl), if (parsed.method.startsWith("recipe")) "recipe" else "automatic",
                    if (quickPhase) ", quick way" else "", secs)
            )
            if (!quickPhase) learnQuick(parsed.items)
            if (ex.debug.isNotEmpty()) Report.log("List: ${ex.debug}")
            if (parsed.items.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val counts = parsed.items.groupingBy { ListStatus.of(it, now) }.eachCount()
                Report.log("List: by status " + counts.entries.joinToString(", ") { "${it.key} ${it.value}" } +
                    (counts[ListStatus.FINISHED]?.let { " (start read as 4-24h ago; shown last as \"Maybe ended\")" } ?: ""))
            }
            // A real list (2+ rows, at least half with a time or live marker; never a timeless link group) on this site: remember its row shape for sibling pages with a single event.
            if (ex.shape.isNotEmpty() && ex.shapeTimed >= 0.5 && parsed.items.size >= 2 && !parsed.method.startsWith("recipe")) {
                LearnedShapes.remember(context, Uri.parse(currentPage()).host, ex.shape)
                if (reqHost != Uri.parse(currentPage()).host) LearnedShapes.remember(context, reqHost, ex.shape)
            }
            val shown = followedFrom?.let { parsed.copy(pageUrl = it) } ?: parsed // keep the list page's own URL for display
            val result = if (shown.items.isEmpty()) shown.copy(error = ListResult.NO_EVENTS, noEvents = true) else shown
            finish(result)
        }
    }

    /** Loads the embedded frame's own address in this web view and reads the events from there (done at most once per read). */
    private fun follow(frameUrl: String, debugOfPage: String) {
        val wv = webView ?: return
        if (debugOfPage.isNotEmpty()) Report.log("List: $debugOfPage")
        Report.log("List: the events seem to be inside an embedded frame; reading ${Report.host(frameUrl)} directly")
        followedFrom = currentPage()
        client?.siteUrl = frameUrl
        client?.firstPageLoaded = false
        extracting = false
        polling = false
        retried = false
        slowRetried = false
        expandTried = false // the embedded frame is a new load
        firstLoadedAt = 0L
        pollFrom = 0L
        lookKey = null
        looks = 0
        lookMode = false
        lastSignature = null
        unchanged = 0
        main.removeCallbacks(poll)
        extendDeadline(System.currentTimeMillis() - startedAt + FOLLOW_MS)
        wv.loadUrl(frameUrl)
        schedulePollStart()
    }

    companion object {
        private const val SHORT_TEXT = 1500
        private const val SLOW_WAIT_MS = 6_000L
        private const val SLOW_OVERALL_MS = 35_000L
        private const val FOLLOW_MS = 18_000L
        private const val POLL_MS = 1000L
        private const val EXPAND_POLL_MS = 500L
        private const val EXPAND_SETTLE_MS = 4000L
        private const val SETTLE_MAX_MS = 15_000L
        /** C1: settle polling starts this long after the load began even if the page has not painted or loaded yet. */
        private const val EARLY_POLL_MS = 2_000L
        /** C2: looks at the events themselves start after this much polling, then run about every [LOOK_POLL_MS]. */
        private const val LOOK_AFTER_MS = 3_000L
        private const val LOOK_POLL_MS = 1_500L
        private const val OVERALL_MS = 25_000L
        /** The quick half's own limit (download + layout + one extraction); then the full read takes over. */
        private const val QUICK_MS = 10_000L
        /** The data file's own limit; then the page is read. */
        private const val FEED_MS = 8_000L
        /** The page's structure without scripts/styles, plus open shadow roots, capped at 1.5 MB. */
        internal const val SNAP_JS = """(function(){try{
var c=document.documentElement.cloneNode(true);
c.querySelectorAll('script,style,noscript,link[rel=stylesheet],template').forEach(function(e){e.remove();});
c.querySelectorAll('svg').forEach(function(e){e.innerHTML='';});
var out='<!-- '+String(location.href).replace(/--/g,'- -')+' -->\n'+c.outerHTML;
var shadows=[];
document.querySelectorAll('*').forEach(function(e){if(e.shadowRoot){shadows.push(e.tagName+(e.id?'#'+e.id:'')+': '+e.shadowRoot.innerHTML.replace(/--/g,'- -'));}});
if(shadows.length){out+='\n<!-- OPEN SHADOW ROOTS\n'+shadows.join('\n\n')+'\n-->';}
var fr=[];document.querySelectorAll('iframe').forEach(function(f){fr.push(String(f.src));});
if(fr.length){out+='\n<!-- IFRAMES\n'+fr.join('\n').replace(/--/g,'- -')+'\n-->';}
return out.slice(0,1500000);
}catch(e){return 'snapshot failed: '+e;}})()"""
        private const val SIGNATURE_JS =
            "(function(){var r=document.readyState;var b=document.body;if(!b)return r+'|0:0';return r+'|'+b.innerText.length+':'+b.getElementsByTagName('*').length;})()"

        /** A2: snapshot for "Save sample" only after a failed or empty read. */
        internal fun shouldSnapshot(result: ListResult): Boolean = ListReadLogic.shouldSnapshot(result)

        @Volatile private var scriptCache: String? = null

        private fun loadAsset(context: Context, name: String): String =
            context.applicationContext.assets.open(name).bufferedReader().use { it.readText() }

        private fun loadScript(context: Context): String {
            scriptCache?.let { return it }
            val text = context.applicationContext.assets.open("list-extract.js").bufferedReader().use { it.readText() }
            scriptCache = text
            return text
        }

        /**
         * Turns the extractor's JSON into a [ListResult]: absolute http(s) URLs only, no ad networks
         * or TV-local addresses, start times parsed with [DateGuess]. Pure apart from the
         * [AdBlock] host checks; [now] and [zone] are injectable for tests.
         */
        internal fun parseExtraction(
            json: String,
            fallbackUrl: String,
            recipeHost: String?,
            now: Long = System.currentTimeMillis(),
            zone: ZoneId = ZoneId.systemDefault(),
            hostBlocked: (String?) -> Boolean = { AdBlock.isBlockedHost(it) || AdBlock.isLocalHost(it) },
        ): Extraction {
            val root = MiniJson.parse(json) as Map<*, *>
            val items = ArrayList<ListItem>()
            for (o in (root["items"] as? List<*>) ?: emptyList<Any?>()) {
                val m = o as? Map<*, *> ?: continue
                val url = (m["url"] as? String)?.trim().orEmpty()
                val uri = try { java.net.URI(url) } catch (_: Exception) { continue }
                if (uri.scheme?.lowercase() !in listOf("http", "https") || uri.host == null) continue
                if (hostBlocked(uri.host)) continue
                val title = (m["title"] as? String)?.trim().orEmpty()
                if (title.isEmpty()) continue
                val timeText = (m["timeText"] as? String)?.trim().orEmpty()
                val section = (m["section"] as? String)?.trim().orEmpty()
                val datetime = (m["datetime"] as? String)?.trim()?.ifEmpty { null }
                items.add(
                    ListItem(
                        title = title,
                        subtitle = (m["subtitle"] as? String)?.trim().orEmpty(),
                        url = url,
                        startMillis = DateGuess.guess(timeText, section, now, zone, datetime),
                        timeText = timeText,
                        section = section,
                        liveHint = m["liveHint"] == true,
                        imageUrl = (m["imageUrl"] as? String)?.ifEmpty { null },
                    )
                )
            }
            val method = if (root["method"] == "recipe" && recipeHost != null) "recipe:$recipeHost" else "automatic"
            val result = ListResult(
                pageUrl = (root["pageUrl"] as? String)?.ifEmpty { null } ?: fallbackUrl,
                pageTitle = (root["pageTitle"] as? String).orEmpty(),
                items = items,
                method = method,
                error = null,
            )
            val frames = ArrayList<FramePick.Frame>()
            for (o in (root["frames"] as? List<*>) ?: emptyList<Any?>()) {
                val m = o as? Map<*, *> ?: continue
                frames.add(
                    FramePick.Frame(
                        src = (m["src"] as? String).orEmpty(),
                        pct = (m["pct"] as? Double) ?: 0.0,
                        sameOrigin = m["same"] == true,
                        visible = m["vis"] == true,
                    )
                )
            }
            return Extraction(
                result, (root["debug"] as? String).orEmpty(),
                textLen = (root["textLen"] as? Double)?.toInt() ?: 0,
                loading = root["loading"] == true,
                frames = frames,
                clickRows = items.count { ClickRef.isClickUrl(it.url) },
                shape = (root["shape"] as? String).orEmpty().take(300),
                shapeTimed = (root["shapeTimed"] as? Double) ?: 0.0,
            )
        }

        internal class Extraction(
            val result: ListResult,
            val debug: String,
            val textLen: Int = 0,
            val loading: Boolean = false,
            val frames: List<FramePick.Frame> = emptyList(),
            val clickRows: Int = 0,
            val shape: String = "",
            val shapeTimed: Double = 0.0,
        )
    }
}

/** Tiny JSON reader (objects, arrays, strings, numbers, booleans, null) so this code is unit-testable on the JVM. Throws on malformed input. */
internal object MiniJson {
    fun parse(s: String): Any? {
        val p = Parser(s)
        p.skip()
        val v = p.value(0)
        p.skip()
        if (p.i != s.length) throw IllegalArgumentException("trailing characters")
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun skip() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(depth: Int): Any? {
            if (depth > 40) throw IllegalArgumentException("too deep")
            if (i >= s.length) throw IllegalArgumentException("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw IllegalArgumentException("unexpected '$c'")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("bad literal")
            i += word.length
            return v
        }

        private fun num(): Double {
            val st = i
            while (i < s.length && (s[i] in '0'..'9' || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDouble()
        }

        private fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw IllegalArgumentException("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw IllegalArgumentException("bad escape")
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            '/', '\\', '"' -> sb.append(e)
                            'u' -> {
                                if (i + 4 > s.length) throw IllegalArgumentException("bad \\u")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("bad escape")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            skip()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                skip()
                out.add(value(depth + 1))
                skip()
                if (i >= s.length) throw IllegalArgumentException("unterminated array")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return out }
                throw IllegalArgumentException("bad array")
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            skip()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                skip()
                if (i >= s.length || s[i] != '"') throw IllegalArgumentException("bad key")
                val k = str()
                skip()
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException("missing colon")
                i++
                skip()
                out[k] = value(depth + 1)
                skip()
                if (i >= s.length) throw IllegalArgumentException("unterminated object")
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return out }
                throw IllegalArgumentException("bad object")
            }
        }
    }
}

/**
 * Per-site "recipes" for index pages the automatic reader gets wrong, kept in recipes.json in the
 * repo root and downloaded (cached 6h in SharedPreferences "streamlink"/"recipes_json").
 */
object ListRecipes {
    // Read from the branch this build came from: the test app's recipes live on "test", the normal
    // app's on "main" (asking main from the test app gave HTTP 404 while main had no recipes.json yet).
    val URL = "https://raw.githubusercontent.com/drauch69-cmd/tv/${BuildConfig.CHANNEL}/recipes.json"
    private const val PREFS = "streamlink"
    private const val KEY = "recipes_json"
    private const val KEY_AT = "recipes_json_at"
    /** The saved copy is used at once; one older than this is refreshed in the background for the next read. */
    internal const val REFRESH_MS = 10 * 60 * 1000L
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)
    private const val MAX_BYTES = 256 * 1024

    /** [item] null = a site entry with only settings (like [zone]): its rows are read automatically. */
    data class Recipe(
        val host: String,
        val item: String?,
        val title: String? = null,
        val subtitle: String? = null,
        val time: String? = null,
        val link: String? = null,
        val section: String? = null,
        val live: String? = null,
        /** Time zone for times the page writes with no zone label ("zone": "UTC"); labelled times still win. */
        val zone: ZoneId? = null,
        /** What to do on this site's stream pages right after they load (a learned habit copied from a report, "habit"). */
        val habit: SiteHabits.Habit? = null,
        /** The site's data file to read instead of drawing the page ("feed", FeedRecipe.kt). */
        val feed: FeedRecipe.Feed? = null,
    ) {
        /** The JSON handed to list-extract.js. */
        fun toJson(): String {
            val o = JSONObject()
            o.put("host", host); item?.let { o.put("item", it) }
            title?.let { o.put("title", it) }; subtitle?.let { o.put("subtitle", it) }; time?.let { o.put("time", it) }
            link?.let { o.put("link", it) }; section?.let { o.put("section", it) }; live?.let { o.put("live", it) }
            return o.toString()
        }
    }

    private fun normHost(h: String?): String = (h ?: "").trim().lowercase().removePrefix("www.").trimEnd('.')

    /** Reads recipes.json text; bad JSON gives an empty list; entries need "host" plus "item", a valid "zone", "habit" or "feed"; others are skipped. Never throws. */
    fun parse(json: String): List<Recipe> {
        val sites = try { (MiniJson.parse(json) as? Map<*, *>)?.get("sites") as? List<*> } catch (_: Exception) { null } ?: return emptyList()
        val out = ArrayList<Recipe>()
        for (e in sites) {
            val m = e as? Map<*, *> ?: continue
            val host = normHost(m["host"] as? String)
            val item = (m["item"] as? String)?.trim()?.ifEmpty { null }
            val zone = zoneOf(m["zone"] as? String)
            val habit = SiteHabits.fromRecipe(m["habit"] as? Map<*, *>)
            val feed = try { FeedRecipe.parse(m["feed"] as? Map<*, *>) } catch (_: Exception) { null }
            if (host.isEmpty() || (item == null && zone == null && habit == null && feed == null)) continue
            fun opt(k: String) = (m[k] as? String)?.trim()?.ifEmpty { null }
            out.add(Recipe(host, item, opt("title"), opt("subtitle"), opt("time"), opt("link"), opt("section"), opt("live"), zone, habit, feed))
        }
        return out
    }

    /** "UTC", "Europe/London", "+01:00"... as a zone; null when missing or not a zone. */
    internal fun zoneOf(raw: String?): ZoneId? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return null
        return try { ZoneId.of(t) } catch (_: Exception) { null }
    }

    /** The recipe for this page host (exact or a parent domain of it); the most specific one wins. */
    fun match(recipes: List<Recipe>, pageHost: String?): Recipe? {
        val h = normHost(pageHost)
        if (h.isEmpty()) return null
        return recipes.filter { h == it.host || h.endsWith("." + it.host) }.maxByOrNull { it.host.length }
    }

    /**
     * The saved copy at once when there is one (refreshed in the background when over [REFRESH_MS] old, for the next
     * read); with no saved copy, a blocking download. Call off the main thread.
     */
    fun load(context: Context): List<Recipe> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY, null)
        val at = prefs.getLong(KEY_AT, 0L)
        val now = System.currentTimeMillis()
        if (cached != null) {
            if (!(now - at in 0 until REFRESH_MS)) refreshInBackground(prefs)
            return parse(cached)
        }
        var why = ""
        val fresh = try { download() } catch (e: Exception) { why = describeFailure(e); null }
        if (fresh != null) {
            prefs.edit().putString(KEY, fresh).putLong(KEY_AT, now).apply()
            return parse(fresh)
        }
        Report.logOnce("recipes-fail", "List: could not refresh recipes ($why); using ${if (cached != null) "the saved copy" else "none"}")
        return if (cached != null) parse(cached) else emptyList()
    }

    @Volatile private var savedText: String? = null
    @Volatile private var savedParsed: List<Recipe> = emptyList()

    /**
     * The saved copy, parsed (kept until the text changes), without ever waiting for the network: for the stream finder.
     * Starts a background refresh when the copy is missing or over [REFRESH_MS] old. Safe on the main thread.
     */
    fun saved(context: Context): List<Recipe> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val text = prefs.getString(KEY, null)
        val at = prefs.getLong(KEY_AT, 0L)
        if (text == null || !(System.currentTimeMillis() - at in 0 until REFRESH_MS)) refreshInBackground(prefs)
        if (text == null) return emptyList()
        if (text != savedText) {
            savedParsed = parse(text)
            savedText = text
        }
        return savedParsed
    }

    private fun refreshInBackground(prefs: android.content.SharedPreferences) {
        if (!refreshing.compareAndSet(false, true)) return
        thread(name = "recipes-refresh") {
            try {
                val fresh = try { download() } catch (e: Exception) {
                    Report.logOnce("recipes-fail", "Recipes: could not refresh (${describeFailure(e)}); using the saved copy")
                    null
                }
                if (fresh != null) prefs.edit().putString(KEY, fresh).putLong(KEY_AT, System.currentTimeMillis()).apply()
            } finally {
                refreshing.set(false)
            }
        }
    }

    /** Short reason for the log: "HTTP 404", or the exception's class and message. */
    internal fun describeFailure(e: Exception): String {
        val m = e.message?.take(80)
        return if (e is RecipesHttpException) m ?: "HTTP error" else e.javaClass.simpleName + (if (m.isNullOrBlank()) "" else ": $m")
    }

    internal class RecipesHttpException(msg: String) : java.io.IOException(msg)

    private fun download(): String? {
        val c = URL(URL).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.instanceFollowRedirects = true
            val code = c.responseCode
            if (code != 200) throw RecipesHttpException("HTTP $code")
            val bytes = c.inputStream.use { it.readNBytesLimited(MAX_BYTES) } ?: throw RecipesHttpException("file too large")
            val text = String(bytes, Charsets.UTF_8)
            // only keep text that is valid JSON with a "sites" list
            val root = MiniJson.parse(text) as? Map<*, *> ?: throw RecipesHttpException("not a recipes file")
            if (root["sites"] !is List<*>) throw RecipesHttpException("no \"sites\" list in the file")
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesLimited(max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) return null
        }
        return out.toByteArray()
    }
}
