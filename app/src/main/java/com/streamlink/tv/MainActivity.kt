package com.streamlink.tv

import android.app.Activity
import android.app.AlertDialog
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.Uri
import android.content.Intent
import android.provider.Settings
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.KeyEvent
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import java.util.Locale
import java.util.TimeZone

/**
 * The home screen: "Live now" from all saved lists, the list tiles, recent links, and the "Send a link"
 * panel with the QR code for the phone page. Also runs the phone page server.
 */
class MainActivity : Activity() {

    private lateinit var sniffer: StreamSniffer
    private lateinit var prefetcher: Prefetcher
    private lateinit var prefetchToggle: TextView
    private var liveItems: Map<String, ListItem> = emptyMap()
    private lateinit var server: LinkServer
    private lateinit var statusView: TextView
    private lateinit var recentList: LinearLayout
    private lateinit var recentLabel: TextView
    private lateinit var reader: IndexReader
    private val main = Handler(Looper.getMainLooper())
    private var readingId: String? = null
    private var readToken = 0
    private var port = -1
    private var shownLink: String? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var resumed = false
    private lateinit var updateBanner: TextView
    private lateinit var updatePerm: TextView
    private lateinit var updateSettings: TextView
    private lateinit var checkUpdate: TextView
    private lateinit var updateInfo: TextView
    private lateinit var liveRow: LinearLayout
    private lateinit var liveSub: TextView
    private lateinit var liveGamesHeader: View
    private lateinit var liveGamesScroll: View
    private lateinit var liveGamesRow: LinearLayout
    private lateinit var sportsRow: LinearLayout
    private lateinit var qrPanel: View
    private lateinit var sendLink: TextView
    private lateinit var yourLists: TextView
    private val homeTick = object : Runnable {
        override fun run() {
            if (resumed) {
                refreshStale()
                renderLists() // statuses change with the clock: LIVE badges, "starts in"
            }
            main.postDelayed(this, HOME_TICK_MS)
        }
    }
    /**
     * Every 5 s while the home screen is in front: lets the 30 s live-score cadence run. This is the only score
     * refresher on this screen; when the scores really changed it asks for one redraw of everything that depends on them.
     */
    private val scoreTick = object : Runnable {
        override fun run() {
            if (resumed) ScoreFeed.refreshIfDue { if (!isFinishing && !isDestroyed && resumed) requestHome(false) }
            main.postDelayed(this, SCORE_TICK_MS)
        }
    }

