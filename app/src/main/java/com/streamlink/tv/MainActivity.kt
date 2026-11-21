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
 * The home screen: "Live games" (RedZone first while it airs, NFL games first), the sport and 24/7 tiles, recent links, and the "Send a link"
 * panel with the QR code for the phone page. Also runs the phone page server.
 */
class MainActivity : Activity() {

    private lateinit var sniffer: StreamSniffer
    private lateinit var prefetcher: Prefetcher
    private lateinit var prefetchToggle: TextView
    /** The RedZone card's item, for the head start when focus comes back to it. */
    private var redZoneItem: ListItem? = null
    private lateinit var server: LinkServer
    private lateinit var statusView: TextView
    private lateinit var recentList: LinearLayout
    private lateinit var recentLabel: TextView
    private val main = Handler(Looper.getMainLooper())
    private var port = -1
    private var shownLink: String? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var resumed = false
    /**
     * The user has pressed a key on this screen. Until then, focus is the app's own pick and moves to the featured
     * card as soon as it appears (user, 2026-11-20: home opened with the Sports row focused because the card was not
     * drawn yet).
     */
    private var userMoved = false
    private lateinit var updateBanner: TextView
    private lateinit var updatePerm: TextView
    private lateinit var updateSettings: TextView
    private lateinit var checkUpdate: TextView
    private lateinit var updateInfo: TextView
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
            if (resumed) ScoreFeed.refreshIfDue { if (!isFinishing && !isDestroyed && resumed) scoresLanded() }
            main.postDelayed(this, SCORE_TICK_MS)
        }
    }

    /**
     * Scores landed (one call per league): while the loading screen shows, the home redo starts at once (the screen
     * hides once that redo is drawn with games, see [applyHome]); otherwise the redraw waits [SCORE_SETTLE_MS] so the
     * leagues of one refresh round give one home redo, not one each (2026-11-20).
     */
    private val scoreRedraw = Runnable { if (!isFinishing && !isDestroyed && resumed) requestHome(false) }
    private fun scoresLanded() {
        if (loading != null) { requestHome(false); return }
        main.removeCallbacks(scoreRedraw)
        main.postDelayed(scoreRedraw, SCORE_SETTLE_MS)
    }

    // ---- Home data: worked out on one background thread, drawn on the main thread ----
    private val homeCalc = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "home-calc").apply { isDaemon = true } }
    /** Bumped on every request; a result that is not from the latest request is dropped. Main thread only. */
    @Volatile private var homeGen = 0
    /** The first home calculations since start are logged, and any slow one after that. */
    private var homeTimed = 0
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

    /** [computeHome] with a report line for the first 3 calculations and any one over 1.5 s. */
    private fun timedHome(ctx: android.content.Context, games: List<Game>, now: Long): HomeModel {
        val t0 = System.currentTimeMillis()
        val m = computeHome(ctx, games, now)
        val ms = System.currentTimeMillis() - t0
        val n = synchronized(this) { ++homeTimed }
        if (n <= 3 || ms > 1500) {
            val events = m.cached.sumOf { it.second.result.items.size }
            Report.log(String.format(java.util.Locale.US, "Home: worked out in %.1f s (%d games, %d list events)", ms / 1000.0, games.size, events))
        }
        return m
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
            if (gen != homeGen) return@execute // a newer request is queued: this one would be thrown away anyway
            val m = try { timedHome(app, games, now) } catch (_: Exception) { return@execute }
            main.post { if (gen == homeGen && !isFinishing && !isDestroyed) applyHome(m) }
        }
    }

    /** Same as a forced [requestHome] but worked out and drawn right now (first draw, list removed). */
    private fun renderHomeNow() {
        val games = ScoreFeed.latest()
        homeGames = games
        homeForce = true
        homeGen++
        applyHome(timedHome(this, games, System.currentTimeMillis()))
    }

    private fun applyHome(m: HomeModel) {
        val draw = homeForce || (m.look != homeLook && resumed)
        if (draw) {
            homeForce = false
            homeLook = m.look
            renderSports(m)
            ListEvents.changed() // the "Your lists" screen, if open
        }
        // The loading screen goes once the screen underneath really shows games (user, 2026-11-20): this model was
        // worked out from a non-empty scores list and is what is drawn. [applyHome] only runs for the latest request,
        // so [homeGames] is what this model was built from.
        if (loading != null && (draw || m.look == homeLook) && homeGames?.isNotEmpty() == true) hideLoading()
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
        DeadHosts.recheckOnStart()
        statusView = findViewById(R.id.status)
        recentList = findViewById(R.id.recentList)
        recentLabel = findViewById(R.id.recentLabel)
        updateBanner = findViewById(R.id.updateBanner)
        updatePerm = findViewById(R.id.updatePerm)
        updateSettings = findViewById(R.id.updateSettings)
        checkUpdate = findViewById(R.id.checkUpdate)
        updateInfo = findViewById(R.id.updateInfo)
        liveGamesHeader = findViewById(R.id.liveGamesHeader)
        liveGamesScroll = findViewById(R.id.liveGamesScroll)
        liveGamesRow = findViewById(R.id.liveGamesRow)
        sportsRow = findViewById(R.id.sportsRow)
        qrPanel = findViewById(R.id.qrPanel)
        sendLink = findViewById(R.id.sendLink)
        yourLists = findViewById(R.id.yourLists)
        prefetchToggle = findViewById(R.id.prefetchToggle)
        styleHome()
        buildFeatured()
        updateBanner.setOnClickListener { installUpdate() }
        updateSettings.setOnClickListener { openInstallSettings() }
        checkUpdate.setOnClickListener { Updater.check(this, manual = true) }
        prefetchToggle.setOnClickListener { setPrefetch(!PrefetchSettings.enabled(this)) }
        renderPrefetchToggle()
        Session.prefetchEnabled = { PrefetchSettings.enabled(applicationContext) }
        sendLink.setOnClickListener { showQr(true) }
        yourLists.setOnClickListener { startActivity(ListsActivity.intent(this)) }
        ListEvents.refresh = { id -> runOnUiThread { if (!isFinishing && !isDestroyed) refreshList(id, manual = true) } }
        findViewById<View>(R.id.qrClose).setOnClickListener { showQr(false) }
        sniffer = StreamSniffer(this, findViewById(R.id.webHolder))
        // The app's one list reader reads in this screen's hidden holder while home is the newest screen (ListReads).
        ListReads.addListener(readListener)
        prefetcher = Prefetcher(this, findViewById(R.id.prefetchHolder), "idle")

        val pages = mapOf("/" to asset("phone.html"), "/shortcut" to asset("shortcut.html"))
        server = LinkServer(
            pages = pages,
            onLink = { url -> runOnUiThread { play(url) } },
            onCommand = { c, v -> runOnUiThread { command(c, v) } },
            status = { AppStatus.text },
            state = { Session.stateJson() },
            report = { Report.text() },
            allReports = { ReportLog.all(Report.text()) },
            onReportsClear = { ReportLog.clear() },
            sample = { SampleRecorder.zip(Report.text(), BuildConfig.VERSION_NAME) },
            lists = { ListJson.listsForPhone(Ranking.sorted(SavedLists.all(this)), { SavedLists.cached(this, it) }, { SavedLists.isBusy(it) }, { SavedLists.lastError(it) }, System.currentTimeMillis()) },
            list = { id ->
                SavedLists.get(this, id)?.let { l ->
                    ListJson.listForPhone(l, SavedLists.cached(this, id), SavedLists.isBusy(id), SavedLists.lastError(id), System.currentTimeMillis())
                }
            },
            onListAdd = { url, name, sports -> addList(url, name, sports) },
            onListSport = { id, sports -> SavedLists.setSports(this, id, sports) },
            onListRank = { id, rank -> SavedLists.setRank(this, id, rank).also { ok -> if (ok) runOnUiThread { renderLists() } } },
            onListRename = { id, name -> SavedLists.rename(this, id, name).also { ok -> if (ok) runOnUiThread { renderLists() } } },
            onListRemove = { id -> removeList(id) },
            onListRefresh = { id -> SavedLists.get(this, id)?.let { runOnUiThread { refreshList(id, manual = true) }; true } ?: false },
            onListPlay = { id, url, start ->
                SavedLists.get(this, id)?.let { l -> runOnUiThread { playListItem(l, url, start) }; true } ?: false
            },
            photos = { SportPhotos.json(this) },
            photo = { lg -> SportPhotos.bytes(this, lg) },
            onPhotoUpload = { lg, bytes -> SportPhotos.save(this, lg, bytes).also { if (it == null) runOnUiThread { renderLists() } } },
            onPhotoFocus = { lg, x, y -> SportPhotos.setFocus(this, lg, x, y).also { if (it) runOnUiThread { renderLists() } } },
            onPhotoFade = { lg, w, d -> SportPhotos.setFade(this, lg, w, d).also { if (it) runOnUiThread { renderLists() } } },
            onPhotoRemove = { lg -> SportPhotos.remove(this, lg).also { runOnUiThread { renderLists() } } },
        )
        port = try { server.start() } catch (_: Exception) { -1 }
        showAddress()
        renderHomeNow()
        if (savedInstanceState == null && ScoreFeed.latest().isEmpty() && SavedLists.all(this).isNotEmpty()) showLoading()
        // Saved scores from the last run go back in at once (off the main thread), then the home screen redraws.
        homeCalc.execute {
            val back = try { ScoreFeed.restoreOnce() } catch (_: Exception) { false }
            if (back) main.post { if (!isFinishing && !isDestroyed) requestHome(false) }
        }
        renderRecents()
        watchNetwork()
        Updater.listener = { runOnUiThread { renderUpdate() } }
        renderUpdate()
        // First run (nothing saved yet): the phone code is what is needed, so show it right away.
        if (SavedLists.all(this).isEmpty() && Recents.list(this).isEmpty()) showQr(true) else focusFirst()
        main.post(updateTick)
        main.postDelayed(homeTick, HOME_TICK_MS)
    }

    // ---- Loading screen on a fresh start (user, 2026-11-20): logo and spinner (no text, user 2026-11-20) until the home rows
    // are drawn with games (see [applyHome]) or [LOADING_MAX_MS] passes, so a half-built home screen is never shown. ----
    private var loading: View? = null
    private val loadingTimeout = Runnable { hideLoading() }

    private fun showLoading() {
        val root = findViewById<ViewGroup>(android.R.id.content)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(Ui.BG)
            isClickable = true // nothing underneath takes touches while it shows
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        box.addView(ImageView(this).apply { setImageResource(R.drawable.logo_mark) }, LinearLayout.LayoutParams(dp(156), dp(132))) // bigger logo (user, 2026-11-20)
        box.addView(android.widget.ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { topMargin = dp(32) })
        root.addView(box, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        loading = box
        main.postDelayed(loadingTimeout, LOADING_MAX_MS)
    }

    private fun hideLoading() {
        val v = loading ?: return
        loading = null
        main.removeCallbacks(loadingTimeout)
        if (!userMoved && qrPanel.visibility != View.VISIBLE) focusFirst()
        v.animate().alpha(0f).setDuration(250).withEndAction { (v.parent as? ViewGroup)?.removeView(v) }.start()
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
        ListReads.attach(this, findViewById(R.id.webHolder))
        main.removeCallbacks(scoreTick)
        main.post(scoreTick)
        prefetcher.setActive(true)
        renderUpdate() // the user may just have allowed installs in Settings
        showAddress()
        renderLists()
        refreshStale()
        if (ListEvents.showQrOnHome) { ListEvents.showQrOnHome = false; showQr(true) }
        if (Session.mode == "idle") statusView.text = lastStatusForScreen()
        // Back from playing with the RedZone card still focused: no focus event comes, so start the 2 s again.
        if (liveGamesRow.focusedChild?.tag == REDZONE_TAG) redZoneItem?.let { prefetcher.focus(it) }
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

    /**
     * Left/Right at the end of a home row stays put instead of jumping to the row above or below (user, 2026-11-20).
     * Rows: the top bar, the featured card, Live games, Sports and recents.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // While the loading screen shows, keys must not move focus underneath it (Back still works).
        if (loading != null && event.keyCode != KeyEvent.KEYCODE_BACK) return true
        if (event.action == KeyEvent.ACTION_DOWN) userMoved = true
        val left = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
        if ((left || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) && qrPanel.visibility != View.VISIBLE && atRowEnd(left)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun atRowEnd(left: Boolean): Boolean {
        val f = currentFocus ?: return false
        val rows = listOf<View?>(yourLists.parent as? View, featured, liveGamesRow, sportsRow, recentList)
        var child: View = f
        var p = f.parent
        while (p is View) {
            if (p in rows) {
                val row = p as ViewGroup
                val items = (0 until row.childCount).map { row.getChildAt(it) }
                    .filter { it.visibility == View.VISIBLE && (it.isFocusable || (it is ViewGroup && it.hasFocusable())) }
                val i = items.indexOf(child)
                return i >= 0 && RowEdges.blocked(i, items.size, left)
            }
            child = p
            p = p.parent
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU && qrPanel.visibility != View.VISIBLE) {
            val lists = SavedLists.all(this)
            if (lists.isEmpty()) {
                Toast.makeText(this, "No lists yet: add one from the phone page", Toast.LENGTH_SHORT).show()
            } else {
                lists.forEach { refreshList(it.id, manual = true) }
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
        ListReads.removeListener(readListener)
        ListReads.detach(this)
        main.removeCallbacksAndMessages(null)
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
        sniffer.sniff(url, lateExtras = true) { set ->
            if (set.sources.isEmpty()) {
                // Never the site's player by itself (user, 2026-11-20): the choice screen offers it as a button.
                val why = if (set.offline) "the site's stream is offline" else "no direct stream found"
                setStatus("No working stream on $site")
                startActivity(WaitingActivity.failedIntent(this, set.pageUrl, site, why, null))
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
        runOnUiThread { renderLists(); refreshList(l.id, manual = true) }
        return null
    }

    private fun removeList(id: String): Boolean {
        val ok = SavedLists.remove(this, id)
        if (ok) runOnUiThread { renderHomeNow() }
        return ok
    }

    /** Any list read finished or changed state (ListReads): redraw the tiles and rows that depend on it. */
    private val readListener: (String, ListResult?) -> Unit = { _, _ -> if (!isFinishing && !isDestroyed) renderLists() }

    /**
     * Re-reads a list through the app's one reader (ListReads), wherever it was asked from. [manual]: the user asked
     * (phone, Menu, a new list) and it always reads; otherwise a list read a moment ago is skipped.
     */
    private fun refreshList(id: String, manual: Boolean) {
        if (SavedLists.get(this, id) == null) return
        ListReads.request(this, id, if (manual) ListReadLogic.Reason.MANUAL else ListReadLogic.Reason.TIMER)
        renderLists()
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

    /** Re-reads, in the background, lists not read for [STALE_MS] (so Live games and the tiles stay current). */
    private fun refreshStale() {
        val now = System.currentTimeMillis()
        for (l in SavedLists.all(this)) {
            val c = SavedLists.cached(this, l.id)
            if (c == null || now - c.readAt > STALE_MS) {
                if (!SavedLists.isBusy(l.id) && SavedLists.lastError(l.id) == null) refreshList(l.id, manual = false)
            }
        }
    }

    /** Redraws the home rows from the saved lists and the scores (worked out in the background). */
    private fun renderLists() { requestHome(true); ListEvents.changed() }

    /** The "Live games" row (RedZone first while it airs, then games) and the "Sports" row. */
    private fun renderSports(m: HomeModel) {
        val pick = FeaturedPick.choose(m.folders, System.currentTimeMillis(), featured.hasFocus(), featState)
        val feat = pick?.folder
        if (pick != null && pick.reason != "kept" && pick.reason != "protected" && pick.reason != "focused") {
            Report.log("Featured: ${SportsText.matchup(pick.folder.game)} (${pick.reason}, score ${pick.rating.score.toInt()}" +
                (if (pick.rating.why.isEmpty()) ")" else ", ${pick.rating.why.joinToString(", ")})"))
        }
        renderFeatured(feat)
        renderLiveGames(m.folders, m.redZone, feat?.game?.id)
        renderSportTiles(m)
    }

    // ---- Featured game: chosen by FeaturedPick (excitement, big games, stickiness), over the user's photo for its sport ----
    private val featState = FeaturedPick.shared

    private lateinit var featured: android.widget.FrameLayout
    private lateinit var featPhoto: ImageView
    private lateinit var featFade: View
    private lateinit var featSport: TextView
    private lateinit var featTeams: LinearLayout
    private lateinit var featStatus: TextView
    private lateinit var featWatch: TextView
    private var featGame: GameFolder? = null
    /** Sport + photo stamp shown now, so redraws only reload the photo when it changed. */
    private var featPhotoKey = ""

    private fun buildFeatured() {
        featured = findViewById(R.id.featured)
        featured.background = Ui.rounded(this, Ui.FADE_BOTTOM, 16f)
        featured.clipToOutline = true
        featPhoto = ImageView(this).apply { scaleType = ImageView.ScaleType.MATRIX; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        // The photo fills the whole card and the text sits over it (user, 2026-11-20); the fade runs from the card's left edge.
        featured.addView(featPhoto, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        featFade = View(this).apply { background = Ui.photoFade() }
        featured.addView(featFade,
            android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(22), dp(28), dp(22)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        top.addView(Ui.badge(this, "LIVE", true, 10.5f))
        featSport = Ui.text(this, "", 12f, Ui.TEXT_2).apply { maxLines = 1 }
        top.addView(featSport, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
        // The game clock sits up here, so Watch has the bottom row to itself (user, 2026-11-20).
        featStatus = Ui.text(this, "", 12.5f, Ui.LIVE_TEXT, bold = true).apply { maxLines = 1 }
        top.addView(featStatus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(14) })
        col.addView(top)
        featTeams = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(featTeams, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f).apply { topMargin = dp(16) })
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        featWatch = Ui.text(this, "Watch", 15f, Ui.ON_ACCENT, bold = true).apply {
            val play = android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.PathShape(android.graphics.Path().apply {
                moveTo(0f, 0f); lineTo(10f, 6f); lineTo(0f, 12f); close()
            }, 10f, 12f)).apply { paint.color = Ui.ON_ACCENT; setIntrinsicWidth(dp(10)); setIntrinsicHeight(dp(12)) }
            setCompoundDrawablesRelativeWithIntrinsicBounds(play, null, null, null)
            compoundDrawablePadding = dp(9)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(24), dp(10))
            Ui.primaryCard(this, 99f)
            isFocusable = true; isClickable = true
            setOnClickListener { watchFeatured() }
        }
        bottom.addView(featWatch)
        col.addView(bottom)
        featured.addView(col, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        featPhoto.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            // The phone page frames photos for the card's real shape.
            if (v.width > 0 && v.height > 0) SportPhotos.cardAspect = v.width.toFloat() / v.height
            placePhoto()
        }
        SportPhotos.cardAspect = featCardW().toFloat() / dp(FEAT_H)
    }

    /** The card's width: its laid-out width, else the screen width less the home page's side padding. */
    private fun featCardW(): Int = featured.width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels - dp(2 * FEAT_SIDE_PAD))

    /** One team: logo, name in a column [nameW] wide (the longer name's width), then the score right after it. */
    private fun teamLine(t: TeamSide, leading: Boolean, nameW: Int): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        row.addView(Logos.view(this, t, 30))
        val c = if (leading) Ui.TEXT else 0xFFC4CAD3.toInt()
        row.addView(teamName(t, c), LinearLayout.LayoutParams(nameW, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        row.addView(Ui.scoreText(this, ScoreText.points(t), 22f, c), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(22) })
        return row
    }

    private fun teamName(t: TeamSide, c: Int = Ui.TEXT) = Ui.text(this, t.shortName.ifBlank { t.displayName }, 20f, c, bold = true).apply {
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun renderFeatured(gf: GameFolder?) {
        val hadFocus = featured.hasFocus()
        featGame = gf
        if (gf == null) {
            featured.visibility = View.GONE
            featPhotoKey = ""
            if (hadFocus) focusFirst()
            return
        }
        val appeared = featured.visibility != View.VISIBLE
        featured.visibility = View.VISIBLE
        if (appeared && !userMoved && qrPanel.visibility != View.VISIBLE) featWatch.post { if (!userMoved) featWatch.requestFocus() }
        val g = gf.game
        featSport.text = Sports.label(g.league) + if (g.broadcast.isBlank()) "" else "  ·  ${g.broadcast.trim()}"
        val away = g.away.score.toIntOrNull() ?: 0
        val home = g.home.score.toIntOrNull() ?: 0
        featTeams.removeAllViews()
        val nameW = minOf(dp(260), maxOf(teamName(g.away).let { it.paint.measureText(it.text.toString()) }, teamName(g.home).let { it.paint.measureText(it.text.toString()) }).toInt() + dp(2))
        // Both lines sized the same way, so neither score is squeezed (user, 2026-11-20: "17" showed as "1").
        featTeams.addView(teamLine(g.away, away >= home, nameW), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        featTeams.addView(teamLine(g.home, home >= away, nameW), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        featStatus.text = (if (FeaturedPick.heating(featState.ratings[g.id])) "${FeaturedPick.FLAME} " else "") + (ScoreText.status(g) ?: "")
        featWatch.contentDescription = "Watch ${SportsText.matchup(g)}"
        loadPhoto(g.league)
    }

    private fun loadPhoto(league: String) {
        val key = league + "|" + (getSharedPreferences("sport_photos", MODE_PRIVATE).all.toString())
        if (key == featPhotoKey) return
        featPhotoKey = key
        val w = featCardW()
        val h = dp(FEAT_H)
        val app = applicationContext
        homeCalc.execute {
            val b = try { SportPhotos.bitmap(app, league, w, h) } catch (_: Exception) { null }
            main.post {
                if (isFinishing || isDestroyed || featPhotoKey != key) return@post
                featPhoto.setImageBitmap(b)
                featPhoto.tag = league
                val (fw, fd) = SportPhotos.fade(this, league)
                featFade.background = Ui.photoFade(fw, fd)
                placePhoto()
            }
        }
    }

    /** Covers the photo box with the photo, the spot the user tapped on the phone kept in view. */
    private fun placePhoto() {
        val d = featPhoto.drawable ?: return
        val lg = featPhoto.tag as? String ?: return
        val (fx, fy) = SportPhotos.focus(this, lg)
        val p = PhotoFrame.place(d.intrinsicWidth, d.intrinsicHeight, featPhoto.width, featPhoto.height, fx, fy)
        featPhoto.imageMatrix = android.graphics.Matrix().apply { setScale(p.scale, p.scale); postTranslate(p.dx, p.dy) }
    }

    /** Watch: plays the game's best stream, like picking the game. */
    private fun watchFeatured() { featGame?.let { playBest(it) } }

    /** Plays [gf]'s best stream (best-ranked list first, skipping ones marked Offline or just failed); false with none. */
    private fun playBest(gf: GameFolder): Boolean {
        val now = System.currentTimeMillis()
        val s = GamePick.best(gf.streams) { OfflineMarks.isOffline(it.item.url, now) || AutoSwitch.isDead(it.item.url, now) } ?: return false
        val l = SavedLists.get(this, s.listId) ?: return false
        playListItem(l, s.item.url, s.item.startMillis)
        return true
    }

    private fun renderLiveGames(folders: List<SportFolders>, redZone: HomeFeed.Entry?, featuredId: String?) {
        val focusedId = liveGamesRow.focusedChild?.tag as? String
        liveGamesRow.removeAllViews()
        val ratings = featState.ratings
        val games = LiveGames.sample(folders, 9, ratings.mapValues { it.value.score }).filter { it.game.id != featuredId }.take(8)
        val vis = if (games.isEmpty() && redZone == null) View.GONE else View.VISIBLE
        liveGamesHeader.visibility = vis
        liveGamesScroll.visibility = vis
        var focusTarget: View? = null
        redZoneItem = redZone?.item
        if (redZone != null) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                Ui.fadeCard(this, 14f)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                isFocusable = true
                isClickable = true
                tag = REDZONE_TAG
                setOnClickListener { playListItem(redZone.list, redZone.item.url, redZone.item.startMillis) }
                // Head start on focus, like the other list cards.
                setOnFocusChangeListener { _, has -> if (has) prefetcher.focus(redZone.item) else prefetcher.blur() }
            }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            top.addView(Ui.badge(this, "LIVE", true, 10f))
            top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            top.addView(Ui.text(this, Sports.label("nfl"), 10.5f, Ui.TEXT_3).apply { maxLines = 1 })
            card.addView(top)
            card.addView(Ui.text(this, ListText.displayTitle(redZone.item), 17f, Ui.TEXT, bold = true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(9), 0, 0)
            })
            card.addView(Ui.text(this, "Every game, live", 11.5f, Ui.LIVE_TEXT).apply {
                maxLines = 1
                setPadding(0, dp(5), 0, 0)
            })
            card.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
            card.addView(Ui.text(this, redZone.list.name, 11.5f, Ui.TEXT_2).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            val lp = LinearLayout.LayoutParams(dp(232), dp(146))
            lp.marginEnd = dp(16)
            liveGamesRow.addView(card, lp)
            if (focusedId == REDZONE_TAG) focusTarget = card
        }
        for (gf in games) {
            val g = gf.game
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                Ui.fadeCard(this, 14f)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                isFocusable = true
                isClickable = true
                tag = g.id
                contentDescription = SportsText.matchup(g)
                // OK plays the best stream; holding OK opens the game's full stream list (user, 2026-11-20).
                setOnClickListener { if (!playBest(gf)) startActivity(StreamsActivity.gameIntent(this@MainActivity, g.league, g.id)) }
                setOnLongClickListener { startActivity(StreamsActivity.gameIntent(this@MainActivity, g.league, g.id)); true }
            }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            top.addView(Ui.badge(this, "LIVE", true, 10f))
            if (FeaturedPick.heating(ratings[g.id])) top.addView(Ui.text(this, FeaturedPick.FLAME, 13f, Ui.TEXT).apply {
                contentDescription = "Heating up"
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
            top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            top.addView(Ui.text(this, Sports.label(g.league), 10.5f, Ui.TEXT_2).apply { maxLines = 1 })
            card.addView(top)
            val away = g.away.score.toIntOrNull() ?: 0
            val home = g.home.score.toIntOrNull() ?: 0
            card.addView(cardTeam(g.away, away >= home), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(11) })
            card.addView(cardTeam(g.home, home >= away), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) })
            card.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
            val foot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            foot.addView(Ui.text(this, ScoreText.status(g) ?: "", 11f, Ui.LIVE_TEXT, bold = true).apply { maxLines = 1 },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            foot.addView(Ui.streamChip(this, gf.streams.size))
            card.addView(foot)
            val lp = LinearLayout.LayoutParams(dp(232), dp(146))
            lp.marginEnd = dp(16)
            liveGamesRow.addView(card, lp)
            if (g.id == focusedId) focusTarget = card
        }
        // The focused game became the featured one: follow it to its Watch button.
        if (focusedId != null && focusTarget == null && focusedId == featuredId) focusTarget = featWatch
        // The focused game ended or left the row: move focus to the sports tiles rather than losing it.
        if (focusedId != null && focusTarget == null) focusTarget = sportsRow.getChildAt(0)
        focusTarget?.requestFocus()
    }

    /** One team line on a small live card: logo, name, points (the trailing team dimmed). */
    private fun cardTeam(t: TeamSide, leading: Boolean): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val c = if (leading) Ui.TEXT else Ui.TEXT_2
        row.addView(Logos.view(this, t, 22))
        row.addView(Ui.text(this, t.shortName.ifBlank { t.displayName }, 13.5f, c, bold = true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(9) })
        row.addView(Ui.scoreText(this, ScoreText.points(t), 14.5f, c, android.view.Gravity.END))
        return row
    }

    private fun renderSportTiles(m: HomeModel) {
        val focusedTag = sportsRow.focusedChild?.tag as? String
        sportsRow.removeAllViews()
        val now = System.currentTimeMillis()
        var focusTarget: View? = null
        fun tile(key: String, name: String, line: String, foot: String, open: () -> Unit) {
            val t = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                Ui.fadeCard(this, 14f)
                setPadding(dp(18), dp(16), dp(18), dp(16))
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
            val lp = LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(16)
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
        if (featured.visibility == View.VISIBLE) { featWatch.requestFocus(); return }
        (liveGamesRow.getChildAt(0)?.takeIf { it.isFocusable && liveGamesScroll.visibility == View.VISIBLE }
            ?: sportsRow.getChildAt(0)?.takeIf { it.isFocusable } ?: sendLink).requestFocus()
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
        // The StreamLink mark (user's logo, 2026-11-20) in blue before the name.
        val mark = getDrawable(R.drawable.logo_mark)?.apply { setBounds(0, 0, dp(26), dp(22)) }
        findViewById<TextView>(R.id.brand).setCompoundDrawablesRelative(mark ?: dot, null, null, null)
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
        /** Leagues of one score round land within about a second of each other: one redo for all of them. */
        const val SCORE_SETTLE_MS = 1_500L
        /** Lists older than this are re-read in the background while the home screen is shown. */
        const val STALE_MS = 10 * 60_000L
        const val ALWAYS_ON_TAG = "always-on"
        const val REDZONE_TAG = "redzone"
        const val LOADING_MAX_MS = 15_000L
        /** Featured card height (dp); the photo covers the whole card. Side padding of the home page (dp). */
        const val FEAT_H = 212
        const val FEAT_SIDE_PAD = 48
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
