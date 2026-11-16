package com.streamlink.tv

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var sniffer: StreamSniffer
    private lateinit var server: LinkServer
    private lateinit var statusView: TextView
    private lateinit var recentList: LinearLayout
    private lateinit var recentLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusView = findViewById(R.id.status)
        recentList = findViewById(R.id.recentList)
        recentLabel = findViewById(R.id.recentLabel)
        sniffer = StreamSniffer(this, findViewById(R.id.webHolder))

        server = LinkServer(onLink = { url -> runOnUiThread { play(url) } }, status = { AppStatus.text })
        val port = try { server.start() } catch (_: Exception) { -1 }
        showAddress(port)
        renderRecents()
    }

    override fun onResume() {
        super.onResume()
        val t = AppStatus.text
        if (t.startsWith("Playback failed")) statusView.text = t
    }

    override fun onDestroy() {
        server.stop()
        sniffer.cancel()
        super.onDestroy()
    }

    private fun showAddress(port: Int) {
        val ip = NetUtil.localIp()
        val address: TextView = findViewById(R.id.address)
        val qr: ImageView = findViewById(R.id.qr)
        if (ip == null || port < 0) {
            address.text = "Not connected to your network"
            return
        }
        val link = "http://$ip:$port"
        address.text = link
        qr.setImageBitmap(Qr.bitmap(link))
    }

    private fun play(url: String) {
        Recents.add(this, url)
        renderRecents()
        val site = Uri.parse(url).host ?: url
        setStatus("Finding the video on $site…")
        sniffer.sniff(url) { found ->
            if (found == null) {
                setStatus("Couldn't find a video on $site.")
            } else {
                setStatus("Playing $site")
                startActivity(PlayerActivity.intent(this, found))
            }
        }
    }

    private fun setStatus(text: String) {
        AppStatus.text = text
        statusView.text = text
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
