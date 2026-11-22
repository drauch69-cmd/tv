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
 * One stream list: shows the cached events at once, asks the app's one reader (ListReads) for a fresh read, and
 * re-reads while open on the adaptive timer (ListReadLogic.nextReadDelay: 5 min, 90 s near start times).
 * Rows are rebuilt only when order, sections or statuses change; otherwise their texts are updated in place.
 * OK on an event plays it (and waits for it if it has not started yet).
 */
class ListActivity : Activity() {

    private lateinit var list: SavedList
    private lateinit var readHolder: FrameLayout
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
    private val reading get() = SavedLists.isBusy(list.id)
    private var nextReadAt = 0L
    private var firstRequest = true
    /** The rows' look when they were last built (ListReadLogic.listLook); null = rebuild next time. */
    private var drawnLook: String? = null
    /** Each drawn row's views by link, so the 30 s refresh can change texts in place. */
    private class RowViews(val item: ListItem, val meta: TextView, val right: FrameLayout, val score: TextView) {
        /** What the meta line and badge show now, so the 30 s refresh only touches views whose content changed. */
        var shownMeta: String? = null
        var shownBadge: ListSections.Badge? = null
    }
    private val rowViews = LinkedHashMap<String, RowViews>()
    private var beats = 0
    private var errorText: String? = null
    /** Score line of every row on screen, so a score update changes text in place (no rebuild, focus untouched). */
    private val scoreViews = ArrayList<Pair<ListItem, TextView>>()
    /** Status and finished label (Final...) each row was drawn with, to see whether a score update moved anything. */
    private val rowLook = HashMap<String, String>()
    /** ESPN games of this list's sports (all sports when it has no tag). Status, badge and score line all use it, so they
     *  always agree. Cached per ScoreFeed list instance (ScoreMatch caches matches per instance). */
    private fun games(): List<Game> = ScoreFeed.forLeagues(list.sports)

    private fun look(item: ListItem, now: Long): String = ScoreStatus.resolve(item, now, games()).let { it.status + "|" + (it.badge ?: "") }

    private val handler: (String, String?) -> Unit = { c, v ->
        when (c) {
            "stop" -> finish()
            "refresh" -> if (v == null || v == list.id) refresh(ListReadLogic.Reason.MANUAL)
        }
    }

    /** A read of this list finished (or was queued / started): show it. */
    private val readListener: (String, ListResult?) -> Unit = { id, result ->
        if (id == list.id && !isFinishing && !isDestroyed) {
            if (result != null) {
                errorText = SavedLists.lastError(list.id)
                cached = SavedLists.cached(this, list.id)
                scheduleNext(System.currentTimeMillis())
                render()
            } else {
                updateHeader()
            }
        }
    }

