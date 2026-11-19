package com.streamlink.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Plays an event from a stream list. Looks for its stream right away; if there is none yet and the event
 * has not started (or only just), it keeps checking every 45 seconds and starts the player by itself
 * as soon as a stream appears. Gives up 3 hours after the start time. Back (or Stop on the phone) ends it.
 */
class WaitingActivity : Activity() {

    private lateinit var sniffer: StreamSniffer
    private lateinit var item: ListItem
    private lateinit var titleView: TextView
    private lateinit var whenView: TextView
    private lateinit var checkView: TextView
    private lateinit var infoView: TextView
    private lateinit var checkNowBtn: TextView
    private lateinit var listNameView: TextView
    private lateinit var stepsRow: LinearLayout
    private lateinit var stepDots: List<TextView>
    private lateinit var stepLabels: List<TextView>
    private lateinit var stepLines: List<View>
    private lateinit var usually: TextView
    private lateinit var waitBox: LinearLayout
    private lateinit var detailsBtn: TextView
    private var showDetails = false
    /** Opened from the Source list for a link player: no waiting, no new report, the original page is the referer. */
    private var linkPage = false
    private var referer: String? = null
    /** Started by the player's automatic switch (see [AutoSwitch]): no stream here means try the next candidate. */
    private var auto = false
    private val main = Handler(Looper.getMainLooper())

    private var checking = false
    private var checkStartedAt = 0L
    private var checkCount = 0
    private var lastCheckAt = 0L
    private var lastResult = ""
    private var nextCheckAt = 0L
    private var waiting = false          // the first check found nothing and we decided to keep waiting
    private var waitStartedAt = 0L
    private var gaveUp = false
    private var handedOff = false
    private var keepForLate = false
    private var loggedResult = ""
    private var lastLoggedAt = 0L

    private val handler: (String, String?) -> Unit = { c, _ -> if (c == "stop") finish() }

