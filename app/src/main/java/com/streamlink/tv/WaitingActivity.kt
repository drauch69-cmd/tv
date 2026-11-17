package com.streamlink.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        Report.start(item.url)
        Report.log("Playing an event from a list: ${ListText.displayTitle(item)}")
        Report.log("Start: " + (start?.let { ListText.whenText(it, System.currentTimeMillis(), java.util.TimeZone.getDefault(), java.util.Locale.getDefault()) } ?: "unknown"))
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
        if (::sniffer.isInitialized) sniffer.cancel()
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
        checking = true
        checkStartedAt = System.currentTimeMillis()
        checkCount++
        update(checkStartedAt)
        sniffer.sniff(item.url) { set ->
            if (!checking || handedOff) return@sniff
            checking = false
            val now = System.currentTimeMillis()
            lastCheckAt = now
            if (set.sources.isNotEmpty()) found(set) else onNoStream(now, "no stream yet", set.pageUrl)
        }
    }

    private fun found(set: StreamSet) {
        handedOff = true
        main.removeCallbacksAndMessages(null)
        Report.log("Check #$checkCount: stream found, starting the player")
        Session.current = set
        AppStatus.text = "Playing ${ListText.displayTitle(item)}"
        startActivity(PlayerActivity.intent(this))
        finish()
    }

    private fun onNoStream(now: Long, why: String, pageUrl: String = item.url) {
        checking = false
        lastCheckAt = now
        lastResult = why
        logCheck(why)
        if (!waiting) {
            if (ListText.shouldWait(item.startMillis, now)) {
                waiting = true
                waitStartedAt = now
            } else {
                // Started a while ago and still nothing: same as a link sent directly.
                handedOff = true
                main.removeCallbacksAndMessages(null)
                AppStatus.text = "No direct stream on ${Report.host(item.url)}, showing the site's own player"
                startActivity(SitePlayerActivity.intent(this, pageUrl))
                finish()
                return
            }
        }
        nextCheckAt = now + CHECK_EVERY_MS
        update(now)
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
            Session.listItemUrl = item.url
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
        titleView.text = ListText.displayTitle(item)
        val st = item.startMillis
        whenView.text = if (st == null) "No start time on the page"
        else ListText.whenText(st, now, java.util.TimeZone.getDefault(), java.util.Locale.getDefault()) + "  ·  " + ListText.countdown(st, now)
        checkView.text = when {
            gaveUp -> "Gave up: no stream appeared within 3 hours of the start. Press Back to return."
            checking -> if (waiting) "Checking now…" else "Looking for the video…"
            lastCheckAt == 0L -> "Looking for the video…"
            else -> "Last check ${ListText.duration(now - lastCheckAt)} ago: $lastResult. Next in ${ListText.duration((nextCheckAt - now).coerceAtLeast(0))}."
        }
        infoView.text = if (gaveUp) "" else "Checking every 45 seconds. The player starts by itself when the stream appears. Back stops waiting."
        checkNowBtn.visibility = if (gaveUp) android.view.View.GONE else android.view.View.VISIBLE
        if (!handedOff) assertSession()
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF0D1220.toInt())
        val holder = FrameLayout(this)
        root.addView(holder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        sniffer = StreamSniffer(this, holder)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF0D1220.toInt())
            setPadding(dp(80), dp(40), dp(80), dp(40))
        }
        col.addView(text("Waiting for it to start", 18f, 0xFF7F8DAD.toInt()))
        titleView = text("", 38f, 0xFFFFFFFF.toInt(), true).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(8), 0, dp(8)) }
        col.addView(titleView)
        if (item.subtitle.isNotBlank()) col.addView(text(item.subtitle.trim(), 20f, 0xFFB8C4E0.toInt()).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        whenView = text("", 24f, 0xFF8FB4FF.toInt(), true).apply { setPadding(0, dp(20), 0, dp(8)) }
        col.addView(whenView)
        checkView = text("", 20f, 0xFFB8C4E0.toInt())
        col.addView(checkView)
        infoView = text("", 16f, 0xFF7F8DAD.toInt()).apply { setPadding(0, dp(12), 0, dp(24)) }
        col.addView(infoView)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        checkNowBtn = button("Check now") { if (waiting && !checking && !gaveUp) check() }
        buttons.addView(checkNowBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(16) })
        val site = button("Open the page's own player") {
            val u = Uri.parse(item.url)
            if (u.scheme == "http" || u.scheme == "https") startActivity(SitePlayerActivity.intent(this, item.url))
        }
        buttons.addView(site)
        col.addView(buttons)
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        checkNowBtn.requestFocus()
    }

    private fun button(label: String, onClick: () -> Unit) = text(label, 18f, 0xFFFFFFFF.toInt(), true).apply {
        setPadding(dp(22), dp(12), dp(22), dp(12))
        setBackgroundResource(R.drawable.recent_item)
        isFocusable = true; isClickable = true
        setOnClickListener { onClick() }
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s // plain text only: titles come from untrusted pages
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val CHECK_EVERY_MS = 45_000L
        private const val CHECK_WATCHDOG_MS = 90_000L
        private const val NO_TIME = Long.MIN_VALUE
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUBTITLE = "subtitle"
        private const val EXTRA_URL = "url"
        private const val EXTRA_START = "start"
        private const val EXTRA_LIVE = "live"

        fun intent(ctx: Context, item: ListItem, listId: String, listName: String) =
            Intent(ctx, WaitingActivity::class.java)
                .putExtra(EXTRA_TITLE, item.title).putExtra(EXTRA_SUBTITLE, item.subtitle)
                .putExtra(EXTRA_URL, item.url).putExtra(EXTRA_START, item.startMillis ?: NO_TIME)
                .putExtra(EXTRA_LIVE, item.liveHint)
                .putExtra("listId", listId).putExtra("listName", listName)
    }
}
