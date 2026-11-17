package com.streamlink.tv

import android.app.Activity
import android.app.AlertDialog
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.Uri
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

/** The link screen: QR code for the phone, status, and recent links. Also runs the phone page server. */
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
        (listRow.getChildAt(0) ?: recentList.getChildAt(0))?.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        showAddress()
        renderLists()
        if (Session.mode == "idle") statusView.text = lastStatusForScreen()
    }

    override fun onDestroy() {
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

    private fun renderLists() {
        val focusedId = listRow.focusedChild?.tag as? String
        listRow.removeAllViews()
        val lists = SavedLists.all(this)
        listScroll.visibility = if (lists.isEmpty()) View.GONE else View.VISIBLE
        listLabel.text = if (lists.isEmpty()) "Stream lists: on the phone page, paste an index page and tap “Add as stream list”."
        else "Stream lists  (hold OK to remove)"
        val now = System.currentTimeMillis()
        var focusTarget: View? = null
        for (l in lists) {
            val c = SavedLists.cached(this, l.id)
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.recent_item)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                isFocusable = true
                isClickable = true
                tag = l.id
                setOnClickListener { startActivity(ListActivity.intent(this@MainActivity, l.id)) }
                setOnLongClickListener { confirmRemove(l); true }
            }
            tile.addView(TextView(this).apply {
                text = l.name
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            tile.addView(TextView(this).apply {
                text = if (c == null) "Not read yet" else "Updated ${ListText.ago(c.readAt, now)}"
                maxLines = 1
                setTextColor(0xFFB8C4E0.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
            val lp = LinearLayout.LayoutParams(dp(230), dp(84))
            lp.marginEnd = dp(12)
            listRow.addView(tile, lp)
            if (l.id == focusedId) focusTarget = tile
        }
        focusTarget?.requestFocus()
    }

    private fun confirmRemove(l: SavedList) {
        val d = AlertDialog.Builder(this)
            .setTitle("Remove “${l.name}”?")
            .setMessage("This removes the saved stream list. The website is not affected.")
            .setPositiveButton("Remove") { _, _ ->
                SavedLists.remove(this, l.id)
                renderLists()
                Toast.makeText(this, "Removed", Toast.LENGTH_SHORT).show()
                (listRow.getChildAt(0) ?: recentList.getChildAt(0))?.requestFocus()
            }
            .setNegativeButton("Cancel", null)
            .create()
        d.show()
        d.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
    }

    private fun command(c: String, v: String?) {
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
        recentLabel.text = if (items.isEmpty()) "" else "Recent  (hold OK to remove)"
        for (url in items) {
            val uri = Uri.parse(url)
            val item = TextView(this).apply {
                text = (uri.host ?: url) + (uri.path ?: "")
                maxLines = 1
                setSingleLine(true)
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setBackgroundResource(R.drawable.recent_item)
                val pad = dp(14)
                setPadding(pad, pad, pad, pad)
                isFocusable = true
                isClickable = true
                setOnClickListener { play(url) }
                setOnLongClickListener {
                    Recents.remove(this@MainActivity, url)
                    renderRecents()
                    recentList.getChildAt(0)?.requestFocus() ?: listRow.getChildAt(0)?.requestFocus()
                    Toast.makeText(this@MainActivity, "Removed", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            recentList.addView(item, lp)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
