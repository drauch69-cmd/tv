package com.streamlink.tv

import android.webkit.WebView
import java.net.InetAddress

/**
 * Report lines for a server that refused Chrome's network engine (ERR_SSL_PROTOCOL_ERROR while the page's own
 * browser reached it, crackstreams ESPN, build 287): which engine versions the TV has (once per app run) and which
 * addresses the TV's own lookup gives for that server (once per server). Blocking: call off the main thread.
 */
object NetDiag {
    @Volatile private var versionsLogged = false
    private val hosts = HashSet<String>()

    fun once(host: String?, log: (String) -> Unit) {
        if (!versionsLogged) {
            versionsLogged = true
            log("Network versions: Chrome's engine ${chromeVersion()}; the TV's browser part ${webViewVersion()}")
        }
        val h = host ?: return
        synchronized(hosts) { if (!hosts.add(h)) return }
        val addrs = try {
            InetAddress.getAllByName(h).joinToString(", ") { it.hostAddress ?: "?" }
        } catch (e: Exception) {
            "lookup failed (${e.javaClass.simpleName})"
        }
        log("$h looks up as: $addrs")
    }

    private fun chromeVersion(): String = try {
        ChromeNet.engine?.versionString ?: "not running"
    } catch (_: Throwable) { "unknown" }

    private fun webViewVersion(): String = try {
        val p = WebView.getCurrentWebViewPackage()
        if (p == null) "unknown" else "${p.packageName} ${p.versionName}"
    } catch (_: Throwable) { "unknown" }
}
