package com.streamlink.tv

import android.app.Activity
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
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
    private var port = -1
    private var shownLink: String? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusView = findViewById(R.id.status)
        recentList = findViewById(R.id.recentList)
        recentLabel = findViewById(R.id.recentLabel)
        sniffer = StreamSniffer(this, findViewById(R.id.webHolder))

        val pages = mapOf("/" to asset("phone.html"), "/shortcut" to asset("shortcut.html"))
        server = LinkServer(
            pages = pages,
            onLink = { url -> runOnUiThread { play(url) } },
            onCommand = { c, v -> runOnUiThread { command(c, v) } },
            status = { AppStatus.text },
            state = { Session.stateJson() },
            report = { Report.text() },
        )
        port = try { server.start() } catch (_: Exception) { -1 }
        showAddress()
        renderRecents()
        watchNetwork()
    }

    override fun onResume() {
        super.onResume()
        showAddress()
        if (Session.mode == "idle") statusView.text = lastStatusForScreen()
    }

    override fun onDestroy() {
        netCallback?.let {
            try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        server.stop()
        sniffer.cancel()
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
        if (Session.mode == "site") Session.commandHandler?.invoke("stop", null)
        Recents.add(this, url)
        renderRecents()
        Report.start(url)
        val site = Uri.parse(url).host ?: url
        Session.title = site
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
                    Toast.makeText(this@MainActivity, "Removed", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            recentList.addView(item, lp)
        }
        recentList.getChildAt(0)?.requestFocus()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