    private val beat = object : Runnable {
        override fun run() {
            if (handedOff || isFinishing) return
            val now = System.currentTimeMillis()
            if (checking && now - checkStartedAt > CHECK_WATCHDOG_MS) {
                sniffer.cancel()
                onNoStream(now, "the page took too long to answer")
            }
            if (waiting && !gaveUp && ListText.gaveUp(item.startMillis, waitStartedAt, now)) giveUp()
            if (waiting && !gaveUp && !checking && now >= nextCheckAt) check()
            update(now)
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        val start = i.getLongExtra(EXTRA_START, NO_TIME).takeIf { it != NO_TIME }
        item = ListItem(
            title = i.getStringExtra(EXTRA_TITLE) ?: "", subtitle = i.getStringExtra(EXTRA_SUBTITLE) ?: "",
            url = i.getStringExtra(EXTRA_URL) ?: "", startMillis = start, timeText = "",
            section = "", liveHint = i.getBooleanExtra(EXTRA_LIVE, false), imageUrl = null,
        )
        if (item.url.isEmpty()) { finish(); return }
        linkPage = i.getBooleanExtra(EXTRA_LINK_PAGE, false)
        referer = i.getStringExtra(EXTRA_REFERER)
        auto = linkPage && i.getBooleanExtra(EXTRA_AUTO, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        if (linkPage) {
            Report.log("Opening ${ListText.displayTitle(item)} as its own page (${Report.host(item.url)})")
        } else {
            AutoSwitch.reset() // the user started something: a new watch
            Report.start(item.url)
            Report.log("Playing an event from a list: ${ListText.displayTitle(item)}")
            Report.log("Start: " + (start?.let { ListText.whenText(it, System.currentTimeMillis(), java.util.TimeZone.getDefault(), java.util.Locale.getDefault()) } ?: "unknown"))
        }
        waitStartedAt = System.currentTimeMillis()
    }

    override fun onStart() {
        super.onStart()
        if (!::item.isInitialized || item.url.isEmpty() || handedOff) return
        assertSession()
        main.removeCallbacks(beat)
        main.post(beat)
        if (!checking && !waiting && !gaveUp) check() // very first check, or back from another screen
    }

    override fun onStop() {
        main.removeCallbacks(beat)
        if (::sniffer.isInitialized && !keepForLate) sniffer.cancel()
        checking = false
        releaseSession()
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (::sniffer.isInitialized) sniffer.cancel()
        releaseSession()
        super.onDestroy()
    }

    // ---- Checking ----

    private fun check() {
        if (checking || handedOff || gaveUp) return
        if (!linkPage) Prefetch.take(item.url)?.let { head ->
            checkCount++
            Timing.path = Timing.PATH_HEAD_START
            Report.log("Head start: stream already found while you were browsing")
            found(head)
            return
        }
        // A ready backup (the player found it while watching) skips the search.
        if (linkPage) PreFound.take(item.url)?.let { ready ->
            checkCount++
            found(ready)
            return
        }
        checking = true
        checkStartedAt = System.currentTimeMillis()
        checkCount++
        update(checkStartedAt)
        sniffer.sniff(item.url, referer = referer, lateExtras = true) { set ->
            if (!checking || handedOff) return@sniff
            checking = false
            val now = System.currentTimeMillis()
            lastCheckAt = now
            if (set.sources.isNotEmpty()) found(set)
            else onNoStream(now, if (set.offline) "the site's stream is offline" else "no stream yet", set.pageUrl, set.offline)
        }
    }

    private fun found(set: StreamSet) {
        handedOff = true
        main.removeCallbacksAndMessages(null)
        Report.log("Check #$checkCount: stream found, starting the player")
        Session.current = set
        AppStatus.text = "Playing ${ListText.displayTitle(item)}"
        if (auto) android.widget.Toast.makeText(applicationContext, "Switched to ${item.title}", android.widget.Toast.LENGTH_LONG).show()
        startActivity(PlayerActivity.intent(this))
        // The hidden page is still reading the page's numbered addresses: keep this screen (and the page) until that ends.
        if (sniffer.lateReading) { keepForLate = true; sniffer.onLateDone = { finish() } } else finish()
    }

    private fun onNoStream(now: Long, why: String, pageUrl: String = item.url, offline: Boolean = false) {
        checking = false
        lastCheckAt = now
        lastResult = why
        logCheck(why)
        // No start time (often an always-on channel, not a scheduled event): one quick retry, then the
        // site's own player instead of re-checking every 45 s for hours.
        // A link's own page never waits: no stream on the first try goes straight to the site player.
        val noTimeGiveUp = linkPage || (item.startMillis == null && checkCount >= NO_TIME_TRIES)
        if (!waiting || noTimeGiveUp) {
            if (!noTimeGiveUp && ListText.shouldWait(item.startMillis, now)) {
                waiting = true
                waitStartedAt = now
            } else {
                if (auto && tryNextAuto()) return
                if (offline && tryOfflineAlternatives(pageUrl)) return
                // Started a while ago (or no time and still nothing): same as a link sent directly.
                if (noTimeGiveUp && !linkPage) Report.log("No start time and no stream after $checkCount checks: opening the site's own player")
                openSite(pageUrl)
                return
            }
        }
        nextCheckAt = now + if (item.startMillis == null && checkCount < NO_TIME_TRIES) NO_TIME_RETRY_MS else CHECK_EVERY_MS
        update(now)
    }

    private fun openSite(pageUrl: String) {
        handedOff = true
        main.removeCallbacksAndMessages(null)
        Timing.emit(Timing.PATH_SITE_PLAYER)
        AppStatus.text = "No direct stream on ${Report.host(item.url)}, showing the site's own player"
        startActivity(SitePlayerActivity.intent(this, pageUrl, referer))
        finish()
    }

    /**
     * The page's stream is offline (every check said 404/410): try the other streams of the same game (other sites, from the
     * sports folders) through the automatic switch; false when no switch is left (the caller opens the site player). When it
     * returns true this screen is handed off and ends in a new page or, with no alternative found, the site player.
     */
    private fun tryOfflineAlternatives(pageUrl: String): Boolean {
        AutoSwitch.markDead(item.url, System.currentTimeMillis())
        if (!AutoSwitch.canSwitch()) return false
        handedOff = true
        main.removeCallbacksAndMessages(null)
        if (::usually.isInitialized) { usually.text = "The site's stream is offline. Trying other sources…"; usually.visibility = View.VISIBLE }
        val app = applicationContext
        kotlin.concurrent.thread(isDaemon = true, name = "offline-alternatives") {
            val alts = try { Alternatives.forPage(app, pageUrl, item.url) } catch (_: Exception) { emptyList() }
            main.post {
                if (isFinishing || isDestroyed) return@post
                val now = System.currentTimeMillis()
                val live = alts.filter { !AutoSwitch.isDead(it.url, now) }
                val first = live.firstOrNull()
                if (first == null || !AutoSwitch.canSwitch()) { openSite(pageUrl); return@post }
                AutoSwitch.begin(live.drop(1))
                Report.log("Stream offline; switching to ${first.label} (${Report.host(first.url)})")
                startActivity(linkPageIntent(this, first.playerUrl.ifEmpty { first.url }, first.label, null, auto = true))
                finish()
            }
        }
        return true
    }

    /** An automatic page had no stream: continue with the next remembered candidate (this screen is replaced). */
    private fun tryNextAuto(): Boolean {
        val now = System.currentTimeMillis()
        AutoSwitch.markDead(item.url, now)
        val next = AutoSwitch.takeNext(now) ?: return false
        val target = next.playerUrl.ifEmpty { next.url }
        Report.log("No stream there; switching to ${next.label} (${Report.host(target)})")
        handedOff = true
        main.removeCallbacksAndMessages(null)
        startActivity(linkPageIntent(this, target, next.label, next.referers.firstOrNull { it.isNotBlank() }, auto = true))
        finish()
        return true
    }

    /** Rate limited: first check, a change of result, or at most one line every 5 minutes. */
    private fun logCheck(why: String) {
        val now = System.currentTimeMillis()
        if (checkCount == 1 || why != loggedResult || now - lastLoggedAt >= 5 * ListText.MIN) {
            Report.log("Check #$checkCount: $why")
            loggedResult = why
            lastLoggedAt = now
        }
    }

    private fun giveUp() {
        gaveUp = true
        waiting = false
        sniffer.cancel()
        checking = false
        Report.log("Gave up waiting after $checkCount checks")
        update(System.currentTimeMillis())
    }

    // ---- Session (phone status) ----

    private fun statusText(now: Long): String {
        val t = ListText.displayTitle(item)
        return when {
            gaveUp -> "Gave up waiting for “$t”: no stream appeared within 3 hours of the start."
            checking && !waiting -> "Finding the video for “$t”…"
            checking -> "Waiting for “$t” (${ListText.countdown(item.startMillis, now)}), checking now…"
            else -> "Waiting for “$t” (${ListText.countdown(item.startMillis, now)}). Next check in " +
                ListText.duration((nextCheckAt - now).coerceAtLeast(0))
        }
    }

    private fun assertSession() {
        if (gaveUp) {
            if (Session.commandHandler !== handler) Session.commandHandler = handler
            if (Session.mode != "idle") Session.mode = "idle"
            AppStatus.text = statusText(System.currentTimeMillis())
            return
        }
        if (Session.commandHandler !== handler || Session.mode != "waiting") {
            Session.commandHandler = handler
            Session.mode = "waiting"
            Session.title = ListText.displayTitle(item)
            Session.snapshot = null
            Session.listId = intent.getStringExtra("listId")
            Session.listName = intent.getStringExtra("listName") ?: ""
            Session.listItemUrl = if (linkPage) null else item.url
        }
        AppStatus.text = statusText(System.currentTimeMillis())
    }

    private fun releaseSession() {
        if (Session.commandHandler === handler) {
            Session.commandHandler = null
            Session.mode = "idle"
            AppStatus.text = "Waiting for a link"
        }
    }

    // ---- UI ----

    private fun update(now: Long) {
        if (!::titleView.isInitialized) return
        val zone = java.util.TimeZone.getDefault()
        val locale = java.util.Locale.getDefault()
        titleView.text = ListText.displayTitle(item)
        val st = item.startMillis
        val waitMode = waiting || gaveUp
        // Calm waiting state replaces the steps while the event has not started / nothing is there yet.
        stepsRow.visibility = if (waitMode) View.GONE else View.VISIBLE
        usually.visibility = if (waitMode) View.GONE else View.VISIBLE
        waitBox.visibility = if (waitMode) View.VISIBLE else View.GONE
        if (!waitMode) {
            val states = WaitingText.steps(if (checking) sniffer.stage else 0, handedOff)
            for (i in 0..2) styleStep(i, states[i])
            for (i in 0..1) stepLines[i].setBackgroundColor(if (states[i] == WaitingText.DONE) Ui.ACCENT else Ui.NEUTRAL_BADGE)
        }
        whenView.text = if (gaveUp) "Gave up waiting" else WaitingText.headline(st, now, zone, locale)
        nextView.text = when {
            gaveUp -> "No stream appeared within 3 hours of the start. Press Back to return."
            checking -> "Checking now…"
            lastCheckAt == 0L -> "Looking for the video…"
            else -> "Next check in ${ListText.duration((nextCheckAt - now).coerceAtLeast(0))}"
        }
        everyView.text = if (gaveUp) "" else "Checking every ${CHECK_EVERY_MS / 1000} s"
        everyView.visibility = if (gaveUp) View.GONE else View.VISIBLE
        // Details: the full status text that used to be always on screen.
        val whenFull = if (st == null) "No start time on the page"
        else ListText.whenText(st, now, zone, locale) + "  ·  " + ListText.countdown(st, now)
        val check = when {
            gaveUp -> "Gave up: no stream appeared within 3 hours of the start. Press Back to return."
            checking -> if (waiting) "Checking now…" else "Looking for the video…"
            lastCheckAt == 0L -> "Looking for the video…"
            else -> "Last check ${ListText.duration(now - lastCheckAt)} ago: $lastResult. Next in ${ListText.duration((nextCheckAt - now).coerceAtLeast(0))}."
        }
        checkView.text = whenFull + "\n" + check
        infoView.text = if (gaveUp) "" else "Checking every 45 seconds. The player starts by itself when the stream appears. Back stops waiting."
        checkView.visibility = if (showDetails) View.VISIBLE else View.GONE
        infoView.visibility = if (showDetails && !gaveUp) View.VISIBLE else View.GONE
        detailsBtn.text = if (showDetails) "Hide details" else "Show details"
        checkNowBtn.visibility = if (gaveUp) View.GONE else View.VISIBLE
        if (!handedOff) assertSession()
    }

    private fun styleStep(i: Int, state: Int) {
        val dot = stepDots[i]
        when (state) {
            WaitingText.DONE -> { dot.background = Ui.rounded(this, Ui.ACCENT, 13f); dot.text = "✓" }
            WaitingText.ACTIVE -> { dot.background = Ui.rounded(this, Ui.BG, 13f, Ui.ACCENT, 2f); dot.text = "" }
            else -> { dot.background = Ui.rounded(this, Ui.BG, 13f, Ui.NEUTRAL_BADGE, 2f); dot.text = "" }
        }
        stepLabels[i].setTextColor(if (state == WaitingText.TODO) Ui.TEXT_2 else Ui.TEXT)
        stepLabels[i].typeface = Typeface.create(Typeface.DEFAULT, if (state == WaitingText.ACTIVE) Typeface.BOLD else Typeface.NORMAL)
    }

    private lateinit var nextView: TextView
    private lateinit var everyView: TextView

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Ui.BG)
        val holder = FrameLayout(this)
        root.addView(holder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        sniffer = StreamSniffer(this, holder)

        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Ui.BG)
            setPadding(dp(60), dp(30), dp(60), dp(30))
        }
        val listName = intent.getStringExtra("listName") ?: ""
        listNameView = text(listName, 12f, Ui.TEXT_2).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; visibility = if (listName.isBlank()) View.GONE else View.VISIBLE }
        col.addView(listNameView)
        titleView = text("", 30f, Ui.TEXT, true).apply { gravity = Gravity.CENTER; maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, dp(4)) }
        col.addView(titleView)
        if (item.subtitle.isNotBlank()) col.addView(text(item.subtitle.trim(), 14f, Ui.TEXT_2).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END })

        // Looking: 3 steps.
        stepsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val names = listOf("Opening page", "Finding the stream", "Starting video")
        val dots = mutableListOf<TextView>(); val labels = mutableListOf<TextView>(); val lines = mutableListOf<View>()
        for (i in 0..2) {
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val dot = text("", 13f, Ui.ON_ACCENT, true).apply { gravity = Gravity.CENTER }
            cell.addView(dot, LinearLayout.LayoutParams(dp(26), dp(26)))
            val label = text(names[i], 12f, Ui.TEXT).apply { gravity = Gravity.CENTER; setPadding(0, dp(7), 0, 0) }
            cell.addView(label)
            stepsRow.addView(cell, LinearLayout.LayoutParams(dp(128), wrap))
            dots += dot; labels += label
            if (i < 2) {
                val line = View(this)
                stepsRow.addView(line, LinearLayout.LayoutParams(dp(52), dp(2)).apply { bottomMargin = dp(22) })
                lines += line
            }
        }
        stepDots = dots; stepLabels = labels; stepLines = lines
        col.addView(stepsRow, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(26) })
        usually = text("Usually ready in under 10 seconds", 12f, Ui.TEXT_3).apply { gravity = Gravity.CENTER; setPadding(0, dp(20), 0, 0) }
        col.addView(usually)

        // Not started yet: calm waiting state.
        waitBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; visibility = View.GONE }
        whenView = text("", 32f, Ui.TEXT, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(22), 0, dp(6)) }
        waitBox.addView(whenView)
        everyView = text("", 14f, Ui.TEXT_2).apply { gravity = Gravity.CENTER }
        waitBox.addView(everyView)
        nextView = text("", 13f, Ui.TEXT_3).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) }
        waitBox.addView(nextView)
        col.addView(waitBox)

        // Details (hidden until "Show details").
        checkView = text("", 14f, Ui.TEXT_2).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0); visibility = View.GONE }
        col.addView(checkView)
        infoView = text("", 12f, Ui.TEXT_3).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0); visibility = View.GONE }
        col.addView(infoView)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val cancel = button("Cancel") { finish() }
        buttons.addView(cancel, LinearLayout.LayoutParams(wrap, wrap).apply { marginEnd = dp(10) })
        detailsBtn = button("Show details") { showDetails = !showDetails; update(System.currentTimeMillis()) }
        buttons.addView(detailsBtn, LinearLayout.LayoutParams(wrap, wrap).apply { marginEnd = dp(10) })
        checkNowBtn = button("Check now") { if (waiting && !checking && !gaveUp) check() }
        buttons.addView(checkNowBtn, LinearLayout.LayoutParams(wrap, wrap).apply { marginEnd = dp(10) })
        val site = button("Open the page's own player") {
            val u = Uri.parse(item.url)
            if (u.scheme == "http" || u.scheme == "https") startActivity(SitePlayerActivity.intent(this, item.url, referer))
        }
        buttons.addView(site)
        col.addView(buttons, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(28) })
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        // Not "Cancel": an impatient second OK press must not stop what was just started.
        detailsBtn.requestFocus()
    }

    private fun button(label: String, onClick: () -> Unit) = text(label, 13f, Ui.TEXT, true).apply {
        setPadding(dp(14), dp(9), dp(14), dp(9))
        Ui.focusCard(this, Ui.CHIP, Ui.SURFACE_FOCUS, 8f)
        isFocusable = true; isClickable = true
        setOnClickListener { onClick() }
    }

    // plain text only: titles come from untrusted pages
    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false): TextView = Ui.text(this, s, sp, color, bold)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val CHECK_EVERY_MS = 45_000L
        /** Entries without a start time: this many checks (the second after [NO_TIME_RETRY_MS]), then the site's own player. */
        const val NO_TIME_TRIES = 2
        const val NO_TIME_RETRY_MS = 15_000L
        private const val CHECK_WATCHDOG_MS = 90_000L
        private const val NO_TIME = Long.MIN_VALUE
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUBTITLE = "subtitle"
        private const val EXTRA_URL = "url"
        private const val EXTRA_START = "start"
        private const val EXTRA_LIVE = "live"
        private const val EXTRA_LINK_PAGE = "linkPage"
        private const val EXTRA_REFERER = "referer"
        private const val EXTRA_AUTO = "auto"

        fun intent(ctx: Context, item: ListItem, listId: String, listName: String) =
            Intent(ctx, WaitingActivity::class.java)
                .putExtra(EXTRA_TITLE, item.title).putExtra(EXTRA_SUBTITLE, item.subtitle)
                .putExtra(EXTRA_URL, item.url).putExtra(EXTRA_START, item.startMillis ?: NO_TIME)
                .putExtra(EXTRA_LIVE, item.liveHint)
                .putExtra("listId", listId).putExtra("listName", listName)

        /**
         * A link's player page, opened from the Source list as a normal page: full stream search sending [referer]
         * (the original page), clean player if a stream verifies, else the site player. Back returns to the stream.
         */
        fun linkPageIntent(ctx: Context, playerUrl: String, label: String, referer: String?, auto: Boolean = false) =
            Intent(ctx, WaitingActivity::class.java)
                .putExtra(EXTRA_TITLE, label).putExtra(EXTRA_SUBTITLE, "")
                .putExtra(EXTRA_URL, playerUrl).putExtra(EXTRA_START, NO_TIME)
                .putExtra(EXTRA_LIVE, true)
                .putExtra(EXTRA_LINK_PAGE, true).putExtra(EXTRA_AUTO, auto).apply { if (!referer.isNullOrBlank()) putExtra(EXTRA_REFERER, referer) }
    }
}
