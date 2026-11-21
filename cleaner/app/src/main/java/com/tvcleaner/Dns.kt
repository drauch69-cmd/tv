package com.tvcleaner

import android.content.Context
import android.net.ConnectivityManager
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** A DNS choice: [host] is the Private DNS (DNS-over-TLS) server name; null for Off / Automatic. */
data class DnsChoice(val name: String, val about: String, val mode: String, val host: String?)

/**
 * Android's "Private DNS" setting, which Google TV hides from its menus. TV Cleaner sets it through
 * Shizuku. Only server names work here (DNS-over-TLS), not plain addresses like 8.8.8.8.
 */
object Dns {
    const val OFF = "off"
    const val AUTO = "opportunistic"
    const val HOST = "hostname"

    val PRESETS = listOf(
        DnsChoice("AdGuard", "Blocks most ads and trackers.", HOST, "dns.adguard-dns.com"),
        DnsChoice("Cloudflare", "Fast, no blocking.", HOST, "one.one.one.one"),
        DnsChoice("Google", "Fast, no blocking.", HOST, "dns.google"),
        DnsChoice("Quad9", "Blocks known scam and malware sites.", HOST, "dns.quad9.net"),
        DnsChoice("Automatic", "Android's default: uses your router's DNS, encrypted when it can.", AUTO, null),
        DnsChoice("Off", "Plain DNS from your router.", OFF, null),
    )

    private val LABEL = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")

    /** The cleaned-up server name, or null when [raw] isn't a usable one (addresses and URLs are refused). */
    fun cleanHost(raw: String): String? {
        val h = raw.trim().lowercase().removeSuffix(".")
        if (h.length !in 4..253 || !h.contains('.')) return null
        val labels = h.split('.')
        if (labels.any { !LABEL.matches(it) }) return null
        // An IP address isn't a server name; Private DNS needs a name.
        if (labels.all { it.all(Char::isDigit) }) return null
        if (labels.last().all(Char::isDigit)) return null
        return h
    }

    data class Current(val mode: String, val host: String?)

    /** Unset means Android's default, Automatic. */
    fun parseMode(raw: String?): String = when (raw?.trim()) {
        OFF -> OFF
        HOST -> HOST
        else -> AUTO
    }

    fun current(): Current {
        val mode = Shell.run(SETTINGS, "get", "global", "private_dns_mode")
        val host = Shell.run(SETTINGS, "get", "global", "private_dns_specifier")
        val h = host.out.trim().takeIf { host.ok && it.isNotEmpty() && it != "null" }
        return Current(parseMode(if (mode.ok) mode.out else null), h)
    }

    /** Sets the mode (and server name for [HOST]). Returns the shell result of the last step. */
    fun set(mode: String, host: String?): Shell.Result {
        if (mode == HOST) {
            val h = host?.let(::cleanHost) ?: return Shell.Result(-1, "Refused: not a server name")
            val r = Shell.run(SETTINGS, "put", "global", "private_dns_specifier", h)
            if (!r.ok) return r
        }
        if (mode !in setOf(OFF, AUTO, HOST)) return Shell.Result(-1, "Refused: unknown mode")
        return Shell.run(SETTINGS, "put", "global", "private_dns_mode", mode)
    }

    /** Puts back exactly what was there before. */
    fun restore(old: Current): Shell.Result {
        if (old.host != null) Shell.run(SETTINGS, "put", "global", "private_dns_specifier", old.host)
        return Shell.run(SETTINGS, "put", "global", "private_dns_mode", old.mode)
    }

    /** Null when [host] answers as an encrypted DNS server (TLS on port 853); otherwise what's wrong. */
    fun probe(host: String): String? = try {
        (SSLSocketFactory.getDefault().createSocket() as SSLSocket).use { s ->
            s.connect(InetSocketAddress(host, 853), 6000)
            s.soTimeout = 6000
            s.startHandshake()
        }
        null
    } catch (e: Exception) {
        "$host didn't answer as a Private DNS server (${e.javaClass.simpleName})."
    }

    /**
     * After a change: true once the TV is online through the new setting. For a server name it waits
     * for Android to confirm it's using that server, then loads a page.
     */
    fun works(ctx: Context, mode: String, host: String?, waitMs: Long = 15000): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val end = System.currentTimeMillis() + waitMs
        var dnsOk = mode != HOST
        while (System.currentTimeMillis() < end) {
            if (!dnsOk) {
                val lp = cm?.let { c -> c.activeNetwork?.let { c.getLinkProperties(it) } }
                dnsOk = lp != null && lp.isPrivateDnsActive && lp.privateDnsServerName.equals(host, ignoreCase = true)
            }
            if (dnsOk && online()) return true
            Thread.sleep(1500)
        }
        return false
    }

    private fun online(): Boolean = CHECKS.any { url ->
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 4000
            c.readTimeout = 4000
            c.instanceFollowRedirects = false
            try {
                c.responseCode in 200..399
            } finally {
                c.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }

    private val CHECKS = listOf("https://www.gstatic.com/generate_204", "https://cloudflare.com/cdn-cgi/trace")

    /** The friendly name for what's set now. */
    fun describe(c: Current): String = when (c.mode) {
        OFF -> "Off (plain DNS from your router)"
        HOST -> PRESETS.firstOrNull { it.host == c.host }?.let { "${it.name} (${c.host})" } ?: (c.host ?: "Custom")
        else -> "Automatic"
    }

    private const val SETTINGS = "/system/bin/settings"
}
