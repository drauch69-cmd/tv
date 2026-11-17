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
    private lateinit var server: LinkServer
    private lateinit var statusView: TextView
    private lateinit var recentList: LinearLayout
    private lateinit var recentLabel: TextView
    private lateinit var listRow: LinearLayout
    private lateinit var listLabel: TextView
    private lateinit var listScroll: HorizontalScrollView
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
    private lateinit var qrPanel: View
    private lateinit var sendLink: TextView
    private val homeTick = object : Runnable {
        override fun run() {
            if (resumed) {
                refreshStale()
                renderLists() // statuses change with the clock: LIVE badges, "starts in"
            }
            main.postDelayed(this, HOME_TICK_MS)
        }
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
        listRow = findViewById(R.id.listRow)
        listLabel = findViewById(R.id.listLabel)
        listScroll = findViewById(R.id.listScroll)
        updateBanner = findViewById(R.id.updateBanner)
        updatePerm = findViewById(R.id.updatePerm)
        updateSettings = findViewById(R.id.updateSettings)
        checkUpdate = findViewById(R.id.checkUpdate)
        updateInfo = findViewById(R.id.updateInfo)
        liveRow = findViewById(R.id.liveRow)
        liveSub = findViewById(R.id.liveSub)
        qrPanel = findViewById(R.id.qrPanel)
        sendLink = findViewById(R.id.sendLink)
        styleHome()
        updateBanner.setOnClickListener { installUpdate() }
        updateSettings.setOnClickListener { openInstallSettings() }
        checkUpdate.setOnClickListener { Updater.check(this, manual = true) }
        sendLink.setOnClickListener { showQr(true) }
        findViewById<View>(R.id.qrClose).setOnClickListener { showQr(false) }
        sniffer = StreamSniffer(this, findViewById(R.id.webHolder))
        // Reads index pages when the phone asks for a refresh and no list screen is open.
        reader = IndexReader(this, findViewById(R.id.webHolder))

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
            onListAdd = { url, name -> addList(url, name) },
            onListRemove = { id -> removeList(id) },
            onListRefresh = { id -> SavedLists.get(this, id)?.let { runOnUiThread { refreshList(id) }; true } ?: false },
            onListPlay = { id, url, start ->
                SavedLists.get(this, id)?.let { l -> runOnUiThread { playListItem(l, url, start) }; true } ?: false
            },
        )
        port = try { server.start() } catch (_: Exception) { -1 }
        showAddress()
        renderLists()
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
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        renderUpdate() // the user may just have allowed installs in Settings
        showAddress()
        renderLists()
        refreshStale()
        if (Session.mode == "idle") statusView.text = lastStatusForScreen()
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
        netCallback?.let {
            try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        server.stop()
        sniffer.cancel()
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
        if (Session.mode == "site" || Session.mode == "waiting") Session.commandHandler?.invoke("stop", null)
        Recents.add(this, url)
        renderRecents()
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
    private fun addList(url: String, name: String): String? {
        val l = SavedLists.add(this, url, name) ?: return "You can save up to ${SavedLists.MAX} stream lists. Remove one first."
        runOnUiThread { renderLists(); refreshList(l.id) }
        return null
    }

    private fun removeList(id: String): Boolean {
        val ok = SavedLists.remove(this, id)
        if (ok) runOnUiThread { renderLists() }
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

    /** The list tiles and the "Live now" row (both come from the saved lists). */
    private fun renderLists() {
        renderLive()
        val focusedId = listRow.focusedChild?.tag as? String
        val addFocused = listRow.focusedChild?.tag == ADD_TAG
        listRow.removeAllViews()
        val lists = SavedLists.all(this)
        val now = System.currentTimeMillis()
        var focusTarget: View? = null
        for (l in lists) {
            val c = SavedLists.cached(this, l.id)
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.focusable(this@MainActivity, radiusDp = 11f)
                setPadding(dp(15), dp(13), dp(15), dp(13))
                isFocusable = true
                isClickable = true
                tag = l.id
                setOnClickListener { startActivity(ListActivity.intent(this@MainActivity, l.id)) }
                setOnLongClickListener { confirmRemove(l); true }
            }
            Ui.growOnFocus(tile)
            tile.addView(Ui.text(this, l.name, 15f, Ui.TEXT, bold = true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            val counts = c?.let { HomeFeed.counts(it.result.items, now) }
            val line = SpannableStringBuilder()
            when {
                counts == null -> line.append(if (SavedLists.isBusy(l.id)) "Reading…" else "Not read yet")
                else -> {
                    val t = HomeFeed.countsText(counts)
                    line.append(t)
                    if (counts.live > 0) {
                        val end = t.indexOf(" live") + 5
                        line.setSpan(ForegroundColorSpan(Ui.LIVE_TEXT), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        line.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
            tile.addView(Ui.text(this, line, 11.5f, Ui.TEXT_2).apply {
                maxLines = 1
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(6)
                setPadding(0, dp(7), 0, 0)
            })
            val err = SavedLists.lastError(l.id)
            val foot = when {
                SavedLists.isBusy(l.id) && c != null -> "Reading…"
                err != null -> "Couldn't read: $err"
                c != null -> "Read ${ListText.ago(c.readAt, now)}"
                else -> ""
            }
            tile.addView(Ui.text(this, foot, 10.5f, if (err != null && !SavedLists.isBusy(l.id)) Ui.WARN else Ui.TEXT_3).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(6), 0, 0)
            })
            val lp = LinearLayout.LayoutParams(dp(210), ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(15)
            listRow.addView(tile, lp)
            if (l.id == focusedId) focusTarget = tile
        }
        // The last tile: how to add a list (opens the phone code).
        val add = Ui.text(this, "Add a list from your phone", 13f, Ui.TEXT_2).apply {
            gravity = android.view.Gravity.CENTER
            background = dashedFocusable()
            setPadding(dp(15), dp(13), dp(15), dp(13))
            isFocusable = true
            isClickable = true
            tag = ADD_TAG
            minHeight = dp(70)
            setOnClickListener { showQr(true) }
        }
        Ui.growOnFocus(add)
        val alp = LinearLayout.LayoutParams(dp(210), ViewGroup.LayoutParams.MATCH_PARENT)
        listRow.addView(add, alp)
        if (addFocused) focusTarget = add
        focusTarget?.requestFocus()
    }

    /** The "Live now" row: live events from every list, then ones starting soon. */
    private fun renderLive() {
        val focusedUrl = liveRow.focusedChild?.tag as? String
        liveRow.removeAllViews()
        val now = System.currentTimeMillis()
        val lists = SavedLists.all(this)
        val cached = lists.mapNotNull { l -> SavedLists.cached(this, l.id)?.let { l to it } }
        val rows = HomeFeed.liveRow(cached.map { it.first to it.second.result.items }, now)
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
        for (e in rows) {
            val live = e.status == ListStatus.LIVE
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.focusable(this@MainActivity, radiusDp = 12f)
                setPadding(dp(15), dp(13), dp(15), dp(13))
                isFocusable = true
                isClickable = true
                tag = e.item.url
                if (e.status == ListStatus.UPCOMING) alpha = 0.75f
                setOnClickListener { playListItem(e.list, e.item.url, e.item.startMillis) }
            }
            Ui.growOnFocus(card)
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            val badgeText = when (e.status) {
                ListStatus.LIVE -> "LIVE"
                else -> ListText.badge(e.item, now, zone, locale)
            }
            top.addView(Ui.badge(this, badgeText, live, 10f))
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

    private fun dashedFocusable(): StateListDrawable {
        val normal = GradientDrawable().apply {
            setColor(0)
            cornerRadius = dp(11).toFloat()
            setStroke(dp(2), Ui.BORDER, dp(6).toFloat(), dp(5).toFloat())
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), Ui.rounded(this@MainActivity, Ui.SURFACE_FOCUS, 11f, Ui.ACCENT, 3f))
            addState(intArrayOf(), normal)
        }
    }

    private fun focusFirst() {
        (liveRow.getChildAt(0)?.takeIf { it.isFocusable } ?: listRow.getChildAt(0) ?: sendLink).requestFocus()
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
        sendLink.background = Ui.focusable(this, normal = Ui.SURFACE, radiusDp = 9f)
        val qrIcon = GradientDrawable().apply { setColor(0); setStroke(dp(2), Ui.TEXT); cornerRadius = dp(2).toFloat(); setSize(dp(14), dp(14)) }
        sendLink.setCompoundDrawablesRelativeWithIntrinsicBounds(qrIcon, null, null, null)
        val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.ACCENT); setSize(dp(12), dp(12)) }
        findViewById<TextView>(R.id.brand).setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
        findViewById<View>(R.id.qrCard).background = Ui.rounded(this, Ui.SURFACE, 16f, Ui.BORDER, 1f)
        checkUpdate.background = Ui.focusable(this, normal = Ui.CHIP, radiusDp = 8f)
        findViewById<View>(R.id.qrClose).background = Ui.focusable(this, normal = Ui.CHIP, radiusDp = 8f)
        updateSettings.background = Ui.focusable(this, normal = Ui.CHIP, radiusDp = 8f)
        findViewById<TextView>(R.id.versionText).text = "StreamLink ${BuildConfig.VERSION_NAME}" +
            if (BuildConfig.CHANNEL == "test") " (test)" else ""
    }

    private fun confirmRemove(l: SavedList) {
        val d = AlertDialog.Builder(this)
            .setTitle("Remove “${l.name}”?")
            .setMessage("This removes the saved stream list. The website is not affected.")
            .setPositiveButton("Remove") { _, _ ->
                SavedLists.remove(this, l.id)
                renderLists()
                Toast.makeText(this, "Removed", Toast.LENGTH_SHORT).show()
                focusFirst()
            }
            .setNegativeButton("Cancel", null)
            .create()
        d.show()
        d.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
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
        updateBanner.background = if (phase == UpdateState.Phase.READY) Ui.focusable(this, normal = Ui.NEUTRAL_BADGE, radiusDp = 14f)
        else Ui.rounded(this, Ui.SURFACE, 14f)
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

    private fun command(c: String, v: String?) {
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
                background = Ui.focusable(this@MainActivity, radiusDp = 9f)
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
        /** Lists older than this are re-read in the background while the home screen is shown. */
        const val STALE_MS = 10 * 60_000L
        const val ADD_TAG = "add-list"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
