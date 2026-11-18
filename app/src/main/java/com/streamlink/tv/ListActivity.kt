package com.streamlink.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * One stream list: shows the cached events at once, reads the index page again, and re-reads every
 * minute while open. OK on an event plays it (and waits for it if it has not started yet).
 */
class ListActivity : Activity() {

    private lateinit var list: SavedList
    private lateinit var reader: IndexReader
    private lateinit var prefetcher: Prefetcher
    private lateinit var rows: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var subtitle: TextView
    private lateinit var errorView: TextView
    private lateinit var refreshBtn: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var pBadgeHolder: FrameLayout
    private lateinit var pTitle: TextView
    private lateinit var pMeta: TextView
    private lateinit var pScore: LinearLayout
    private lateinit var playBtn: TextView
    private lateinit var siteBtn: TextView
    private var selected: ListItem? = null
    private var selectedUrl: String? = null
    private val main = Handler(Looper.getMainLooper())
    private var cached: CachedList? = null
    private var reading = false
    private var readStartedAt = 0L
    private var nextReadAt = 0L
    private var beats = 0
    private var errorText: String? = null
    /** Score line of every row on screen, so a score update changes text in place (no rebuild, focus untouched). */
    private val scoreViews = ArrayList<Pair<ListItem, TextView>>()

    private val handler: (String, String?) -> Unit = { c, v ->
        when (c) {
            "stop" -> finish()
            "refresh" -> if (v == null || v == list.id) refresh()
        }
    }

    private val beat = object : Runnable {
        override fun run() {
            assertSession()
            beats++
            val now = System.currentTimeMillis()
            if (reading && now - readStartedAt > READ_WATCHDOG_MS) {
                // The reader never answered; drop it and try again at the next slot.
                reader.cancel()
                finishRead(ListResult(list.url, "", emptyList(), "automatic", "the page took too long to answer"))
            }
            if (!reading && now >= nextReadAt) refresh()
            ScoreFeed.refreshIfDue { if (!isFinishing) updateScores() }
            if (beats % RERENDER_BEATS == 0) render() // keep badges, minutes and "updated ... ago" current without a read
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)
        val l = id?.let { SavedLists.get(this, it) }
        if (l == null) { finish(); return }
        list = l
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        cached = SavedLists.cached(this, list.id)
        errorText = SavedLists.lastError(list.id)
        render()
    }

    override fun onStart() {
        super.onStart()
        openId = list.id
        assertSession()
    }

    override fun onResume() {
        super.onResume()
        // The 60 s re-read and 30 s re-render only run while this screen is in front.
        main.removeCallbacks(beat)
        main.post(beat)
        prefetcher.setActive(true)
        // Back from playing with an event still focused: no focus event comes, so start the 2 s again.
        val f = currentFocus
        val sel = selected
        if (f != null && f.parent === rows && sel != null && sel.url == f.tag) prefetcher.focus(sel)
    }

    override fun onPause() {
        prefetcher.setActive(false)
        main.removeCallbacks(beat)
        super.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            nextReadAt = 0
            refresh()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        if (openId == list.id) openId = null
        main.removeCallbacks(beat)
        reader.cancel()
        if (reading) { reading = false; SavedLists.setBusy(list.id, false) }
        releaseSession()
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        prefetcher.cancel()
        reader.cancel()
        SavedLists.setBusy(list.id, false)
        releaseSession()
        super.onDestroy()
    }

    // ---- Session (phone status) ----

    private fun assertSession() {
        if (Session.commandHandler !== handler || Session.mode != "list") {
            Session.commandHandler = handler
            Session.mode = "list"
            Session.title = list.name
            Session.snapshot = null
            AppStatus.text = "Stream list: ${list.name}"
        }
    }

    private fun releaseSession() {
        if (Session.commandHandler === handler) {
            Session.commandHandler = null
            Session.mode = "idle"
            AppStatus.text = "Waiting for a link"
        }
    }

    // ---- Reading ----

    private fun refresh() {
        if (reading) return
        reading = true
        readStartedAt = System.currentTimeMillis()
        SavedLists.setBusy(list.id, true)
        updateHeader()
        reader.read(list.url) { result -> finishRead(result) }
    }

    private fun finishRead(result: ListResult) {
        reading = false
        SavedLists.setBusy(list.id, false)
        val now = System.currentTimeMillis()
        nextReadAt = now + REREAD_MS
        errorText = SavedLists.applyResult(this, list, result, now)
        cached = SavedLists.cached(this, list.id)
        render()
    }

