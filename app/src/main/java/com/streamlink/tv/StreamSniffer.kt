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
    /** Checked by [Verifier] and found to be a playlist. False: ranked anyway, but not confirmed playable. */
    val verified: Boolean = true,
    /** Why the verifier rejected it (e.g. "server said 403"), or "" when it passed or was never checked. */
    val note: String = "",
    /** The player frame this source was found on (a link player), or "" for the page itself; used to get a fresh address when its access code expires. */
    val playerUrl: String = "",
    /**
     * A link whose player gave no verified stream: [url] is the link's player page, not a playlist. Choosing it
     * opens that page as its own page (clean player or site player); it is never played in ExoPlayer, never picked
     * by automatic source fallback and never renewed.
     */
    val openPage: Boolean = false,
    /**
     * Content type the verifier saw ("application/x-mpegURL" or "application/dash+xml") when the address does not
     * say so itself (a disguised playlist address with no m3u8/.mpd in it); "" otherwise. The player uses it.
     */
    val mime: String = "",
)

/** Everything found on a page. Empty [sources] means no stream was found. */
data class StreamSet(
    val pageUrl: String,
    val sources: List<Source>,
    /** Why nothing was found, when the sniffer knows (display/log only). */
    val reason: String = "",
    /** The address the search was asked to open (before redirects); "" when unknown. Used by [LastAddress]. */
    val requestedUrl: String = "",
    /** Nothing playable because every candidate answered 404/410 (the stream behind the page is offline); see [DeadStream]. */
    val offline: Boolean = false,
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
    /** Taken from inside another request (an analytics ping): often without the access code the real request carries. */
    val embedded: Boolean = false,
    /** Only looks like a playlist by its address words (see [StreamUrls.isPossiblePlaylist]); must pass the content check. */
    val possible: Boolean = false,
    /** The referers of [referers] that may be remembered for the stream server (the page itself or its player frame; see [RefererTrust]). */
    val rememberable: List<String> = emptyList(),
)

/**
 * Loads a page in a hidden WebView (with popups, off-site redirects and ad networks blocked),
 * nudges the page's player to start, collects every video stream it requests, checks them,
 * and reports the working ones ranked best first.
 */
class StreamSniffer(private val context: Context, private val holder: ViewGroup) {

    private val main = Handler(Looper.getMainLooper())

    /** Quiet run beside a playing stream (see [sniff], used by the access-code renewal): same page loader, but light (blocks all but playlists once one is seen). */
    @Volatile private var light = false
    /** False for quiet runs (Prefetcher, the player's renew/reconnect): they never set Timing marks. Light runs never do either. */
    @Volatile var timed = true
    private fun tm(name: String) { if (timed && !light) Timing.mark(name) }
    @Volatile private var playlistSeen = false
    private var lastReason = ""
    /** A background run beside a playing stream (BackupKeeper): writes nothing to the report. */
    @Volatile var quiet = false
    private fun log(m: String) { if (!quiet) Report.log(if (light) "Links: · $m" else m) }
    private var webView: WebView? = null
    private var client: SafeWebViewClient? = null
    private var done = true
    private var verifying = false
    private var generation = 0
    private var pageUrl = ""
    private var nudgeCount = 0
    private val frameUrls = Collections.synchronizedList(mutableListOf<String>())
    private val candidates = LinkedHashMap<String, StreamCandidate>()
    /** Possible playlists (disguised addresses) seen in this run, and the ones whose content turned out not to be a playlist. */
    private var possibleCount = 0
    private var repeatCount = 0
    private val repeatWatch = RepeatWatch()
    private val possibleRejected = HashSet<String>()
    private var onResult: ((StreamSet) -> Unit)? = null
    /** Early check result per direct stream address (true = working, non-ad playlist); see [EarlyFinish]. Main thread. */
    private val prechecked = HashMap<String, EarlyFinish.State>()
    private var lateExtras = false
    /** The page kept alive after the hand-over while its numbered addresses are read (see [finishThenRead]). */
    private var lateView: WebView? = null
    private var lateToken = 0
    /** Called once when the late read is over or dropped (the waiting screen finishes then). */
    var onLateDone: (() -> Unit)? = null
    val lateReading: Boolean get() = lateView != null

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

    private var referer: String? = null
    /** The address [sniff] was asked to open, as given (may carry a click fragment). */
    private var askedUrl = ""
    private var runStartedAt = 0L
    /** Page HTML for the stream-page snapshot; null until the snapshot script answers. */
    private var streamHtml: String? = null

