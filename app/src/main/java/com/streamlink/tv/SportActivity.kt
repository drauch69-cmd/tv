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
 * One sport (NFL, college football or NBA): its games in sections, then "Other" streams that matched no game.
 * Shows the cached lists at once, then asks the app's one reader (ListReads) for every list tagged with this sport.
 */
class SportActivity : Activity() {

    private lateinit var league: String
    private lateinit var readHolder: FrameLayout
    private lateinit var rows: LinearLayout
    private lateinit var subtitle: TextView
    private lateinit var errorView: TextView
    private lateinit var refreshBtn: LinearLayout
    private lateinit var allChip: TextView
    private val main = Handler(Looper.getMainLooper())
    private var beats = 0
    /** One background thread works out the folders and sections; the main thread only draws them. */
    private val calc = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "sport-calc").apply { isDaemon = true } }
    @Volatile private var renderGen = 0
    private var drawnLook: String? = null
    private var forceDraw = false

    /** Everything one draw needs, worked out off the main thread. [look] changes when anything shown would change. */
    private class Model(
        val f: SportFolders, val sections: List<GameSections.Section>, val tagged: Int, val showAll: Boolean,
        val now: Long, val look: String,
    )

    private val handler: (String, String?) -> Unit = { c, _ ->
        if (c == "stop") finish()
    }

    /** A read finished or started for one of this sport's lists (ListReads): redraw. */
    private val readListener: (String, ListResult?) -> Unit = { id, result ->
        if (!isFinishing && !isDestroyed && SavedLists.get(this, id)?.has(league) == true) {
            if (result != null) render() else updateHeader()
        }
    }

    private val beat = object : Runnable {
        override fun run() {
            assertSession()
            beats++
            if (beats % READ_CHECK_BEATS == 0) readDue(ListReadLogic.Reason.TIMER)
            if (beats % SCORE_BEATS == 0) ScoreFeed.refreshIfDue { if (!isFinishing && !isDestroyed) render() }
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
        ListReads.addListener(readListener)
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
        ListReads.attach(this, readHolder)
        readDue(ListReadLogic.Reason.OPEN) // lists read a moment ago (home, another screen) are skipped
        render()
    }

    override fun onPause() {
        main.removeCallbacks(beat)
        super.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) { readDue(ListReadLogic.Reason.MANUAL); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        main.removeCallbacks(beat)
        releaseSession()
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        calc.shutdownNow()
        ListReads.removeListener(readListener)
        ListReads.detach(this)
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

    /**
     * Asks the app's one reader (ListReads) for this sport's tagged lists. MANUAL reads them all; OPEN asks for every
     * list (ones read a moment ago are skipped); TIMER only for lists due by ListReadLogic.nextReadDelay.
     */
    private fun readDue(reason: ListReadLogic.Reason) {
        val now = System.currentTimeMillis()
        for (l in SavedLists.all(this)) {
            if (!l.has(league) || SavedLists.isBusy(l.id)) continue
            if (reason == ListReadLogic.Reason.TIMER) {
                val items = SavedLists.cached(this, l.id)?.result?.items ?: emptyList()
                if (now < ListReads.lastAttempt(this, l.id) + ListReadLogic.nextReadDelay(items, now)) continue
            }
            ListReads.request(this, l.id, reason)
        }
        updateHeader()
    }

    // ---- UI ----

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Ui.BG)
        readHolder = FrameLayout(this)
        root.addView(readHolder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

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
            Ui.focusCard(this, Ui.CHIP, Ui.SURFACE_FOCUS, 8f)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            isFocusable = true; isClickable = true
            setOnClickListener {
                setShowAll(!showAll())
                text = SportsText.allGamesChip(showAll())
                render(force = true)
            }
        }
        top.addView(allChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(14) })
        refreshBtn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            Ui.focusCard(this, 0, Ui.SURFACE_FOCUS, 10f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isFocusable = true; isClickable = true
            setOnClickListener { readDue(ListReadLogic.Reason.MANUAL) }
        }
        refreshBtn.addView(Ui.text(this, "↻  Re-reads your pages by itself", 11f, Ui.TEXT_2).apply { gravity = Gravity.END })
        subtitle = Ui.text(this, "", 11f, Ui.TEXT_3).apply { gravity = Gravity.END; setPadding(0, dp(2), 0, 0) }
        refreshBtn.addView(subtitle)
        top.addView(refreshBtn)
        content.addView(top)
        // Lists with no events: a rounded yellow warning triangle and the page's address in yellow (user, 2026-11-20).
        errorView = Ui.text(this, "", 12f, Ui.WARN).apply {
            val warn = android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.PathShape(android.graphics.Path().apply {
                moveTo(6f, 1.5f); lineTo(11f, 10.5f); lineTo(1f, 10.5f); close()
            }, 12f, 12f)).apply {
                paint.color = Ui.WARN
                paint.style = android.graphics.Paint.Style.FILL_AND_STROKE
                paint.strokeWidth = 1.6f
                paint.strokeJoin = android.graphics.Paint.Join.ROUND
                paint.pathEffect = android.graphics.CornerPathEffect(1.6f)
                setIntrinsicWidth(dp(13)); setIntrinsicHeight(dp(13))
            }
            setCompoundDrawablesRelativeWithIntrinsicBounds(warn, null, null, null)
            compoundDrawablePadding = dp(8)
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        content.addView(errorView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; isSmoothScrollingEnabled = true
            clipToPadding = false
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rows.addView(Ui.text(this, "Loading games…", 15f, Ui.TEXT_3).apply { setPadding(0, dp(10), 0, dp(6)) })
        scroll.addView(rows, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(14) })
        content.addView(Ui.text(this, "OK  Open   ·   Menu  Refresh now", 11f, Ui.TEXT_3).apply { setPadding(0, dp(10), 0, 0) })
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    private fun updateHeader() {
        val now = System.currentTimeMillis()
        val tagged = SavedLists.all(this).filter { it.has(league) }
        val oldest = tagged.map { SavedLists.cached(this, it.id)?.readAt ?: 0L }.minOrNull()
        val ago = when {
            tagged.isEmpty() -> "no pages tagged"
            oldest == null || oldest <= 0L -> "not read yet"
            else -> "updated ${ListText.ago(oldest, now)}"
        }
        val busy = tagged.count { SavedLists.isBusy(it.id) }
        subtitle.text = if (busy > 0) "Reading pages… $busy left  ($ago)"
        else ago.replaceFirstChar { it.uppercase() }
        val e = tagged.firstNotNullOfOrNull { SavedLists.lastError(it.id) }
        errorView.visibility = if (e == null) View.GONE else View.VISIBLE
        errorView.text = e ?: ""
    }

    /** Asks for a redraw. The work runs on [calc]; rows are rebuilt only when something shown changed (or [force]). */
    private fun render(force: Boolean = false) {
        updateHeader()
        if (force) forceDraw = true
        val gen = ++renderGen
        val now = System.currentTimeMillis()
        val showAll = showAll()
        val app = applicationContext
        val lg = league
        try {
            calc.execute {
                if (gen != renderGen) return@execute // a newer request is queued
                val m = try { compute(app, lg, now, showAll) } catch (_: Exception) { return@execute }
                main.post { if (gen == renderGen && !isFinishing && !isDestroyed) draw(m) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    private fun compute(ctx: Context, lg: String, now: Long, showAll: Boolean): Model {
        val zone = ZoneId.systemDefault()
        val tagged = SportsData.taggedCount(ctx, lg)
        val f = SportsData.folders(ctx, lg, now)
        val sections = GameSections.group(f, now, zone, withStreamsOnly = !showAll)
        val sb = StringBuilder()
        sb.append(showAll).append('#').append(tagged).append('#')
        if (sections.isEmpty()) sb.append(SportsText.emptyMessage(lg, tagged, f, !showAll))
        for (sec in sections) {
            sb.append('[').append(sec.key).append(sec.label)
            for (gf in sec.games) {
                val g = gf.game
                sb.append('|').append(g.id).append(g.state).append(SportsText.matchup(g)).append(SportsText.gameMeta(g, zone))
                    .append(SportsText.streamsText(gf.streams.size))
            }
            sb.append(']')
        }
        for (s in f.other) sb.append("|o").append(streamLook(s, now))
        return Model(f, sections, tagged, showAll, now, sb.toString())
    }

    private fun streamLook(s: GameStream, now: Long): String =
        GameFolders.urlKey(s.item.url) + ListText.displayTitle(s.item) + s.listName + ListSections.meta(s.item, now) + ListSections.badge(s.item, now).text

    private fun draw(m: Model) {
        if (!forceDraw && m.look == drawnLook) return
        forceDraw = false
        drawnLook = m.look
        val now = m.now
        val zone = ZoneId.systemDefault()
        val f = m.f
        val sections = m.sections
        val showAll = m.showAll
        val tagged = m.tagged
        val focus = currentFocus
        val hadFocusInRows = focus?.parent === rows
        val focusedTag = if (hadFocusInRows) focus?.tag as? String else null
        val summary = GameFolders.summary(f)
        Report.logOnce(summary, summary)

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
        val started = g.state == "in" || g.state == "post"
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            Ui.fadeCard(this, 14f)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            isFocusable = true; isClickable = true
            tag = "g:" + g.id
            contentDescription = SportsText.matchup(g)
            setOnClickListener { startActivity(StreamsActivity.gameIntent(this@SportActivity, league, g.id)) }
        }
        // Mirrored around the time or score (user's mockup, 2026-11-20): "Cardinals [logo] 7:00 PM [logo] Steelers".
        // Each name hugs its own logo; the first team's text is right-aligned, the second's left-aligned. A spacer as
        // wide as the stream count on the left keeps the middle exactly in the middle of the card.
        fun side(t: TeamSide, mirrored: Boolean): LinearLayout {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL or if (mirrored) Gravity.END else Gravity.START
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = if (mirrored) Gravity.END else Gravity.START }
            // A vertical LinearLayout stretches its children to its width, so the text itself must be aligned too.
            val align = if (mirrored) Gravity.END else Gravity.START
            col.addView(Ui.text(this, t.shortName.ifBlank { t.displayName }, 15f, Ui.TEXT, bold = true).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = align
            })
            if (t.record.isNotBlank()) col.addView(Ui.text(this, t.record, 10.5f, Ui.TEXT_2).apply { setPadding(0, dp(3), 0, 0); gravity = align })
            val logo = Logos.view(this, t, 32)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (mirrored) {
                box.addView(col, lp.apply { marginEnd = dp(12) })
                box.addView(logo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(16) })
            } else {
                box.addView(logo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(16) })
                box.addView(col, lp.apply { marginStart = dp(12) })
            }
            return box
        }
        row.addView(View(this), LinearLayout.LayoutParams(dp(CHIP_W), 1))
        row.addView(side(g.away, mirrored = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        if (started && ScoreStatus.cancelLabel(g) == null) {
            mid.addView(Ui.text(this, "${ScoreText.points(g.away)} – ${ScoreText.points(g.home)}", 21f, Ui.TEXT, bold = true))
            mid.addView(Ui.text(this, ScoreText.status(g) ?: "", 11f, if (g.state == "in") Ui.LIVE_TEXT else Ui.TEXT_2, bold = g.state == "in").apply {
                setPadding(0, dp(3), 0, 0)
            })
        } else {
            mid.addView(Ui.badge(this, ScoreStatus.cancelLabel(g) ?: SportsText.kickoff(g.startMillis, zone), false),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        row.addView(mid, LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(side(g.home, mirrored = false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val chip = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        chip.addView(Ui.streamChip(this, gf.streams.size, 12f))
        row.addView(chip, LinearLayout.LayoutParams(dp(CHIP_W), ViewGroup.LayoutParams.WRAP_CONTENT))
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
        private const val CHIP_W = 56
        private const val EXTRA_LEAGUE = "league"
        private const val PREF_PREFIX = "sport_all_games_"
        /** How often (beats = seconds) the tagged lists are checked for a due re-read. */
        private const val READ_CHECK_BEATS = 5
        private const val RERENDER_BEATS = 30
        private const val SCORE_BEATS = 5
        fun intent(ctx: Context, league: String) = Intent(ctx, SportActivity::class.java).putExtra(EXTRA_LEAGUE, league)
    }
}