    // ---- Home data: worked out on one background thread, drawn on the main thread ----
    private val homeCalc = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "home-calc").apply { isDaemon = true } }
    /** Bumped on every request; a result that is not from the latest request is dropped. Main thread only. */
    private var homeGen = 0
    /** What the home screen showed last, so a score update only redraws when it changes something. */
    private var homeLook = ""
    /** The scores the last request was made for (same instance until the data changes). */
    private var homeGames: List<Game>? = null
    /** A forced redraw is wanted (lists, busy marks or the clock changed), even if the look is the same. */
    private var homeForce = false

    private fun computeHome(ctx: android.content.Context, games: List<Game>, now: Long): HomeModel {
        val lists = SavedLists.all(ctx)
        val byId = HashMap<String, CachedList>()
        for (l in lists) SavedLists.cached(ctx, l.id)?.let { byId[l.id] = it }
        return HomeModel.build(lists, byId, games, now, java.time.ZoneId.systemDefault())
    }

    /**
     * Asks for the home rows to be rebuilt. [force]: draw even when nothing in the look changed (the lists, a busy
     * mark or the clock changed). Not forced (new scores): drawn only when the look changed. Not forced and the
     * scores are the same instance as last time: nothing to do.
     */
    private fun requestHome(force: Boolean) {
        val games = ScoreFeed.latest()
        if (!force && games === homeGames) return
        homeGames = games
        if (force) homeForce = true
        val gen = ++homeGen
        val now = System.currentTimeMillis()
        val app = applicationContext
        homeCalc.execute {
            val m = try { computeHome(app, games, now) } catch (_: Exception) { return@execute }
            main.post { if (gen == homeGen && !isFinishing && !isDestroyed) applyHome(m) }
        }
    }

    /** Same as a forced [requestHome] but worked out and drawn right now (first draw, list removed). */
    private fun renderHomeNow() {
        val games = ScoreFeed.latest()
        homeGames = games
        homeForce = true
        homeGen++
        applyHome(computeHome(this, games, System.currentTimeMillis()))
    }

    private fun applyHome(m: HomeModel) {
        if (!homeForce && (m.look == homeLook || !resumed)) return
        homeForce = false
        homeLook = m.look
        renderSports(m) // also draws "Live now"
        ListEvents.changed() // the "Your lists" screen, if open
    }
    private val updateTick = object : Runnable {
        override fun run() {
            Updater.checkIfDue(this@MainActivity) // checks at most every 12 h, and not while something plays
            main.postDelayed(this, 30 * 60 * 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ChromeNet.init(this)
        statusView = findViewById(R.id.status)
        recentList = findViewById(R.id.recentList)
        recentLabel = findViewById(R.id.recentLabel)
        updateBanner = findViewById(R.id.updateBanner)
        updatePerm = findViewById(R.id.updatePerm)
        updateSettings = findViewById(R.id.updateSettings)
        checkUpdate = findViewById(R.id.checkUpdate)
        updateInfo = findViewById(R.id.updateInfo)
        liveRow = findViewById(R.id.liveRow)
        liveSub = findViewById(R.id.liveSub)
        liveGamesHeader = findViewById(R.id.liveGamesHeader)
        liveGamesScroll = findViewById(R.id.liveGamesScroll)
        liveGamesRow = findViewById(R.id.liveGamesRow)
        sportsRow = findViewById(R.id.sportsRow)
        qrPanel = findViewById(R.id.qrPanel)
        sendLink = findViewById(R.id.sendLink)
        yourLists = findViewById(R.id.yourLists)
        prefetchToggle = findViewById(R.id.prefetchToggle)
        styleHome()
        updateBanner.setOnClickListener { installUpdate() }
        updateSettings.setOnClickListener { openInstallSettings() }
        checkUpdate.setOnClickListener { Updater.check(this, manual = true) }
        prefetchToggle.setOnClickListener { setPrefetch(!PrefetchSettings.enabled(this)) }
        renderPrefetchToggle()
        Session.prefetchEnabled = { PrefetchSettings.enabled(applicationContext) }
        sendLink.setOnClickListener { showQr(true) }
        yourLists.setOnClickListener { startActivity(ListsActivity.intent(this)) }
        ListEvents.refresh = { id -> runOnUiThread { if (!isFinishing && !isDestroyed) refreshList(id) } }
        findViewById<View>(R.id.qrClose).setOnClickListener { showQr(false) }
        sniffer = StreamSniffer(this, findViewById(R.id.webHolder))
        // Reads index pages when the phone asks for a refresh and no list screen is open.
        reader = IndexReader(this, findViewById(R.id.webHolder))
        prefetcher = Prefetcher(this, findViewById(R.id.prefetchHolder), "idle")

        val pages = mapOf("/" to asset("phone.html"), "/shortcut" to asset("shortcut.html"))
        server = LinkServer(
            pages = pages,
            onLink = { url -> runOnUiThread { play(url) } },
            onCommand = { c, v -> runOnUiThread { command(c, v) } },
            status = { AppStatus.text },
            state = { Session.stateJson() },
            report = { Report.text() },
            sample = { SampleRecorder.zip(Report.text(), BuildConfig.VERSION_NAME) },
            lists = { ListJson.listsForPhone(SavedLists.all(this), { SavedLists.cached(this, it) }, { SavedLists.isBusy(it) }, { SavedLists.lastError(it) }, System.currentTimeMillis()) },
            list = { id ->
                SavedLists.get(this, id)?.let { l ->
                    ListJson.listForPhone(l, SavedLists.cached(this, id), SavedLists.isBusy(id), SavedLists.lastError(id), System.currentTimeMillis())
                }
            },
            onListAdd = { url, name, sports -> addList(url, name, sports) },
            onListSport = { id, sports -> SavedLists.setSports(this, id, sports) },
            onListPreferred = { id, on -> SavedLists.setPreferred(this, id, on).also { ok -> if (ok) runOnUiThread { renderLists() } } },
            onListRemove = { id -> removeList(id) },
            onListRefresh = { id -> SavedLists.get(this, id)?.let { runOnUiThread { refreshList(id) }; true } ?: false },
            onListPlay = { id, url, start ->
                SavedLists.get(this, id)?.let { l -> runOnUiThread { playListItem(l, url, start) }; true } ?: false
            },
        )
        port = try { server.start() } catch (_: Exception) { -1 }
        showAddress()
        renderHomeNow()
        renderRecents()
        watchNetwork()
        Updater.listener = { runOnUiThread { renderUpdate() } }
        renderUpdate()
        // First run (nothing saved yet): the phone code is what is needed, so show it right away.
        if (SavedLists.all(this).isEmpty() && Recents.list(this).isEmpty()) showQr(true) else focusFirst()
        main.post(updateTick)
        main.postDelayed(homeTick, HOME_TICK_MS)
    }

    override fun onPause() {
        resumed = false
        main.removeCallbacks(scoreTick)
        prefetcher.setActive(false)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        main.removeCallbacks(scoreTick)
        main.post(scoreTick)
        prefetcher.setActive(true)
        renderUpdate() // the user may just have allowed installs in Settings
        showAddress()
        renderLists()
        refreshStale()
        if (ListEvents.showQrOnHome) { ListEvents.showQrOnHome = false; showQr(true) }
        if (Session.mode == "idle") statusView.text = lastStatusForScreen()
        // Back from playing with a "Live now" card still focused: no focus event comes, so start the 2 s again.
        (liveRow.focusedChild?.tag as? String)?.let { liveItems[it] }?.let { prefetcher.focus(it) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (qrPanel.visibility == View.VISIBLE && (SavedLists.all(this).isNotEmpty() || Recents.list(this).isNotEmpty())) {
            showQr(false)
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU && qrPanel.visibility != View.VISIBLE) {
            val lists = SavedLists.all(this)
            if (lists.isEmpty()) {
                Toast.makeText(this, "No lists yet: add one from the phone page", Toast.LENGTH_SHORT).show()
            } else {
                lists.forEach { refreshList(it.id) }
                Toast.makeText(this, "Refreshing ${lists.size} list${if (lists.size == 1) "" else "s"}…", Toast.LENGTH_SHORT).show()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        Updater.listener = null
        ListEvents.refresh = null
        netCallback?.let {
            try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        server.stop()
        sniffer.cancel()
        prefetcher.cancel()
        reader.cancel()
        main.removeCallbacksAndMessages(null)
        readingId?.let { SavedLists.setBusy(it, false) }
        super.onDestroy()
    }

    private fun asset(name: String): String =
        try { assets.open(name).bufferedReader().use { it.readText() } } catch (_: Exception) { "<h1>Missing $name</h1>" }

    // ---- Address / QR (refreshes itself when the network changes) ----

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) = refresh()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = refresh()
            private fun refresh() { runOnUiThread { showAddress() } }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (_: Exception) {
        }
    }

    private fun showAddress() {
        val ip = NetUtil.localIp()
        val address: TextView = findViewById(R.id.address)
        val qr: ImageView = findViewById(R.id.qr)
        val link = if (ip != null && port > 0) "http://$ip:$port" else null
        if (link == shownLink) return
        shownLink = link
        if (link == null) {
            address.text = "Not connected to your network"
            qr.setImageDrawable(null)
        } else {
            address.text = link
            qr.setImageBitmap(Qr.bitmap(link))
        }
    }

    // ---- Links ----

    private fun play(url: String) {
        // A new link replaces the site's own player right away; the built-in player is replaced
        // once the new stream is ready.
        prefetcher.cancel()
        if (Session.mode == "site" || Session.mode == "waiting") Session.commandHandler?.invoke("stop", null)
        Recents.add(this, url)
        renderRecents()
        AutoSwitch.reset() // the user started something: a new watch
        Session.listId = null // a direct link is not part of a list
        Session.listName = ""
        Session.listItemUrl = null
        Report.start(url)
        val site = Uri.parse(url).host ?: url
        Session.title = site
        val playing = Session.commandHandler
        if (Session.mode == "player" && playing != null) {
            // The player is in front; it finds the new stream with its own visible page loader.
            setStatus("Finding the video on $site…")
            playing("open", url)
            return
        }
        if (Session.mode == "idle") Session.mode = "finding"
        setStatus("Finding the video on $site…")
        sniffer.sniff(url) { set ->
            if (set.sources.isEmpty()) {
                setStatus("No direct stream on $site, showing the site's own player")
                startActivity(SitePlayerActivity.intent(this, set.pageUrl))
            } else {
                Session.current = set
                setStatus("Playing $site")
                startActivity(PlayerActivity.intent(this))
            }
        }
    }

    // ---- Stream lists ----

    /** Phone: save a link as a stream list. Returns null on success, else the reason. */
    private fun addList(url: String, name: String, sports: Set<String>): String? {
        val l = SavedLists.add(this, url, name, sports) ?: return "You can save up to ${SavedLists.MAX} stream lists. Remove one first."
        runOnUiThread { renderLists(); refreshList(l.id) }
        return null
    }

    private fun removeList(id: String): Boolean {
        val ok = SavedLists.remove(this, id)
        if (ok) runOnUiThread { renderHomeNow() }
        return ok
    }

    /** Lists waiting to be read in the background, in order. One page is read at a time. */
    private val readQueue = LinkedHashSet<String>()

    /**
     * Re-reads a list: through its own screen if it is open, otherwise here in the background.
     * Background reads are queued, so adding several lists quickly reads every one of them
     * (a new request used to cancel the read in progress).
     */
    private fun refreshList(id: String) {
        if (ListActivity.openId == id) {
            Session.commandHandler?.invoke("refresh", id)
            return
        }
        if (SavedLists.get(this, id) == null) return
        if (readingId == id) return
        readQueue.add(id)
        SavedLists.setBusy(id, true)
        renderLists()
        if (readingId == null) readNext()
    }

    private fun readNext() {
        val id = readQueue.firstOrNull() ?: return
        readQueue.remove(id)
        val l = SavedLists.get(this, id)
        if (l == null) {
            readNext()
            return
        }
        readingId = id
        val token = ++readToken
        fun done(r: ListResult) {
            if (token != readToken) return
            readingId = null
            SavedLists.setBusy(id, false)
            SavedLists.applyResult(this, l, r, System.currentTimeMillis())
            renderLists()
            readNext()
        }
        reader.read(l.url) { done(it) }
        main.postDelayed({
            if (token == readToken && readingId == id) {
                reader.cancel()
                done(ListResult(l.url, "", emptyList(), "automatic", "the page took too long to answer"))
            }
        }, 90_000)
    }

    /** An event from a list: the waiting screen finds its stream, or waits for it to start. */
    private fun playListItem(l: SavedList, url: String, start: Long?) {
        val known = SavedLists.cached(this, l.id)?.result?.items?.firstOrNull { it.url == url }
        val item = known ?: ListItem("", "", url, start, "", "", false, null)
        sniffer.cancel() // a direct link still being looked for is replaced
        prefetcher.cancel()
        val mode = Session.mode
        if (mode == "player" || mode == "clean" || mode == "site" || mode == "waiting") Session.commandHandler?.invoke("stop", null)
        startActivity(WaitingActivity.intent(this, item, l.id, l.name))
    }

    /** Re-reads, in the background, lists not read for [STALE_MS] (so "Live now" stays current). */
    private fun refreshStale() {
        val now = System.currentTimeMillis()
        for (l in SavedLists.all(this)) {
            val c = SavedLists.cached(this, l.id)
            if (c == null || now - c.readAt > STALE_MS) {
                if (!SavedLists.isBusy(l.id) && SavedLists.lastError(l.id) == null) refreshList(l.id)
            }
        }
    }

    /** Redraws the home rows from the saved lists and the scores (worked out in the background). */
    private fun renderLists() { requestHome(true); ListEvents.changed() }

    /** The "Live now" row: live events from every list, then ones starting soon. */
    private fun renderLive(m: HomeModel) {
        val focusedUrl = liveRow.focusedChild?.tag as? String
        liveRow.removeAllViews()
        val now = System.currentTimeMillis()
        val lists = m.lists
        val cached = m.cached
        // Items already shown as a live game card are not shown a second time here (worked out in the model).
        val rows = m.liveRows
        val oldest = cached.minOfOrNull { it.second.readAt }
        liveSub.text = when {
            lists.isEmpty() -> "add a list of events from your phone to see what's live"
            lists.any { SavedLists.isBusy(it.id) } -> "from all your lists · checking now…"
            oldest != null -> "from all your lists · updated ${ListText.ago(oldest, now)}"
            else -> "from all your lists"
        }
        var focusTarget: View? = null
        val zone = TimeZone.getDefault()
        val locale = Locale.getDefault()
        liveItems = rows.associate { it.item.url to it.item }
        for (e in rows) {
            val live = e.status == ListStatus.LIVE
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                Ui.focusCard(this, radiusDp = 12f)
                setPadding(dp(15), dp(13), dp(15), dp(13))
                isFocusable = true
                isClickable = true
                tag = e.item.url
                if (e.status == ListStatus.UPCOMING) alpha = 0.75f
                setOnClickListener { playListItem(e.list, e.item.url, e.item.startMillis) }
                // Head start on focus.
                setOnFocusChangeListener { _, has -> if (has) prefetcher.focus(e.item) else prefetcher.blur() }
            }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            val badgeText = HomeFeed.badge(e.item, e.status, now, zone, locale)
            if (badgeText.isNotEmpty()) top.addView(Ui.badge(this, badgeText, live, 10f))
            top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            top.addView(Ui.text(this, e.list.name, 10.5f, Ui.TEXT_3).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = dp(100)
            })
            card.addView(top)
            card.addView(Ui.text(this, ListText.displayTitle(e.item), 16f, Ui.TEXT, bold = true).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setLineSpacing(0f, 1.15f)
                setPadding(0, dp(9), 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            card.addView(Ui.text(this, HomeFeed.whenLine(e.item, now, zone, locale), 11.5f, Ui.TEXT_2).apply { maxLines = 1 })
            val lp = LinearLayout.LayoutParams(dp(210), dp(126))
            lp.marginEnd = dp(15)
            liveRow.addView(card, lp)
            if (e.item.url == focusedUrl) focusTarget = card
        }
        // The focused card left the row (e.g. its event ended): keep focus in the row instead of losing it.
        if (focusedUrl != null && focusTarget == null) focusTarget = liveRow.getChildAt(0)?.takeIf { it.isFocusable } ?: sendLink
        if (rows.isEmpty()) {
            val msg = when {
                lists.isEmpty() -> "Nothing to show yet. On the phone page, paste a page that lists events and tap “Add as stream list”."
                cached.isEmpty() -> "Your lists haven't been read yet. Press Menu to read them now."
                else -> "Nothing live or starting soon. Press Menu to check again, or open a list for later events."
            }
            liveRow.addView(Ui.text(this, msg, 13f, Ui.TEXT_2).apply {
                background = Ui.rounded(this@MainActivity, Ui.SURFACE, 12f)
                setPadding(dp(18), dp(16), dp(18), dp(16))
                maxWidth = dp(560)
            })
        }
        focusTarget?.requestFocus()
    }

    /** The "Live games" row, the "Sports" row, and "Live now" (which skips games shown as cards). */
    private fun renderSports(m: HomeModel) {
        renderLiveGames(m.folders)
        renderSportTiles(m)
        renderLive(m)
    }

    private fun renderLiveGames(folders: List<SportFolders>) {
        val focusedId = liveGamesRow.focusedChild?.tag as? String
        liveGamesRow.removeAllViews()
        val games = LiveGames.sample(folders, 8)
        val vis = if (games.isEmpty()) View.GONE else View.VISIBLE
        liveGamesHeader.visibility = vis
        liveGamesScroll.visibility = vis
        var focusTarget: View? = null
        for (gf in games) {
            val g = gf.game
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                Ui.focusCard(this, radiusDp = 12f)
                setPadding(dp(15), dp(13), dp(15), dp(13))
                isFocusable = true
                isClickable = true
                tag = g.id
                setOnClickListener { startActivity(StreamsActivity.gameIntent(this@MainActivity, g.league, g.id)) }
            }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            top.addView(Ui.badge(this, "LIVE", true, 10f))
            top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            top.addView(Ui.text(this, Sports.label(g.league), 10.5f, Ui.TEXT_3).apply { maxLines = 1 })
            card.addView(top)
            card.addView(Ui.text(this, SportsText.matchupShort(g), 17f, Ui.TEXT, bold = true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(9), 0, 0)
            })
            card.addView(Ui.text(this, ScoreText.rowLine(g) ?: "", 11.5f, Ui.LIVE_TEXT).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(5), 0, 0)
            })
            card.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
            card.addView(Ui.text(this, SportsText.streamsText(gf.streams.size), 11.5f, Ui.TEXT_2).apply { maxLines = 1 })
            val lp = LinearLayout.LayoutParams(dp(230), dp(126))
            lp.marginEnd = dp(15)
            liveGamesRow.addView(card, lp)
            if (g.id == focusedId) focusTarget = card
        }
        // The focused game ended or left the row: move focus to the sports tiles rather than losing it.
        if (focusedId != null && focusTarget == null) focusTarget = sportsRow.getChildAt(0)
        focusTarget?.requestFocus()
    }

    private fun renderSportTiles(m: HomeModel) {
        val focusedTag = sportsRow.focusedChild?.tag as? String
        sportsRow.removeAllViews()
        val now = System.currentTimeMillis()
        var focusTarget: View? = null
        fun tile(key: String, name: String, line: String, foot: String, open: () -> Unit) {
            val t = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                Ui.focusCard(this, radiusDp = 11f)
                setPadding(dp(15), dp(13), dp(15), dp(13))
                isFocusable = true
                isClickable = true
                tag = key
                setOnClickListener { open() }
            }
            t.addView(Ui.text(this, name, 15f, Ui.TEXT, bold = true).apply { maxLines = 1 })
            val sb = SpannableStringBuilder(line)
            val i = line.indexOf(" live")
            if (i > 0) {
                sb.setSpan(ForegroundColorSpan(Ui.LIVE_TEXT), 0, i + 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, i + 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            t.addView(Ui.text(this, sb, 11.5f, Ui.TEXT_2).apply { maxLines = 1; setPadding(0, dp(7), 0, 0) })
            t.addView(Ui.text(this, foot, 10.5f, Ui.TEXT_3).apply { maxLines = 1; setPadding(0, dp(6), 0, 0) })
            val lp = LinearLayout.LayoutParams(dp(210), ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(15)
            sportsRow.addView(t, lp)
            if (key == focusedTag) focusTarget = t
        }
        for (f in m.folders) {
            tile(f.league, Sports.label(f.league), SportsText.sportTileLine(m.taggedCounts[f.league] ?: 0, f), "Today and the next 2 days") {
                startActivity(SportActivity.intent(this, f.league))
            }
        }
        val n = m.alwaysOnCount
        tile(ALWAYS_ON_TAG, "24/7", SportsText.alwaysOnLine(n), "From all your lists") {
            startActivity(StreamsActivity.alwaysOnIntent(this))
        }
        focusTarget?.requestFocus()
    }

    private fun focusFirst() {
        (liveRow.getChildAt(0)?.takeIf { it.isFocusable } ?: sendLink).requestFocus()
    }

    /** The "Send a link" panel: QR code, address, version and update check. */
    private fun showQr(show: Boolean) {
        qrPanel.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            showAddress()
            findViewById<View>(R.id.qrClose).requestFocus()
        } else {
            focusFirst()
        }
    }

    private fun styleHome() {
        Ui.focusCard(sendLink, normal = Ui.SURFACE, radiusDp = 9f)
        Ui.focusCard(yourLists, normal = Ui.SURFACE, radiusDp = 9f)
        val listsIcon = GradientDrawable().apply { setColor(Ui.TEXT); cornerRadius = dp(2).toFloat(); setSize(dp(14), dp(10)) }
        yourLists.setCompoundDrawablesRelativeWithIntrinsicBounds(listsIcon, null, null, null)
        val qrIcon = GradientDrawable().apply { setColor(0); setStroke(dp(2), Ui.TEXT); cornerRadius = dp(2).toFloat(); setSize(dp(14), dp(14)) }
        sendLink.setCompoundDrawablesRelativeWithIntrinsicBounds(qrIcon, null, null, null)
        val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.ACCENT); setSize(dp(12), dp(12)) }
        findViewById<TextView>(R.id.brand).setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
        findViewById<View>(R.id.qrCard).background = Ui.rounded(this, Ui.SURFACE, 16f, Ui.BORDER, 1f)
        Ui.focusCard(checkUpdate, normal = Ui.CHIP, radiusDp = 8f)
        Ui.focusCard(prefetchToggle, normal = Ui.CHIP, radiusDp = 8f)
        Ui.focusCard(findViewById<View>(R.id.qrClose), normal = Ui.CHIP, radiusDp = 8f)
        Ui.focusCard(updateSettings, normal = Ui.CHIP, radiusDp = 8f)
        findViewById<TextView>(R.id.versionText).text = "StreamLink ${BuildConfig.VERSION_NAME}" +
            if (BuildConfig.CHANNEL == "main") "" else " (${BuildConfig.CHANNEL})"
    }

    // ---- App updates (see Updater.kt) ----

    private fun renderUpdate() {
        val phase = UpdateState.phase
        val canInstall = Updater.canInstall(this)
        when (phase) {
            UpdateState.Phase.READY -> {
                updateBanner.text = "Update ready · build ${UpdateState.build} · press OK"
                updateBanner.visibility = View.VISIBLE
                if (currentFocus == null) updateBanner.requestFocus()
            }
            UpdateState.Phase.DOWNLOADING -> {
                val p = UpdateState.percent
                updateBanner.text = if (p in 0..99) "Updating… $p%" else "Updating…"
                updateBanner.visibility = View.VISIBLE
            }
            UpdateState.Phase.INSTALLING -> {
                updateBanner.text = "Installing update (build ${UpdateState.build})…"
                updateBanner.visibility = View.VISIBLE
            }
            UpdateState.Phase.NONE -> updateBanner.visibility = View.GONE
        }
        // Only a banner you can press needs to be focusable.
        updateBanner.isFocusable = phase == UpdateState.Phase.READY
        updateBanner.isClickable = phase == UpdateState.Phase.READY
        if (phase == UpdateState.Phase.READY) Ui.focusCard(updateBanner, normal = Ui.NEUTRAL_BADGE, radiusDp = 14f)
        else { updateBanner.background = Ui.rounded(this, Ui.SURFACE, 14f); updateBanner.foreground = null }
        val needAllow = phase == UpdateState.Phase.READY && !canInstall
        updatePerm.visibility = if (needAllow) View.VISIBLE else View.GONE
        updateSettings.visibility = if (needAllow) View.VISIBLE else View.GONE
        val msg = UpdateState.message
        updateInfo.text = msg
        updateInfo.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
        checkUpdate.text = if (UpdateState.checking) "Checking…" else "Check for updates"
    }

    /** The user pressed OK on the banner (or the phone's button): install, unless installs are not allowed yet. */
    private fun installUpdate() {
        if (UpdateState.phase != UpdateState.Phase.READY) return
        if (!Updater.canInstall(this)) {
            Toast.makeText(this, "First allow StreamLink to install apps (see above)", Toast.LENGTH_LONG).show()
            renderUpdate()
            updateSettings.requestFocus()
            return
        }
        Updater.install(this)
    }

    private fun openInstallSettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            // Not every TV has this screen; the text above says where to find the setting by hand.
            Toast.makeText(this, "This TV has no shortcut: use Settings → Apps → Security & restrictions → Unknown sources", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderPrefetchToggle() {
        prefetchToggle.text = "Head start while browsing: " + if (PrefetchSettings.enabled(this)) "On" else "Off"
    }

    /** The switch (TV row or phone page): looks up a focused event's stream a little early. */
    private fun setPrefetch(on: Boolean) {
        PrefetchSettings.set(this, on)
        if (!on) prefetcher.cancel()
        renderPrefetchToggle()
    }

    private fun command(c: String, v: String?) {
        if (c == "prefetch") {
            if (v == "on" || v == "off") setPrefetch(v == "on")
            return
        }
        if (c == "update") {
            // Only from the home screen: never pop up the install dialog over a stream.
            if (resumed && Session.mode == "idle") installUpdate()
            return
        }
        val h = Session.commandHandler
        if (h != null) {
            h(c, v)
        } else if (c == "site") {
            Session.current?.pageUrl?.let { startActivity(SitePlayerActivity.intent(this, it)) }
        }
    }

    private fun setStatus(text: String) {
        AppStatus.text = text
        statusView.text = text
    }

    private fun lastStatusForScreen(): String {
        val t = AppStatus.text
        return if (t == "Waiting for a link") "Scan the code with your phone and send a link." else t
    }

    private fun renderRecents() {
        recentList.removeAllViews()
        val items = Recents.list(this)
        recentLabel.text = if (items.isEmpty()) "" else "Recent links"
        recentLabel.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        for (url in items) {
            val uri = Uri.parse(url)
            val label = ((uri.host ?: url).removePrefix("www.") + (uri.path?.takeIf { it.length > 1 } ?: ""))
            val item = Ui.text(this, label, 13f, Ui.TEXT).apply {
                maxLines = 1
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = dp(260)
                Ui.focusCard(this, radiusDp = 9f)
                setPadding(dp(13), dp(9), dp(13), dp(9))
                isFocusable = true
                isClickable = true
                setOnClickListener { play(url) }
                setOnLongClickListener {
                    Recents.remove(this@MainActivity, url)
                    renderRecents()
                    focusFirst()
                    Toast.makeText(this@MainActivity, "Removed", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(10)
            recentList.addView(item, lp)
        }
    }

    private companion object {
        const val HOME_TICK_MS = 60_000L
        const val SCORE_TICK_MS = 5_000L
        /** Lists older than this are re-read in the background while the home screen is shown. */
        const val STALE_MS = 10 * 60_000L
        const val ALWAYS_ON_TAG = "always-on"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