    // ---- UI ----

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Ui.BG)
        // Hidden page loader for the reader, behind the content.
        val holder = FrameLayout(this)
        root.addView(holder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        reader = IndexReader(this, holder)
        // Second hidden loader for "Head start" (the focused event's stream, looked up in advance).
        val prefetchHolder = FrameLayout(this)
        root.addView(prefetchHolder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        prefetcher = Prefetcher(this, prefetchHolder, "list")

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(42), dp(30), dp(42), dp(22))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text("Your lists", 12f, Ui.TEXT_3))
        titles.addView(text(list.name, 27f, Ui.TEXT, bold = true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0)
        })
        top.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Right-aligned note; focusable so OK also refreshes now (Menu does the same).
        refreshBtn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            background = Ui.focusable(this@ListActivity, 0, Ui.SURFACE_FOCUS, 10f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isFocusable = true; isClickable = true
            setOnClickListener { nextReadAt = 0; refresh() }
        }
        refreshBtn.addView(text("↻  Refreshes by itself every minute", 11f, Ui.TEXT_2).apply { gravity = Gravity.END })
        subtitle = text("", 11f, Ui.TEXT_3).apply { gravity = Gravity.END; setPadding(0, dp(2), 0, 0) }
        refreshBtn.addView(subtitle)
        top.addView(refreshBtn)
        content.addView(top)
        errorView = text("", 12f, Ui.LIVE_TEXT).apply { setPadding(0, dp(8), 0, 0); visibility = View.GONE }
        content.addView(errorView)

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; isSmoothScrollingEnabled = true
            clipToPadding = false
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(rows, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        panel = buildPanel()
        body.addView(panel, LinearLayout.LayoutParams(dp(248), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginStart = dp(20) })
        content.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(14) })
        content.addView(text("OK  Play   ·   Menu  Refresh now   ·   “Maybe ended” events are at the bottom", 11f, Ui.TEXT_3).apply { setPadding(0, dp(10), 0, 0) })
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    private fun buildPanel(): LinearLayout {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(this@ListActivity, Ui.SURFACE, 12f)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            visibility = View.GONE
        }
        pBadgeHolder = FrameLayout(this)
        p.addView(pBadgeHolder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        pTitle = text("", 17f, Ui.TEXT, bold = true).apply { maxLines = 4; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(10), 0, 0) }
        p.addView(pTitle)
        pMeta = text("", 12f, Ui.TEXT_2).apply { maxLines = 4; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(10), 0, 0); setLineSpacing(0f, 1.2f) }
        p.addView(pMeta)
        pScore = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, 0); visibility = View.GONE }
        p.addView(pScore)
        p.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        playBtn = text("Play", 13f, Ui.ON_ACCENT, bold = true).apply {
            gravity = Gravity.CENTER
            background = Ui.primaryButton(this@ListActivity)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            isFocusable = true; isClickable = true
            setOnClickListener { selected?.let { play(it) } }
        }
        p.addView(playBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        siteBtn = text("Open the site’s own player", 13f, Ui.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            background = Ui.focusable(this@ListActivity, Ui.CHIP, Ui.SURFACE_FOCUS, 8f)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            isFocusable = true; isClickable = true
            setOnClickListener { selected?.let { openSitePlayer(it) } }
        }
        p.addView(siteBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        return p
    }

    private fun updateHeader() {
        val c = cached
        val now = System.currentTimeMillis()
        val ago = if (c == null) "not read yet" else "updated ${ListText.ago(c.readAt, now)}"
        subtitle.text = if (reading) "Refreshing…  ($ago)" else ago.replaceFirstChar { it.uppercase() }
        val e = errorText
        errorView.visibility = if (e == null) View.GONE else View.VISIBLE
        errorView.text = e ?: ""
    }

    private fun render() {
        updateHeader()
        val now = System.currentTimeMillis()
        // Keep the highlighted event highlighted across rebuilds (matched by link).
        val focus = currentFocus
        val hadFocusInRows = focus?.parent === rows
        val focusedUrl = if (hadFocusInRows) focus?.tag as? String else null
        val items = cached?.let { ListJson.visible(it.result.items, now) } ?: emptyList()
        rows.removeAllViews()
        scoreViews.clear()
        if (items.isEmpty()) {
            val msg = when {
                reading && cached == null -> "Reading events…"
                cached == null -> "No events yet."
                else -> "No upcoming events found on this page."
            }
            rows.addView(text(msg, 15f, Ui.TEXT_2).apply { setPadding(0, dp(10), 0, 0) })
            selected = null
            panel.visibility = View.GONE
            if (!refreshBtn.hasFocus()) refreshBtn.requestFocus()
            return
        }
        var focusTarget: View? = null
        var firstRow: View? = null
        var first = true
        for (section in ListSections.group(items, now)) {
            val color = if (section.status == ListStatus.LIVE) Ui.LIVE_TEXT else Ui.TEXT_2
            rows.addView(Ui.sectionLabel(this, section.label, color), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = if (first) 0 else dp(8); bottomMargin = dp(6)
            })
            first = false
            for (item in section.items) {
                val row = row(item, now)
                rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(7) })
                if (firstRow == null) firstRow = row
                if (focusedUrl != null && item.url == focusedUrl && focusTarget == null) focusTarget = row
            }
        }
        // Details panel: keep showing the same event (by link) if it is still there.
        val keep = items.firstOrNull { it.url == (focusedUrl ?: selectedUrl) } ?: items.first()
        showDetails(keep, now)
        when {
            focusTarget != null -> focusTarget.requestFocus()
            hadFocusInRows || currentFocus == null -> firstRow?.requestFocus()
        }
    }

    private fun showDetails(item: ListItem, now: Long) {
        selected = item
        selectedUrl = item.url
        panel.visibility = View.VISIBLE
        val st = ListStatus.of(item, now)
        val b = ListSections.badge(item, now)
        val label = when (st) {
            ListStatus.LIVE, ListStatus.SOON -> b.text
            ListStatus.UPCOMING -> item.startMillis?.let { ListText.whenText(it, now, java.util.TimeZone.getDefault(), java.util.Locale.getDefault()) } ?: b.text
            else -> ListSections.label(st)
        }
        pBadgeHolder.removeAllViews()
        pBadgeHolder.addView(Ui.badge(this, label, st == ListStatus.LIVE))
        pTitle.text = ListText.displayTitle(item)
        pMeta.text = ListSections.meta(item, now).replaceFirstChar { it.uppercase() }
        pMeta.visibility = if (pMeta.text.isEmpty()) View.GONE else View.VISIBLE
        fillScoreBlock(item)
    }

    private fun gameFor(item: ListItem): Game? =
        try { ScoreMatch.find(ListText.displayTitle(item), item.startMillis, ScoreFeed.latest().filter { list.sport.isEmpty() || it.league == list.sport }) } catch (_: Exception) { null }

    private fun fillScoreBlock(item: ListItem) {
        pScore.removeAllViews()
        val lines = gameFor(item)?.let { ScoreText.cardLines(it) } ?: emptyList()
        if (lines.isEmpty()) { pScore.visibility = View.GONE; return }
        lines.forEachIndexed { i, l ->
            pScore.addView(if (i == 0) text(l, 15f, Ui.TEXT, bold = true) else text(l, 12f, Ui.TEXT_2).apply { setPadding(0, dp(2), 0, 0) })
        }
        pScore.visibility = View.VISIBLE
    }

    private fun applyScore(item: ListItem, v: TextView) {
        val g = gameFor(item)
        val line = g?.let { ScoreText.rowLine(it) }
        if (g == null || line == null) { v.visibility = View.GONE; return }
        v.text = line
        v.setTextColor(if (g.state == "in") Ui.LIVE_TEXT else Ui.TEXT_2)
        v.visibility = View.VISIBLE
    }

    /** New scores arrived: change the score texts in place, leaving rows and focus alone. */
    private fun updateScores() {
        for ((item, v) in scoreViews) applyScore(item, v)
        selected?.let { fillScoreBlock(it) }
    }

    private fun row(item: ListItem, now: Long): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.focusable(this@ListActivity)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isFocusable = true; isClickable = true
            tag = item.url
            setOnClickListener { play(item) }
            setOnFocusChangeListener { _, has ->
                if (has) {
                    showDetails(item, System.currentTimeMillis())
                    prefetcher.focus(item)
                } else {
                    prefetcher.blur()
                }
            }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(ListText.displayTitle(item), 16f, Ui.TEXT, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        val meta = ListSections.meta(item, now)
        if (meta.isNotEmpty()) col.addView(text(meta, 11f, Ui.TEXT_2).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0) })
        val scoreLine = text("", 11f, Ui.TEXT_2, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0); visibility = View.GONE }
        col.addView(scoreLine)
        applyScore(item, scoreLine)
        scoreViews.add(item to scoreLine)
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(14) })
        val b = ListSections.badge(item, now)
        val right: View = if (b.pill) Ui.badge(this, b.text, b.live) else text(b.text, 11f, Ui.TEXT_2, bold = true)
        row.addView(right)
        return row
    }

    private fun openSitePlayer(item: ListItem) {
        val u = Uri.parse(item.url)
        if (u.scheme == "http" || u.scheme == "https") startActivity(SitePlayerActivity.intent(this, item.url))
    }

    private fun play(item: ListItem) {
        startActivity(WaitingActivity.intent(this, item, list.id, list.name))
    }

    // plain text, never HTML: titles come from untrusted pages
    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false): TextView = Ui.text(this, s, sp, color, bold)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_ID = "id"
        /** The list whose screen is in front right now, so the phone's Refresh goes through it. */
        @Volatile var openId: String? = null
        const val REREAD_MS = 60_000L
        /** Statuses are re-rendered every 30 beats (30 s) even without a read. */
        private const val RERENDER_BEATS = 30
        private const val READ_WATCHDOG_MS = 90_000L
        fun intent(ctx: Context, id: String) = Intent(ctx, ListActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