    private val timeout = Runnable {
        if (done || verifying) return@Runnable
        if (candidates.isEmpty()) {
            lastReason = "no video stream found within ${timeoutMs / 1000}s"
            log("No video stream found within ${timeoutMs / 1000}s")
            val wv = webView
            if (!light && wv != null) {
                // Snapshot what the page showed for "Save sample", then finish (2 s at most).
                val gen = generation
                var finished = false
                val end = Runnable { if (!finished && gen == generation) { finished = true; finish(result(emptyList())) } }
                main.postDelayed(end, 2_000)
                try {
                    wv.evaluateJavascript(IndexReader.SNAP_JS) { raw ->
                        streamHtml = try { if (raw == null || raw == "null") "" else org.json.JSONTokener(raw).nextValue() as? String ?: "" } catch (_: Exception) { "" }
                        if (gen == generation) streamSnapshot(wv, "player search: no stream within ${timeoutMs / 1000}s" + playerNote())
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
    /** The first stream was seen and had a moment to be joined by its siblings: check them. */
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
        log("The click did not open anything; tapping the row")
        tap(wv, clickX, clickY)
    }
    /**
     * Primary search only: a player box that stays empty until one of the page's own source buttons is pressed
     * (see assets/source-switch.js). Runs ~5 s after the page loads; if there is still no player or playlist
     * ~10 s after a press, presses the next button (at most [MAX_SOURCE_PRESSES] in all).
     */
    private val sourceStep = Runnable { pressSource() }
    private var sourceScheduled = false
    private var sourceNext = 0
    private var sourcePresses = 0

    private fun pressSource() {
        val wv = webView ?: return
        if (done || verifying || light || clickRef != null || sourcePresses >= MAX_SOURCE_PRESSES) return
        if (candidates.isNotEmpty() || playlistSeen) return
        // A real player (video or frame) is on the page: the normal nudge and tap search carries on with it.
        if (playerX > 0 && playerKind != "box") return
        val gen = generation
        val stillWanted = { !done && !verifying && gen == generation && candidates.isEmpty() && !playlistSeen && !(playerX > 0 && playerKind != "box") }
        SourcePress.press(context, wv, sourceNext, stillWanted) { pressed ->
            if (gen != generation || done || verifying) return@press
            if (pressed != null) {
                sourceNext = pressed.index + 1
                sourcePresses++
                pressedIdx = pressed.index
                pressedCount = pressed.count
                log("No player on the page yet; pressing source button ${pressed.index + 1} of ${pressed.count}")
            }
            if (sourcePresses < MAX_SOURCE_PRESSES) main.postDelayed(sourceStep, SOURCE_RETRY_MS)
        }
    }


    // ---- learned habits per site (SiteHabits): what worked last time is done at once after the page loads ----
    private var habit: SiteHabits.Habit? = null
    private var habitApplied = false
    /** Uptime of the habit's latest tap (0 = none): the normal search skips its own tap within [HABIT_TAP_GAP_MS] of it. */
    private var habitTapAt = 0L
    private var habitMissed = false
    private var viaRemembered = false
    private var loadedAt = 0L
    private var tapsMade = 0
    private var lastTapKind = ""
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var tapW = 0
    private var tapH = 0
    private var pressedIdx = -1
    private var pressedCount = 0
    private var seen: Seen? = null
    /** What had been done when the first stream was seen. */
    private class Seen(val taps: Int, val kind: String, val x: Float, val y: Float, val w: Int, val h: Int, val srcIdx: Int, val srcCount: Int, val secs: Float)

    private val habitCheck = Runnable {
        if (done || verifying || !habitApplied || habitMissed || seen != null) return@Runnable
        habitMissed = true
        countHabitMiss()
    }
    private val habitSecondTap = Runnable {
        val wv = webView ?: return@Runnable
        val h = habit ?: return@Runnable
        if (done || verifying || candidates.isNotEmpty() || wv.width <= 0 || wv.height <= 0) return@Runnable
        habitTapAt = SystemClock.uptimeMillis()
        tap(wv, SiteHabits.toPixels(h.fx, wv.width), SiteHabits.toPixels(h.fy, wv.height))
    }

    /** Start of a run: forget the last run's bookkeeping and look up this site's habit (primary searches only; light and click-only runs never use or record). */
    private fun startHabit() {
        habit = if (!light && clickRef == null) SiteHabits.get(context, siteHost) else null
        habitApplied = false
        habitTapAt = 0L
        habitMissed = false
        viaRemembered = false
        loadedAt = 0L
        tapsMade = 0
        lastTapKind = ""
        pressedIdx = -1
        pressedCount = 0
        seen = null
    }

    private fun noteTap(kind: String, x: Float, y: Float, wv: WebView) {
        tapsMade++
        lastTapKind = kind
        lastTapX = x
        lastTapY = y
        tapW = wv.width
        tapH = wv.height
    }

    private fun noteSeen() {
        if (seen != null || light || clickRef != null) return
        val secs = if (loadedAt > 0) (SystemClock.uptimeMillis() - loadedAt) / 1000f else 0f
        seen = Seen(tapsMade, lastTapKind, lastTapX, lastTapY, tapW, tapH, pressedIdx, pressedCount, secs)
    }

    /** After the page loaded (primary search with a habit): tap the remembered spot and press the remembered source button at once. The normal search carries on beside it. */
    private fun applyHabit() {
        if (loadedAt == 0L) loadedAt = SystemClock.uptimeMillis()
        val h = habit ?: return
        val wv = webView ?: return
        if (habitApplied || done || verifying || light || clickRef != null || candidates.isNotEmpty() || wv.width <= 0 || wv.height <= 0) return
        habitApplied = true
        main.postDelayed(habitCheck, SiteHabits.deadlineMs(h))
        if (h.hasTap) {
            log("Using what worked on this site last time: " + SiteHabits.describe(h, withTap = true, withSource = false))
            habitTapAt = SystemClock.uptimeMillis()
            tap(wv, SiteHabits.toPixels(h.fx, wv.width), SiteHabits.toPixels(h.fy, wv.height))
            if (h.taps >= 2) main.postDelayed(habitSecondTap, SiteHabits.SECOND_TAP_MS)
        }
        if (h.hasSource) {
            val gen = generation
            val stillWanted = { !done && !verifying && gen == generation && candidates.isEmpty() && !playlistSeen }
            SourcePress.press(context, wv, h.srcIndex, stillWanted, h.srcCount) { pressed ->
                if (gen != generation || done || verifying) return@press
                if (pressed != null) {
                    sourceNext = pressed.index + 1 // the normal search does not press this one again
                    pressedIdx = pressed.index
                    pressedCount = pressed.count
                    log("Using what worked on this site last time: " + SiteHabits.describe(h, withTap = false, withSource = true))
                } else if (!h.hasTap && candidates.isEmpty()) {
                    // Nothing could be done (the page has a different number of buttons): this run is not the habit's to judge.
                    habitApplied = false
                    main.removeCallbacks(habitCheck)
                }
            }
        }
    }

    /** The habit gave no stream in its time: one miss (a habit that misses twice in a row is forgotten). Quiet runs (Prefetcher) never count misses, so they cannot make a habit flap. */
    private fun countHabitMiss() {
        if (!timed || light) return
        if (SiteHabits.missed(context, siteHost)) log("The learned habit stopped working; forgetting it")
    }

    /** End of a primary run: remember what worked after a verified stream, count a miss when the habit never got a stream. */
    private fun habitFinished(result: StreamSet) {
        if (light || clickRef != null || viaRemembered) return
        val s = seen
        if (result.sources.any { it.verified } && s != null) {
            val fresh = SiteHabits.fromRun(s.taps, s.kind, s.x, s.y, s.w, s.h, s.srcIdx, s.srcCount, s.secs, System.currentTimeMillis())
            val onTime = habitApplied && !habitMissed
            if (onTime) log("The learned habit worked (stream after ${String.format(java.util.Locale.US, "%.1f", s.secs)} s)")
            if (fresh != null) SiteHabits.save(context, siteHost, fresh) else if (onTime) SiteHabits.worked(context, siteHost)
        } else if (result.sources.isEmpty() && habitApplied && !habitMissed) {
            habitMissed = true
            countHabitMiss()
        }
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
     * [referer]: sent with the first request (a page opened from another page's Source list; the page it came from), or null.
     * [light]: a quiet run (access-code renewal): the page's site memory is left alone, the time is
     * [LIGHT_TIMEOUT_MS], and once a playlist is seen everything else is blocked. Players may still start.
     * [useRemembered]: a normal foreground run also re-checks the last address that worked for this page, in parallel
     * with the page load (see [tryRemembered]); never for light runs or click-only links. Reconnects pass false.
     * [lateExtras]: a successful primary search hands its set over at once and reads the page's numbered addresses
     * afterwards (the page stays alive until that read ends); they reach the player through [Session.addExtras].
     */
    fun sniff(url: String, referer: String? = null, light: Boolean = false, useRemembered: Boolean = true, lateExtras: Boolean = false, callback: (StreamSet) -> Unit) {
        cancel()
        this.lateExtras = lateExtras
        prechecked.clear()
        this.light = light
        askedUrl = url
        this.referer = referer
        lastReason = ""
        runStartedAt = SystemClock.uptimeMillis()
        playlistSeen = false
        val ref = ClickRef.parse(url)
        val target = ref?.pageUrl ?: ClickRef.pagePart(url) // the page itself, without any "#sl-click=" fragment
        if (isStreamUrl(target) || target.lowercase().substringBefore('?').endsWith(".mp4")) {
            log("Link is a video file itself; playing it directly")
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
        val base = if (light) LIGHT_TIMEOUT_MS else if (SiteMemory.shouldShorten(context, siteHost)) {
            log("The stream wasn't found on this site the last ${SiteMemory.fails(context, siteHost)} times; looking for ${SiteMemory.SHORT_TIMEOUT_MS / 1000} s only")
            SiteMemory.SHORT_TIMEOUT_MS
        } else TIMEOUT_MS
        timeoutMs = if (ref != null) base + CLICK_EXTRA_MS else base
        nudgeCount = 0
        sourceScheduled = false
        sourceNext = 0
        sourcePresses = 0
        playerX = -1f
        playerY = -1f
        playerKind = ""
        playerForeign = false
        playerFrameHost = ""
        playerLogged = false
        pageTapLogged = false
        playerTaps = 0
        firstPlayerTapAt = 0L
        startHabit()
        frameUrls.clear()
        candidates.clear()
        possibleCount = 0
        repeatCount = 0
        repeatWatch.reset()
        possibleRejected.clear()
        onResult = callback
        val wv = createWebView(target)
        webView = wv
        holder.addView(wv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        log(if (ref != null) "Loading the list page (the event has no link of its own, it has to be clicked)" else "Loading page")
        if (!referer.isNullOrBlank()) wv.loadUrl(target, mapOf("Referer" to referer)) else wv.loadUrl(target)
        main.postDelayed(timeout, timeoutMs)
        main.postDelayed(nudger, FIRST_NUDGE_MS)
        if (useRemembered && !light && ref == null) tryRemembered(generation, target)
    }

    /**
     * Feature "last address": while the page loads as usual, a background thread re-checks only the first stored
     * source of this page ([Verifier.quickCheck]). If it passes before the page search reached its own check
     * (not [verifying]) and this run is still the current one, the stored set is finished at once (finish cancels the
     * page load, timers and nudges). If it fails the entry is dropped and the search carries on untouched.
     */
    private fun tryRemembered(gen: Int, target: String) {
        val app = context.applicationContext
        thread(isDaemon = true, name = "remembered-check") {
            val entry = try { LastAddress.get(app, target) } catch (_: Exception) { null } ?: return@thread
            val first = LastAddress.firstPlayable(entry) ?: return@thread
            if (gen != generation) return@thread
            val ageMs = System.currentTimeMillis() - entry.savedAt
            // This server's addresses are known to die young and the saved one is older than any that still worked: skip the re-check.
            if (LastAddress.skipShortcut(ServerMemory.lifetimeFor(Report.host(first.url)), ageMs)) {
                log("Saved addresses from this server expire quickly; skipping the shortcut")
                return@thread
            }
            val mins = (ageMs / 60_000L).coerceAtLeast(0)
            log("Trying the last address that worked (saved $mins min ago)…")
            val cand = StreamCandidate(first.url, first.headers, first.referers, first.label, first.isDefaultLink)
            val checked = try { Verifier.quickCheck(cand, 3000L) { m -> log(m) } } catch (_: Exception) { null }
            ServerMemory.recordAge(Report.host(first.url), ageMs, checked != null)
            main.post {
                if (checked == null) {
                    if (gen == generation) log("The last address no longer works; searching the page")
                    LastAddress.forget(app, target, entry.pageUrl)
                    return@post
                }
                if (gen != generation || done || verifying) return@post // the page search got there first (or the run ended)
                log("The last address still works; playing it")
                if (timed) Timing.path = Timing.PATH_REMEMBERED
                viaRemembered = true
                finish(LastAddress.restore(entry, checked))
            }
        }
    }

    fun cancel() {
        cancelRun()
        dropLate()
    }

    /** Ends the run (timers, page) but leaves a late page-sources read alone. */
    private fun cancelRun() {
        done = true
        verifying = false
        generation++
        onResult = null
        main.removeCallbacks(timeout)
        main.removeCallbacks(nudger)
        main.removeCallbacks(collect)
        main.removeCallbacks(settle)
        main.removeCallbacks(tapRow)
        main.removeCallbacks(sourceStep)
        main.removeCallbacks(habitCheck)
        main.removeCallbacks(habitSecondTap)
        destroyWebView()
    }

    private fun currentPage(): String = client?.siteUrl ?: pageUrl

    private fun runSecs(): String = String.format(java.util.Locale.US, "%.1f", (SystemClock.uptimeMillis() - runStartedAt) / 1000.0)

    private fun result(sources: List<Source>) =
        StreamSet(currentPage(), sources, if (sources.isEmpty()) lastReason else "")

    private fun finish(result: StreamSet) {
        if (done) return
        if (!light) SiteMemory.record(context, siteHost, found = result.sources.isNotEmpty())
        habitFinished(result)
        val cb = onResult
        cancelRun()
        cb?.invoke(result.copy(requestedUrl = askedUrl))
    }

    private fun dropLate() {
        lateToken++
        val v = lateView
        val cb = onLateDone
        onLateDone = null
        if (v != null) {
            lateView = null
            try { v.stopLoading(); v.loadUrl("about:blank"); holder.removeView(v); v.destroy() } catch (_: Exception) {}
            cb?.invoke()
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

    /** Called on the main thread for each stream the page asks for. */
    private fun found(url: String, requestHeaders: Map<String, String>, embedded: Boolean = false, possible: Boolean = false, repeat: Boolean = false) {
        if (done || verifying || candidates.containsKey(url)) return
        if (possible && url in possibleRejected) return
        // Repeat-based possibles have their own budget so ad beacons cannot starve the word-based ones.
        if (possible && (if (repeat) repeatCount >= MAX_REPEAT_POSSIBLES else possibleCount >= MAX_POSSIBLES)) return
        if (possible) { if (repeat) repeatCount++ else possibleCount++ }
        if (AdBlock.isLocalHost(Report.host(url))) return
        // The player's own request carries the access code and real headers: prefer it over a copy found inside another request.
        if (embedded && candidates.values.any { !it.embedded && StreamUrls.sameStream(it.url, url) }) return
        val ua = webView?.settings?.userAgentString
        val headers = LinkedHashMap<String, String>()
        var requestReferer: String? = null
        for ((k, v) in requestHeaders) {
            when {
                k.equals("Referer", true) -> requestReferer = v
                // X-Requested-With names this app; some sites answer it with 403. A captured Origin is kept:
                // the verifier and the players send it as is, instead of deriving one from the referer.
                k.equals("Range", true) || k.equals("Accept-Encoding", true) || k.equals("X-Requested-With", true) -> {}
                else -> headers[k] = v
            }
        }
        if (headers.keys.none { it.equals("User-Agent", true) } && ua != null) headers["User-Agent"] = ua
        CookieManager.getInstance().getCookie(url)?.let { if (it.isNotBlank()) headers["Cookie"] = it }
        // The embedded player frames, newest first.
        val frames = synchronized(frameUrls) { frameUrls.toList() }.asReversed()
        val referers = if (embedded) {
            // Found inside another request: that request's Referer, then the player frames (no ad hosts), up to 4.
            Headers.embeddedReferers(requestReferer, frames, { AdBlock.isBlockedHost(Report.host(it)) }) + ""
        } else {
            // Candidates for "which page asked for this video": the frames, then the page itself, then no referer at all.
            // Each also as a bare origin ("https://host/") right after its full form.
            Headers.withOriginForms(
                (listOfNotNull(requestReferer) + frames + listOfNotNull(webView?.url, currentPage(), referer))
                    .filter { it.startsWith("http") }
                    .distinct(),
                5,
            ) + ""
        }
        if (!embedded) {
            // A direct request replaces the copy of the same stream found inside another request.
            val dropped = candidates.entries.filter { it.value.embedded && StreamUrls.sameStream(it.value.url, url) }.map { it.key }
            for (k in dropped) candidates.remove(k)
        }
        val trusted = listOf(siteHost, Report.host(currentPage()), Report.host(this.referer ?: ""), playerFrameHost)
        candidates[url] = StreamCandidate(url, headers, referers, "", true, embedded, possible, RefererTrust.eligibleReferers(referers, trusted))
        if (possible) {
            // Not a stream yet: no wait is started and playlistSeen stays as it is. A quick content check decides.
            log("Found a possible playlist: ${Report.host(url)}/… (checking its content)")
            checkPossible(candidates.getValue(url))
            return
        }
        val note = when {
            StreamUrls.hasQuery(url) -> " (with access code)"
            embedded -> " (no access code)"
            else -> ""
        }
        tm(Timing.SEEN)
        noteSeen()
        log("Found stream: ${Report.short(url)}$note")
        scheduleCollect(embedded)
        if (!embedded && !light && clickRef == null) precheck(candidates.getValue(url))
        if (candidates.values.count { !it.possible } >= MAX_CANDIDATES) verifyAndFinish()
    }

    /**
     * Primary search: each direct stream is checked at once, in the background, while the short wait for siblings runs.
     * When one passes, [tryEarlyFinish] ends the search without waiting out the rest of that window.
     */
    private fun precheck(c: StreamCandidate) {
        val gen = generation
        thread(isDaemon = true, name = "early-check") {
            val st = try { Verifier.precheck(c) { m -> log(m) } } catch (e: Exception) { EarlyFinish.State.BAD }
            main.post {
                if (gen != generation || done || verifying) return@post
                prechecked[c.url] = st
                if (st == EarlyFinish.State.GOOD_ADAPTIVE) tryEarlyFinish()
            }
        }
    }

    private fun tryEarlyFinish() {
        val items = candidates.values.map {
            EarlyFinish.Item(it.url, it.embedded, it.possible, prechecked[it.url] ?: EarlyFinish.State.PENDING)
        }
        val keep = EarlyFinish.keep(items) ?: return
        candidates.keys.retainAll(keep)
        log("A stream checked out; not waiting for more")
        verifyAndFinish()
    }

    /** After a stream (not a mere possible playlist) was added: start the short wait for siblings, or the wait for the player's own request. */
    private fun scheduleCollect(embedded: Boolean) {
        val direct = candidates.values.count { !it.embedded && !it.possible }
        if (!embedded && direct == 1) {
            // The first real request: the usual short wait for siblings (replaces any longer wait for one).
            main.removeCallbacks(collect)
            main.postDelayed(collect, COLLECT_MS)
        } else if (direct == 0 && candidates.values.count { !it.possible } == 1) {
            // Only copies found inside other requests so far: give the player's own request time to arrive.
            main.postDelayed(collect, EMBEDDED_WAIT_MS)
        }
    }

    /**
     * Looks at the content of a possible playlist in the background (one download, off the main thread). If it is
     * a playlist it is promoted to a normal stream (as if found directly: playlistSeen, wait for siblings);
     * if not it is forgotten. A result that arrives after the run moved on is ignored.
     */
    private fun checkPossible(c: StreamCandidate) {
        val gen = generation
        thread(isDaemon = true, name = "possible-check") {
            val ok = try {
                Verifier.confirmPossible(c) { m -> log(m) }
            } catch (e: Exception) {
                false
            }
            main.post {
                if (gen != generation || done || verifying) return@post
                val cur = candidates[c.url] ?: return@post
                if (!ok) {
                    candidates.remove(c.url)
                    possibleRejected.add(c.url)
                    return@post
                }
                candidates[c.url] = cur.copy(possible = false)
                prechecked[c.url] = EarlyFinish.State.GOOD_SINGLE // content just checked (kept for the ranking); never triggers an early finish
                tm(Timing.SEEN)
                noteSeen()
                log("The possible playlist is a playlist; using it as a stream")
                playlistSeen = true
                scheduleCollect(false)
            }
        }
    }

    private fun verifyAndFinish() {
        if (done || verifying) return
        verifying = true
        main.removeCallbacks(timeout)
        main.removeCallbacks(nudger)
        main.removeCallbacks(collect)
        main.removeCallbacks(settle)
        main.removeCallbacks(tapRow)
        main.removeCallbacks(sourceStep)
        // Real requests first: they carry the access code; copies found inside other requests are the last resort.
        // Order: direct requests, then copies found inside other requests, then possible playlists (disguised addresses).
        val list = candidates.values.sortedWith(compareBy<StreamCandidate>({ it.possible }, { it.embedded }))
        val page = currentPage()
        val gen = generation
        tm(Timing.CHECKING)
        log("Checking ${list.size} stream(s)…")
        thread(isDaemon = true, name = "stream-check") {
            val raw = try {
                Verifier.rankWithRestart(list) { m -> log(m) }
            } catch (e: Exception) {
                log("Checking failed: ${e.javaClass.simpleName}")
                list.map { Source(it.url, it.headers, it.referers, LinkOrder.sourceLabel(it.linkLabel, Report.host(it.url), "unchecked", true), it.isDefault, verified = false, note = "checking failed") }
            }
            // A possible playlist that did not pass the content check is not a source at all.
            val maybe = list.filter { it.possible }.map { it.url }.toSet()
            val sources = if (maybe.isEmpty()) raw else raw.filter { it.verified || it.url !in maybe }
            main.post {
                if (gen != generation || done) return@post // cancelled meanwhile
                tm(Timing.CHECKED)
                // A candidate whose every check answered 404/410 is offline, not unverified: never played, never a fallback.
                val gone = sources.count { DeadStream.isGone(it) }
                // No answer at all, even after Chrome's network engine was restarted: not played as an unchecked source either.
                val silent = sources.count { NoAnswer.isFinal(it) }
                val kept = if (gone > 0 || silent > 0) DeadStream.playable(sources).filter { !NoAnswer.isFinal(it) } else sources
                val offline = gone > 0 && kept.isEmpty()
                if (offline) { lastReason = "stream offline (server said 404)"; log(DeadStream.OFFLINE_LINE) }
                else if (silent > 0 && kept.isEmpty()) { lastReason = "no answer from the stream server"; log("The stream server gives no answer; not playing an unchecked stream") }
                val set = result(kept).copy(pageUrl = page, offline = offline)
                // Primary search that found a playable stream: one quick look for the page's own numbered addresses.
                // (verifying stays true meanwhile, so nothing else restarts the check.)
                if (!light && clickRef == null && kept.any { it.verified }) {
                    if (lateExtras) finishThenRead(page, set) else readPageSources(gen, page, set)
                } else { verifying = false; finish(set) }
            }
        }
    }

    /**
     * Once per primary search, at its end: runs assets/page-sources.js on the (still loaded) page and adds the numbered
     * addresses it finds to [set] as "open page" entries. Immediate when the page has no pick-list near the player; else up to 3 s (pages may rewrite the address a few hundred ms after a change). Any failure just means none.
     */
    private fun readPageSources(gen: Int, page: String, set: StreamSet, late: WebView? = null, token: Int = 0) {
        val wv = late ?: webView
        val script = try { loadAsset(context, "page-sources.js") } catch (e: Exception) { null }
        var ended = false
        // A late read (after the hand-over) lives as long as its token; the normal one as long as its run.
        fun alive() = if (late != null) token == lateToken else gen == generation && !done
        fun end(found: List<PageSources.Found>) {
            if (ended || !alive()) return
            ended = true
            val extra = PageSources.toSources(page, found, set.sources.map { it.url }.toSet())
            log("Page sources: ${extra.size} numbered address(es) on this page")
            if (late != null) {
                if (extra.isNotEmpty()) Session.addExtras(page, extra)
                dropLate()
                return
            }
            verifying = false
            finish(if (extra.isEmpty()) set else set.copy(sources = set.sources + extra))
        }
        if (wv == null || script == null) { end(emptyList()); return }
        main.postDelayed({ end(emptyList()) }, PAGE_SOURCES_MAX_MS)
        fun read(triesLeft: Int) {
            if (ended || !alive()) return
            try {
                wv.evaluateJavascript("($script)('read')") { raw ->
                    val found = PageSources.parse(raw)
                    when {
                        found != null -> end(found)
                        triesLeft > 0 -> main.postDelayed({ read(triesLeft - 1) }, PAGE_SOURCES_READ_MS)
                    }
                }
            } catch (_: Exception) {
                end(emptyList())
            }
        }
        try {
            // With no pick-list to try, "start" answers at once (links only) and the search is not held up.
            wv.evaluateJavascript("($script)('start')") { raw ->
                val now = PageSources.parse(raw)
                if (now != null) end(now) else main.postDelayed({ read(PAGE_SOURCES_READS) }, PAGE_SOURCES_READ_MS)
            }
        } catch (_: Exception) {
            end(emptyList())
        }
    }

    /**
     * Hands the set over at once and keeps the hidden page alive until its numbered addresses are read (at most
     * [PAGE_SOURCES_MAX_MS]); [readPageSources] then adds them through [Session.addExtras] and destroys the page.
     */
    private fun finishThenRead(page: String, set: StreamSet) {
        val wv = webView
        val script = try { loadAsset(context, "page-sources.js") } catch (e: Exception) { null }
        verifying = false
        if (wv == null || script == null) { log("Page sources: 0 numbered address(es) on this page"); finish(set); return }
        webView = null // finish() would destroy it; it is destroyed when the read ends
        client = null
        lateView = wv
        val token = ++lateToken
        finish(set)
        if (lateView === wv && token == lateToken) readPageSources(generation, page, set, wv, token)
    }

    private fun createWebView(url: String): WebView {
        val tmStart = SystemClock.uptimeMillis()
        val wv = WebView(context)
        Timing.browserCreated(SystemClock.uptimeMillis() - tmStart, timed && !light)
        wv.isFocusable = false
        wv.isFocusableInTouchMode = false
        SafeWeb.configure(wv)
        wv.webChromeClient = SafeChromeClient { quiet }
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
        val c = object : SafeWebViewClient(url, quiet = { quiet }) {
            override fun onRendererGone(view: WebView?, didCrash: Boolean) {
                main.post {
                    // The view is dead: drop our references first so nothing touches it again.
                    if (view == null || webView !== view) { SafeWeb.dispose(view); return@post }
                    webView = null
                    client = null
                    SafeWeb.dispose(view)
                    if (done) return@post
                    log("The hidden browser was shut down by the system" + if (didCrash) " (it crashed)" else " (low memory)")
                    lastReason = "web view crashed"
                    finish(result(emptyList()))
                }
            }

            override fun onFrame(url: String) {
                if (!AdBlock.isBlockedHost(Report.host(url))) frameUrls.add(url)
            }

            override fun onResource(request: WebResourceRequest) {
                val u = request.url.toString()
                val (isStream, embedded) = StreamUrls.classify(u)
                if (!isStream && embedded.isEmpty()) {
                    // An address with no m3u8/.mpd in it: a possible playlist, if it is a sub-resource and looks like one by its words.
                    if (!request.isForMainFrame && request.method.equals("GET", true) && StreamUrls.isPossiblePlaylist(u) &&
                        !AdBlock.isBlockedHost(request.url.host)) {
                        val headers = HashMap(request.requestHeaders ?: emptyMap())
                        main.post { found(u, headers, possible = true) }
                    } else if (!request.isForMainFrame && request.method.equals("GET", true) &&
                        !AdBlock.isBlockedHost(request.url.host) && repeatWatch.seen(u, System.currentTimeMillis())) {
                        val headers = HashMap(request.requestHeaders ?: emptyMap())
                        main.post {
                            if (!done && !verifying && !candidates.containsKey(u) && u !in possibleRejected) {
                                log("A repeating request may be a playlist: ${Report.host(u)}/…; checking it")
                            }
                            found(u, headers, possible = true, repeat = true)
                        }
                    }
                    return
                }
                if (isStream) playlistSeen = true
                val headers = HashMap(request.requestHeaders ?: emptyMap())
                main.post {
                    // Addresses carried inside another request (e.g. an analytics ping): the request's
                    // Referer goes with them, through the headers passed to found().
                    for (e in embedded) {
                        if (!candidates.containsKey(e)) log("Found a stream address inside another request: ${Report.host(e)}/…")
                        found(e, headers, embedded = true)
                    }
                    if (isStream) found(u, headers)
                }
            }

            override fun blockResource(request: WebResourceRequest): Boolean =
                // Once a playlist is known, nothing else is needed: block all but playlists
                // (also video pieces disguised as pictures, which have no video extension).
                light && playlistSeen && !isStreamUrl(request.url.toString())

            override fun onLoaded() {
                tm(Timing.LOADED)
                if (clickRef != null && clickPhase == 0) {
                    // list page of a click-only event: let it settle, then click the row (nudging first could tap another row)
                    clickPhase = 1
                    clickLoadedAt = System.currentTimeMillis()
                    main.postDelayed(settle, 1000)
                    return
                }
                if (!sourceScheduled && !light && clickRef == null) {
                    sourceScheduled = true
                    main.postDelayed(sourceStep, SOURCE_FIRST_MS)
                }
                applyHabit()
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
            if (done || verifying || webView !== wv) return@evaluateJavascript
            if (raw == null || raw == "null") { tapStep(wv); return@evaluateJavascript }
            try {
                val o = org.json.JSONObject(JSONTokener(raw).nextValue() as String)
                val iw = o.optDouble("iw", 0.0)
                if (iw > 0 && o.has("px") && wv.width > 0) {
                    val scale = wv.width / iw.toFloat()
                    playerX = (o.optDouble("px") * scale).toFloat()
                    playerY = (o.optDouble("py") * scale).toFloat()
                    playerKind = o.optString("kind", "video")
                    val fh = o.optString("fh", "")
                    if (playerKind == "frame" && fh.isNotEmpty()) playerFrameHost = fh
                    playerForeign = playerKind == "frame" && fh.isNotEmpty() && !sameSite(fh, siteHost)
                    if (!playerLogged) {
                        playerLogged = true
                        val what = when (playerKind) {
                            "frame" -> "player frame" + if (fh.isNotEmpty()) " ($fh)" else ""
                            "box" -> "player box (its frame isn't there yet)"
                            else -> "video"
                        }
                        log(if (nudgeCount <= 2) "Found the $what; tapping its middle" else "Found the $what after ${runSecs()}s; tapping its middle")
                    }
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
            // A video found above ends the search; otherwise tap (with the player position just read).
            if (!done && !verifying && candidates.isEmpty()) tapStep(wv)
        }
    }

    private fun tapStep(wv: WebView) {
        // Players inside iframes can't be scripted from here, so tap them. Any popup or redirect a tap
        // triggers is blocked. Aim at the middle of the player once the page shows one (a video, a big
        // frame or a player box): tap it on every nudge, since these players often spend the first tap
        // on an ad. With no player seen, tap the middle of the page on every other nudge.
        // (On a click-only list page that is still showing the list, a tap would open some other event.)
        val onList = clickRef != null && !pageChanged()
        // A learned-habit tap was just made: skip this nudge's tap (the nudge's other work already ran).
        val habitBusy = habitTapAt != 0L && SystemClock.uptimeMillis() - habitTapAt < HABIT_TAP_GAP_MS
        if (wv.width > 0 && !onList && !habitBusy) {
            val hasPlayer = playerX > 0
            if (hasPlayer && playerTaps < MAX_PLAYER_TAPS) {
                playerTaps++
                if (firstPlayerTapAt == 0L) firstPlayerTapAt = SystemClock.uptimeMillis()
                tm(Timing.TAPPED)
                noteTap(KIND_PLAYER_TAP, playerX, playerY, wv)
                tap(wv, playerX, playerY)
            } else if (!hasPlayer && nudgeCount % 2 == 0) {
                if (!pageTapLogged) { pageTapLogged = true; log("No player box seen yet; tapping the middle of the page") }
                tm(Timing.TAPPED)
                noteTap(SiteHabits.KIND_PAGE, wv.width / 2f, wv.height / 2f, wv)
                tap(wv, wv.width / 2f, wv.height / 2f)
            }
        }
    }


    /** Same site, or one is a subdomain of the other (cdn.example.com vs example.com). */
    private fun sameSite(a: String, b: String): Boolean {
        val x = a.lowercase().removePrefix("www.")
        val y = b.lowercase().removePrefix("www.")
        return x == y || x.endsWith(".$y") || y.endsWith(".$x")
    }

    private var playerKind = ""
    private var playerForeign = false
    /** Host of the player frame the nudge picked (the biggest landscape frame); "" when none. Trusted as a referer to remember. */
    private var playerFrameHost = ""
    private var playerLogged = false
    private var pageTapLogged = false
    private var playerTaps = 0
    private var firstPlayerTapAt = 0L
    private var playerX = -1f
    private var playerY = -1f

    private fun playerNote(): String = when {
        playerX <= 0 -> ", no player seen"
        else -> ", player: $playerKind${if (playerForeign) " from another site" else ""}, tapped $playerTaps time(s)"
    }

    /** Keeps the stream page for "Save sample": picture now, HTML from [streamHtml]. */
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
        private const val HABIT_TAP_GAP_MS = 1_500L
        private const val KIND_PLAYER_TAP = SiteHabits.KIND_PLAYER
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
        /** When only copies found inside other requests are known: how long to wait for the player's own request. */
        private const val EMBEDDED_WAIT_MS = 8_000L
        private const val FIRST_NUDGE_MS = 1_500L
        /** Empty player box (primary search): how long after the page loads before the first source button is pressed, and between presses. */
        private const val SOURCE_FIRST_MS = 5_000L
        private const val SOURCE_RETRY_MS = 10_000L
        private const val MAX_SOURCE_PRESSES = 2
        /** Taps on a found player, at most (one per nudge, 2.5 s apart). */
        private const val MAX_PLAYER_TAPS = 6
        private const val MAX_CANDIDATES = 12
        /** Possible playlists (disguised addresses) looked at per run. */
        private const val MAX_POSSIBLES = 4
        /** Separate budget for possibles found by repeating (see [RepeatWatch]). */
        private const val MAX_REPEAT_POSSIBLES = 6

        /** Page sources: first read of the script's answer, and the hard stop for the whole step. */
        private const val PAGE_SOURCES_READ_MS = 300L
        private const val PAGE_SOURCES_READS = 8
        private const val PAGE_SOURCES_MAX_MS = 3_000L
        /** Time for one light run ([sniff] with light = true). */
        const val LIGHT_TIMEOUT_MS = 25_000L

        fun isStreamUrl(url: String): Boolean = StreamUrls.classify(url).first

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
              // The player: the biggest video or frame on screen. A frame that is lazy or only partly on
              // screen counts too (the page is scrolled to show it); with no frame yet, a player box
              // (e.g. div#videoPlayer, .player-container) stands in so the tap still lands on it.
              var vis=function(e){
                var cs=getComputedStyle(e);
                if(cs.visibility==='hidden'||cs.display==='none')return false;
                for(var pn=e;pn&&pn!==document.body;pn=pn.parentElement){var pp=getComputedStyle(pn).position;if(pp==='fixed'||pp==='sticky')return false;}
                return true;
              };
              var pick=function(sel,minA,landscape){
                var b=null,ba=0;
                document.querySelectorAll(sel).forEach(function(e){
                  if(!vis(e))return;
                  var r=e.getBoundingClientRect(), a=r.width*r.height;
                  if(landscape&&r.width/r.height<1.1)return; // a tall narrow panel (chat) is not the player
                  if(r.width>=200&&r.height>=120&&a>=minA&&a>ba){ba=a;b=e;}
                });
                return b;
              };
              var kind='video', el=pick('video',40000);
              if(!el){ el=pick('iframe',40000,true); kind='frame'; }
              if(!el){
                kind='box';
                ['#videoPlayer','#player','.iframe-placeholder','.player-container','.video-player','[class*="player-wrap" i]','[id*="player" i]']
                .some(function(sel){ var e=pick(sel,60000,true); if(e&&e.getBoundingClientRect().height<=innerHeight*1.2){el=e;return true;} return false; });
              }
              var res={v:out, iw:innerWidth};
              if(el){
                var r=el.getBoundingClientRect();
                var L=Math.max(0,r.left),T=Math.max(0,r.top),R=Math.min(innerWidth,r.right),B=Math.min(innerHeight,r.bottom);
                var shown=(R>L&&B>T)?(R-L)*(B-T):0;
                if(shown<0.6*r.width*r.height){
                  try{el.scrollIntoView({block:'center',inline:'center'});}catch(x){}
                  r=el.getBoundingClientRect();
                  L=Math.max(0,r.left);T=Math.max(0,r.top);R=Math.min(innerWidth,r.right);B=Math.min(innerHeight,r.bottom);
                }
                if(R>L&&B>T){
                  res.px=(L+R)/2; res.py=(T+B)/2; res.kind=kind;
                  if(kind==='frame'){ try{ res.fh=new URL(el.src||el.getAttribute('data-src')||'',location.href).hostname; }catch(x){ res.fh=''; } }
                }
              }
              return JSON.stringify(res);
            })()
        """
    }
}
