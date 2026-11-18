package com.streamlink.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
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
import java.time.ZoneId

/**
 * One sport (NFL or college football): its games in sections, then "Other" streams that matched no game.
 * Shows the cached lists at once, then re-reads every list tagged with this sport, one after another.
 */
class SportActivity : Activity() {

    private lateinit var league: String
    private lateinit var reader: IndexReader
    private lateinit var rows: LinearLayout
    private lateinit var subtitle: TextView
    private lateinit var errorView: TextView
    private lateinit var refreshBtn: LinearLayout
    private lateinit var allChip: TextView
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayList<String>()
    private var readingList: SavedList? = null
    private var readStartedAt = 0L
    private var lastReadAllAt = 0L
    private var readTotal = 0
    private var readDone = 0
    private var beats = 0

    private val handler: (String, String?) -> Unit = { c, _ ->
        if (c == "stop") finish()
    }

    private val beat = object : Runnable {
        override fun run() {
            assertSession()
            beats++
            val now = System.currentTimeMillis()
            val cur = readingList
            if (cur != null && now - readStartedAt > READ_WATCHDOG_MS) {
                reader.cancel()
                finishRead(cur, ListResult(cur.url, "", emptyList(), "automatic", "the page took too long to answer"))
            }
            maybeReadAll(REREAD_MS)
            ScoreFeed.refreshIfDue { if (!isFinishing && !isDestroyed) render() }
            if (beats % RERENDER_BEATS == 0) render() // clock-based text and lists read elsewhere
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val l = Sports.clean(intent.getStringExtra(EXTRA_LEAGUE))
        if (l.isEmpty()) { finish(); return }
        league = l
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        render()
    }

    override fun onStart() {
        super.onStart()
        assertSession()
    }

    override fun onResume() {
        super.onResume()
        main.removeCallbacks(beat)
        main.post(beat)
        maybeReadAll(MIN_GAP_MS)
        render()
    }

    override fun onPause() {
        main.removeCallbacks(beat)
        super.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) { maybeReadAll(0); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        main.removeCallbacks(beat)
        stopReading()
        releaseSession()
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        stopReading()
        releaseSession()
        super.onDestroy()
    }

    // ---- Session (phone status) ----

    private fun assertSession() {
        if (Session.commandHandler !== handler || Session.mode != "list") {
            Session.commandHandler = handler
            Session.mode = "list"
            Session.title = "Sports: ${Sports.label(league)}"
            Session.snapshot = null
            AppStatus.text = "Sports: ${Sports.label(league)}"
        }
    }

    private fun releaseSession() {
        if (Session.commandHandler === handler) {
            Session.commandHandler = null
            Session.mode = "idle"
            AppStatus.text = "Waiting for a link"
        }
    }

    // ---- Reading the tagged lists, one at a time ----

    /** Starts a round of reads if none is running and the last round began at least [gapMs] ago. */
    private fun maybeReadAll(gapMs: Long) {
        if (readingList != null || queue.isNotEmpty()) return
        val now = System.currentTimeMillis()
        if (lastReadAllAt != 0L && now - lastReadAllAt < gapMs) return
        lastReadAllAt = now
        val ids = SavedLists.all(this).filter { it.sport == league && !SavedLists.isBusy(it.id) }.map { it.id }
        if (ids.isEmpty()) return
        queue.addAll(ids)
        readTotal = ids.size
        readDone = 0
        readNext()
    }

    private fun readNext() {
        if (readingList != null) return
        while (queue.isNotEmpty()) {
            val l = SavedLists.get(this, queue.removeAt(0)) ?: continue
            if (SavedLists.isBusy(l.id)) { readDone++; continue }
            readingList = l
            readStartedAt = System.currentTimeMillis()
            SavedLists.setBusy(l.id, true)
            updateHeader()
            reader.read(l.url) { finishRead(l, it) }
            return
        }
        updateHeader()
    }

    private fun finishRead(l: SavedList, result: ListResult) {
        if (readingList?.id != l.id) return // an answer from a read that was already given up on
        readingList = null
        readDone++
        SavedLists.setBusy(l.id, false)
        SavedLists.applyResult(this, l, result, System.currentTimeMillis())
        render()
        readNext()
    }

    private fun stopReading() {
        queue.clear()
        if (::reader.isInitialized) reader.cancel()
        readingList?.let { SavedLists.setBusy(it.id, false) }
        readingList = null
    }

    // ---- UI ----

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Ui.BG)
        val holder = FrameLayout(this)
        root.addView(holder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        reader = IndexReader(this, holder)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(42), dp(30), dp(42), dp(22))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(Ui.text(this, "Sports", 12f, Ui.TEXT_3))
        titles.addView(Ui.text(this, Sports.label(league), 27f, Ui.TEXT, bold = true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0)
        })
        top.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        allChip = Ui.text(this, SportsText.allGamesChip(showAll()), 13f, Ui.TEXT, bold = true).apply {
            background = Ui.focusable(this@SportActivity, Ui.CHIP, Ui.SURFACE_FOCUS, 8f)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            isFocusable = true; isClickable = true
            setOnClickListener {
                setShowAll(!showAll())
                text = SportsText.allGamesChip(showAll())
                render()
            }
        }
        top.addView(allChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(14) })
        refreshBtn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            background = Ui.focusable(this@SportActivity, 0, Ui.SURFACE_FOCUS, 10f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isFocusable = true; isClickable = true
            setOnClickListener { maybeReadAll(0) }
        }
        refreshBtn.addView(Ui.text(this, "↻  Re-reads your pages every 2 minutes", 11f, Ui.TEXT_2).apply { gravity = Gravity.END })
        subtitle = Ui.text(this, "", 11f, Ui.TEXT_3).apply { gravity = Gravity.END; setPadding(0, dp(2), 0, 0) }
        refreshBtn.addView(subtitle)
        top.addView(refreshBtn)
        content.addView(top)
        errorView = Ui.text(this, "", 12f, Ui.LIVE_TEXT).apply { setPadding(0, dp(8), 0, 0); visibility = View.GONE }
        content.addView(errorView)

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; isSmoothScrollingEnabled = true
            clipToPadding = false
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(rows, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(14) })
        content.addView(Ui.text(this, "OK  Open   ·   Menu  Refresh now", 11f, Ui.TEXT_3).apply { setPadding(0, dp(10), 0, 0) })
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    private fun updateHeader() {
        val now = System.currentTimeMillis()
        val tagged = SavedLists.all(this).filter { it.sport == league }
        val oldest = tagged.map { SavedLists.cached(this, it.id)?.readAt ?: 0L }.minOrNull()
        val ago = when {
            tagged.isEmpty() -> "no pages tagged"
            oldest == null || oldest <= 0L -> "not read yet"
            else -> "updated ${ListText.ago(oldest, now)}"
        }
        subtitle.text = if (readingList != null) "Reading pages… ${(readDone + 1).coerceAtMost(readTotal)} of $readTotal  ($ago)"
        else ago.replaceFirstChar { it.uppercase() }
        val e = tagged.firstNotNullOfOrNull { SavedLists.lastError(it.id) }
        errorView.visibility = if (e == null) View.GONE else View.VISIBLE
        errorView.text = e ?: ""
    }

    private fun render() {
        updateHeader()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val focus = currentFocus
        val hadFocusInRows = focus?.parent === rows
        val focusedTag = if (hadFocusInRows) focus?.tag as? String else null
        val tagged = SportsData.taggedCount(this, league)
        val f = SportsData.folders(this, league, now)
        val summary = GameFolders.summary(f)
        Report.logOnce(summary, summary)
        val showAll = showAll()
        val sections = GameSections.group(f, now, zone, withStreamsOnly = !showAll)

        rows.removeAllViews()
        var firstRow: View? = null
        var focusTarget: View? = null
        fun add(v: View) {
            rows.addView(v, SportsUi.rowParams(this))
            if (firstRow == null) firstRow = v
            if (focusedTag != null && v.tag == focusedTag && focusTarget == null) focusTarget = v
        }
        var first = true
        if (sections.isEmpty()) {
            rows.addView(Ui.text(this, SportsText.emptyMessage(league, tagged, f, !showAll), 15f, Ui.TEXT_2).apply { setPadding(0, dp(10), 0, dp(6)) })
            first = false
        }
        for (sec in sections) {
            rows.addView(Ui.sectionLabel(this, sec.label, if (sec.key == "live") Ui.LIVE_TEXT else Ui.TEXT_2), SportsUi.labelParams(this, first))
            first = false
            for (gf in sec.games) add(gameRow(gf, zone))
        }
        if (f.other.isNotEmpty()) {
            rows.addView(Ui.sectionLabel(this, "Other"), SportsUi.labelParams(this, first))
            for (s in f.other) add(SportsUi.streamRow(this, s, now, onOpen = { play(s) }, onFocus = {}))
        }
        when {
            focusTarget != null -> focusTarget!!.requestFocus()
            hadFocusInRows || currentFocus == null -> (firstRow ?: allChip).requestFocus()
        }
    }

    private fun gameRow(gf: GameFolder, zone: ZoneId): View {
        val g = gf.game
        val live = g.state == "in"
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.focusable(this@SportActivity)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isFocusable = true; isClickable = true
            tag = "g:" + g.id
            setOnClickListener { startActivity(StreamsActivity.gameIntent(this@SportActivity, league, g.id)) }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.text(this, SportsText.matchup(g), 16f, Ui.TEXT, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        col.addView(Ui.text(this, SportsText.gameMeta(g, zone), 11f, if (live) Ui.LIVE_TEXT else Ui.TEXT_2).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0)
        })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(14) })
        if (live) row.addView(Ui.badge(this, "LIVE", true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12) })
        val n = gf.streams.size
        row.addView(Ui.text(this, SportsText.streamsText(n), 12f, if (n > 0) Ui.TEXT else Ui.TEXT_3, bold = n > 0))
        return row
    }

    private fun play(s: GameStream) {
        startActivity(WaitingActivity.intent(this, s.item, s.listId, s.listName))
    }

    private fun showAll(): Boolean = prefs().getBoolean(PREF_PREFIX + league, false)
    private fun setShowAll(on: Boolean) { prefs().edit().putBoolean(PREF_PREFIX + league, on).apply() }
    private fun prefs() = getSharedPreferences("streamlink", Context.MODE_PRIVATE)
    private fun dp(v: Int) = Ui.dp(this, v)

    companion object {
        private const val EXTRA_LEAGUE = "league"
        private const val PREF_PREFIX = "sport_all_games_"
        private const val REREAD_MS = 120_000L
        private const val MIN_GAP_MS = 60_000L
        private const val RERENDER_BEATS = 30
        private const val READ_WATCHDOG_MS = 90_000L
        fun intent(ctx: Context, league: String) = Intent(ctx, SportActivity::class.java).putExtra(EXTRA_LEAGUE, league)
    }
}