    private val beat = object : Runnable {
        override fun run() {
            assertSession()
            beats++
            val now = System.currentTimeMillis()
            if (!reading && now >= nextReadAt) {
                refresh(if (firstRequest) ListReadLogic.Reason.OPEN else ListReadLogic.Reason.TIMER)
                firstRequest = false
            }
            ScoreFeed.refreshIfDue { if (!isFinishing) updateScores() }
            if (beats % RERENDER_BEATS == 0) render() // badges, minutes, "updated ... ago": in place unless the rows changed
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
        ListReads.addListener(readListener)
        render()
    }

    override fun onStart() {
        super.onStart()
        assertSession()
    }

    override fun onResume() {
        super.onResume()
        // The re-read timer and 30 s refresh only run while this screen is in front; reads run in this screen's holder.
        ListReads.attach(this, readHolder)
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
            refresh(ListReadLogic.Reason.MANUAL)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        main.removeCallbacks(beat)
        releaseSession()
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        prefetcher.cancel()
        if (::list.isInitialized) {
            ListReads.removeListener(readListener)
            ListReads.detach(this)
        }
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

    private fun refresh(reason: ListReadLogic.Reason) {
        val now = System.currentTimeMillis()
        if (!ListReads.request(this, list.id, reason)) {
            // skipped: read a moment ago (by this or another screen); wait for the next slot
            scheduleNext(now)
            nextReadAt = maxOf(nextReadAt, now + 10_000L)
        }
        updateHeader()
    }

    /** The next timed re-read: after the last read or try, by the list's own events (ListReadLogic.nextReadDelay). */
    private fun scheduleNext(now: Long) {
        val items = cached?.result?.items ?: emptyList()
        nextReadAt = ListReads.lastAttempt(this, list.id) + ListReadLogic.nextReadDelay(items, now)
    }

    // ---- UI ----

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Ui.BG)
        // Hidden page loader for the reader, behind the content.
        readHolder = FrameLayout(this)
        root.addView(readHolder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
        // The list's rank, read-only (ranks are set on the phone's Lists tab). Hidden when unranked.
        if (list.rank != null) top.addView(text("Rank ${Ranking.label(list.rank)}", 12f, Ui.ACCENT, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12) })
        // Right-aligned note; focusable so OK also refreshes now (Menu does the same).
        refreshBtn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            Ui.focusCard(this, 0, Ui.SURFACE_FOCUS, 10f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isFocusable = true; isClickable = true
            setOnClickListener { refresh(ListReadLogic.Reason.MANUAL) }
        }
        refreshBtn.addView(text("↻  Refreshes by itself", 11f, Ui.TEXT_2).apply { gravity = Gravity.END })
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
            Ui.primaryCard(this)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            isFocusable = true; isClickable = true
            setOnClickListener { selected?.let { play(it) } }
        }
        p.addView(playBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        siteBtn = text("Open the site’s own player", 13f, Ui.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            Ui.focusCard(this, Ui.CHIP, Ui.SURFACE_FOCUS, 8f)
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

    /**
     * Draws the rows. When order, sections, titles and statuses are the same as last time (ListReadLogic.listLook),
     * only the texts change in place (no rebuild, focus untouched); otherwise the rows are rebuilt, keeping focus by link.
     */
    private fun render() {
        updateHeader()
        val now = System.currentTimeMillis()
        val items = cached?.let { ListJson.visible(it.result.items, now, games()) } ?: emptyList()
        val sections = if (items.isEmpty()) emptyList() else ListSections.group(items, now, games())
        val look = ListReadLogic.listLook(sections.flatMap { sec ->
            sec.items.map { ListReadLogic.RowKey(sec.label, it.url + '\u0002' + ListText.displayTitle(it), look(it, now)) }
        })
        if (items.isNotEmpty() && !ListReadLogic.needsRebuild(drawnLook, look)) {
            refreshInPlace(now)
            return
        }
        // Keep the highlighted event highlighted across rebuilds (matched by link).
        val focus = currentFocus
        val hadFocusInRows = focus?.parent === rows
        val focusedUrl = if (hadFocusInRows) focus?.tag as? String else null
        val focusedSlot = if (hadFocusInRows) RowEdges.slotOf(rows, focus) else -1
        rows.removeAllViews()
        scoreViews.clear()
        rowLook.clear()
        rowViews.clear()
        if (items.isEmpty()) {
            drawnLook = null
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
        drawnLook = look
        var focusTarget: View? = null
        var firstRow: View? = null
        var first = true
        for (section in sections) {
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
            hadFocusInRows && RowEdges.rowAtSlot(rows, focusedSlot) != null -> RowEdges.rowAtSlot(rows, focusedSlot)!!.requestFocus()
            hadFocusInRows || currentFocus == null -> firstRow?.requestFocus()
        }
    }

    /** Same rows as drawn: refresh each row's meta line, badge and score, and the details panel, without touching focus. */
    private fun refreshInPlace(now: Long) {
        for (rv in rowViews.values) {
            // Only touch a view whose content changed: setText / removeAllViews+addView each request a layout pass,
            // and doing that for every row every 30 s is wasted work on the box's weak CPU.
            val meta = ListSections.meta(rv.item, now, games = games())
            if (meta != rv.shownMeta) {
                rv.shownMeta = meta
                rv.meta.text = meta
                rv.meta.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
            }
            val b = ListSections.badge(rv.item, now, games())
            if (b != rv.shownBadge) {
                rv.shownBadge = b
                rv.right.removeAllViews()
                rv.right.addView(badgeView(rv.item, now))
            }
            applyScore(rv.item, rv.score)
        }
        selected?.let { sel -> showDetails(rowViews[sel.url]?.item ?: sel, now) }
    }

    private fun badgeView(item: ListItem, now: Long): View {
        val b = ListSections.badge(item, now, games())
        return if (b.pill) Ui.badge(this, b.text, b.live) else text(b.text, 11f, Ui.TEXT_2, bold = true)
    }

    private fun showDetails(item: ListItem, now: Long) {
        selected = item
        selectedUrl = item.url
        panel.visibility = View.VISIBLE
        val r = ScoreStatus.resolve(item, now, games())
        val st = r.status
        val b = ListSections.badge(item, now, games())
        val label = when (st) {
            ListStatus.LIVE, ListStatus.SOON -> b.text
            ListStatus.UPCOMING -> r.item.startMillis?.let { ListText.whenText(it, now, java.util.TimeZone.getDefault(), java.util.Locale.getDefault()) } ?: b.text
            else -> r.badge?.uppercase() ?: ListSections.label(st)
        }
        pBadgeHolder.removeAllViews()
        pBadgeHolder.addView(Ui.badge(this, label, st == ListStatus.LIVE))
        pTitle.text = ListText.displayTitle(item)
        pMeta.text = ListSections.meta(item, now, games = games()).replaceFirstChar { it.uppercase() }
        pMeta.visibility = if (pMeta.text.isEmpty()) View.GONE else View.VISIBLE
        fillScoreBlock(item)
    }

    private fun gameFor(item: ListItem): Game? =
        try { ScoreMatch.find(ListText.displayTitle(item), item.startMillis, games()) } catch (_: Exception) { null }

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
        if (v.visibility != View.VISIBLE || v.text.toString() != line) v.text = line
        v.setTextColor(if (g.state == "in") Ui.LIVE_TEXT else Ui.TEXT_2)
        v.visibility = View.VISIBLE
    }

    /**
     * New scores arrived. If a row's status or badge changed (ESPN says it is live, final...), rebuild
     * the list (render keeps focus by link); otherwise change the score texts in place.
     */
    private fun updateScores() {
        val now = System.currentTimeMillis()
        if (scoreViews.any { (item, _) -> rowLook[item.url]?.let { it != look(item, now) } == true }) {
            render()
            return
        }
        for ((item, v) in scoreViews) applyScore(item, v)
        selected?.let { fillScoreBlock(it) }
    }

    private fun row(item: ListItem, now: Long): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            Ui.focusCard(this)
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
        val meta = ListSections.meta(item, now, games = games())
        val metaView = text(meta, 11f, Ui.TEXT_2).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0)
            visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
        }
        col.addView(metaView)
        val scoreLine = text("", 11f, Ui.TEXT_2, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0); visibility = View.GONE }
        col.addView(scoreLine)
        applyScore(item, scoreLine)
        scoreViews.add(item to scoreLine)
        rowLook[item.url] = look(item, now)
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(14) })
        val right = FrameLayout(this)
        right.addView(badgeView(item, now))
        row.addView(right)
        if (!rowViews.containsKey(item.url)) rowViews[item.url] = RowViews(item, metaView, right, scoreLine).also {
            it.shownMeta = meta
            it.shownBadge = ListSections.badge(item, now, games())
        }
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
        /** Statuses are refreshed every 30 beats (30 s) even without a read. */
        private const val RERENDER_BEATS = 30
        fun intent(ctx: Context, id: String) = Intent(ctx, ListActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
