package com.streamlink.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The streams of one game (with its score card), or all 24/7 channels. Built from the lists' caches and
 * ScoreFeed only; it does not read lists itself (the sport screen and the home screen do that).
 */
class StreamsActivity : Activity() {

    private var league = ""
    private var gameId: String? = null
    private lateinit var prefetcher: Prefetcher
    private lateinit var labelView: TextView
    private lateinit var titleView: TextView
    private lateinit var cardHolder: LinearLayout
    private lateinit var rows: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private val itemsByKey = HashMap<String, ListItem>()
    private var beats = 0
    /** One background thread works out the streams; the main thread only draws them. */
    private val calc = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "streams-calc").apply { isDaemon = true } }
    @Volatile private var renderGen = 0
    private var drawnLook: String? = null

    /** [game] null for the 24/7 list; [missing] = the game left the schedule. [look] changes when anything shown would. */
    private class Model(val game: Game?, val missing: Boolean, val streams: List<GameStream>, val emptyMsg: String, val now: Long, val look: String, val ranks: Map<String, Int> = emptyMap())

    private val handler: (String, String?) -> Unit = { c, _ ->
        if (c == "stop") finish()
    }

    private val beat = object : Runnable {
        override fun run() {
            assertSession()
            beats++
            if (beats % SCORE_BEATS == 0) ScoreFeed.refreshIfDue { if (!isFinishing && !isDestroyed) render() }
            if (beats % RERENDER_BEATS == 0) render() // badges, minutes, lists read by other screens
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gameId = intent.getStringExtra(EXTRA_GAME)
        league = Sports.clean(intent.getStringExtra(EXTRA_LEAGUE))
        if (gameId != null && league.isEmpty()) { finish(); return }
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
        prefetcher.setActive(true)
        render()
        // Back from playing with a stream still focused: no focus event comes, so start the 2 s again.
        (currentFocus?.tag as? String)?.let { itemsByKey[it] }?.let { prefetcher.focus(it) }
    }

    override fun onPause() {
        prefetcher.setActive(false)
        main.removeCallbacks(beat)
        super.onPause()
    }

    override fun onStop() {
        main.removeCallbacks(beat)
        releaseSession()
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        calc.shutdownNow()
        if (::prefetcher.isInitialized) prefetcher.cancel()
        releaseSession()
        super.onDestroy()
    }

    // ---- Session (phone status) ----

    private fun sessionTitle() = if (gameId == null) "Sports: 24/7" else "Sports: ${Sports.label(league)} game"

    private fun assertSession() {
        if (Session.commandHandler !== handler || Session.mode != "list") {
            Session.commandHandler = handler
            Session.mode = "list"
            Session.title = sessionTitle()
            Session.snapshot = null
            AppStatus.text = sessionTitle()
        }
    }

    private fun releaseSession() {
        if (Session.commandHandler === handler) {
            Session.commandHandler = null
            Session.mode = "idle"
            AppStatus.text = "Waiting for a link"
        }
    }

    // ---- UI ----

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Ui.BG)
        // Hidden page loader for "Head start" (the focused stream, looked up in advance).
        val prefetchHolder = FrameLayout(this)
        root.addView(prefetchHolder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        prefetcher = Prefetcher(this, prefetchHolder, "list")

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(42), dp(30), dp(42), dp(22))
        }
        labelView = Ui.text(this, if (gameId == null) "Sports" else Sports.label(league), 12f, Ui.TEXT_3)
        content.addView(labelView)
        titleView = Ui.text(this, if (gameId == null) "24/7" else "Game", 27f, Ui.TEXT, bold = true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0)
        }
        content.addView(titleView)
        cardHolder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(this@StreamsActivity, Ui.SURFACE, 12f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            visibility = View.GONE
        }
        content.addView(cardHolder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; isSmoothScrollingEnabled = true
            clipToPadding = false
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(rows, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(14) })
        content.addView(Ui.text(this, "OK  Play   ·   Back  Go back", 11f, Ui.TEXT_3).apply { setPadding(0, dp(10), 0, 0) })
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    /** Asks for a redraw; the work runs on [calc] and rows are rebuilt only when something shown changed. */
    private fun render() {
        val gen = ++renderGen
        val now = System.currentTimeMillis()
        val app = applicationContext
        val lg = league
        val gid = gameId
        try {
            calc.execute {
                if (gen != renderGen) return@execute
                val m = try { compute(app, lg, gid, now) } catch (_: Exception) { return@execute }
                main.post { if (gen == renderGen && !isFinishing && !isDestroyed) draw(m) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    private fun compute(ctx: Context, lg: String, gid: String?, now: Long): Model {
        val streams: List<GameStream>
        val saved = SavedLists.all(ctx)
        val order = Ranking.order(saved)
        val ranks = saved.mapNotNull { l -> l.rank?.let { l.id to it } }.toMap()
        val game: Game?
        val emptyMsg: String
        if (gid != null) {
            val gf = SportsData.folders(ctx, lg, now).games.firstOrNull { it.game.id == gid }
                ?: return Model(null, true, emptyList(), "", now, "missing")
            game = gf.game
            streams = gf.streams
            emptyMsg = SportsText.NO_STREAMS_FOR_GAME
        } else {
            game = null
            streams = AlwaysOn.collect(SportsData.lists(ctx), now, order)
            emptyMsg = SportsText.NO_ALWAYS_ON
        }
        val sb = StringBuilder()
        sb.append("r:").append(ranks.entries.sortedBy { it.value }.joinToString(",") { it.key })
        if (game != null) sb.append(SportsText.matchup(game)).append(game.state).append(ScoreText.cardLines(game).joinToString("/"))
        for (s in streams) {
            sb.append('|').append(GameFolders.urlKey(s.item.url)).append(ListText.displayTitle(s.item)).append(s.listName)
                .append(ListSections.meta(s.item, now)).append(ListSections.badge(s.item, now).text)
        }
        return Model(game, false, streams, emptyMsg, now, sb.toString(), ranks)
    }

    private fun draw(m: Model) {
        if (m.look == drawnLook) return
        drawnLook = m.look
        val now = m.now
        val focus = currentFocus
        val hadFocusInRows = focus?.parent === rows
        val focusedTag = if (hadFocusInRows) focus?.tag as? String else null
        itemsByKey.clear()
        val focusedSlot = if (hadFocusInRows) RowEdges.slotOf(rows, focus) else -1
        rows.removeAllViews()
        cardHolder.removeAllViews()

        if (m.missing) {
            cardHolder.visibility = View.GONE
            rows.addView(Ui.text(this, "This game is no longer in the schedule.", 15f, Ui.TEXT_2).apply { setPadding(0, dp(10), 0, 0) })
            return
        }
        val game = m.game
        if (game != null) {
            titleView.text = SportsText.matchup(game)
            fillCard(game)
        } else {
            cardHolder.visibility = View.GONE
        }
        val streams = m.streams
        if (streams.isEmpty()) {
            rows.addView(Ui.text(this, m.emptyMsg, 15f, Ui.TEXT_2).apply { setPadding(0, dp(10), 0, 0) })
            return
        }
        var firstRow: View? = null
        var focusTarget: View? = null
        for (s in streams) {
            val item = s.item
            val key = GameFolders.urlKey(item.url)
            itemsByKey[key] = item
            val row = SportsUi.streamRow(this, s, now,
                onOpen = { startActivity(WaitingActivity.intent(this, item, s.listId, s.listName)) },
                onFocus = { has -> if (has) prefetcher.focus(item) else prefetcher.blur() },
                rank = m.ranks[s.listId])
            rows.addView(row, SportsUi.rowParams(this))
            if (firstRow == null) firstRow = row
            if (focusedTag != null && key == focusedTag && focusTarget == null) focusTarget = row
        }
        when {
            focusTarget != null -> focusTarget.requestFocus()
            hadFocusInRows && RowEdges.rowAtSlot(rows, focusedSlot) != null -> RowEdges.rowAtSlot(rows, focusedSlot)!!.requestFocus()
            hadFocusInRows || currentFocus == null -> firstRow?.requestFocus()
        }
    }

    private fun fillCard(g: Game) {
        cardHolder.visibility = View.VISIBLE
        if (g.state == "in") {
            cardHolder.addView(Ui.badge(this, "LIVE", true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
        ScoreText.cardLines(g).forEachIndexed { i, l ->
            cardHolder.addView(if (i == 0) Ui.text(this, l, 18f, Ui.TEXT, bold = true) else Ui.text(this, l, 12f, Ui.TEXT_2).apply { setPadding(0, dp(3), 0, 0) })
        }
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    companion object {
        private const val EXTRA_LEAGUE = "league"
        private const val EXTRA_GAME = "game"
        private const val RERENDER_BEATS = 30
        private const val SCORE_BEATS = 5
        fun gameIntent(ctx: Context, league: String, gameId: String) =
            Intent(ctx, StreamsActivity::class.java).putExtra(EXTRA_LEAGUE, league).putExtra(EXTRA_GAME, gameId)
        fun alwaysOnIntent(ctx: Context) = Intent(ctx, StreamsActivity::class.java)
    }
}
