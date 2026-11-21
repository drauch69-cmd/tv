package com.streamlink.tv

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Quick list reads (user, 2026-11-20): download only the page's own HTML (no scripts, ads or trackers), lay it out
 * with its style sheets in the hidden browser and run the same extractor. Used per site only after a test read
 * showed it finds the same events as the full browser read. Pure rules here are unit tested ([QuickLogic]).
 */
object QuickLogic {
    enum class Mode {
        /** The quick way is known to work for this site: quick first, the full browser read if it finds nothing. */
        QUICK,
        /** Unknown (or due a re-check): quick read kept aside, then the full read; compared; the full result is used. */
        TEST,
        /** The site needs the browser. */
        FULL,
    }

    data class Verdict(val quick: Boolean, val at: Long)

    /** A working quick way is re-checked against the full read this often (sites change). */
    const val QUICK_RECHECK_MS = 24 * 60 * 60_000L
    /** A site that needed the browser is tested again after this long. */
    const val BROWSER_RECHECK_MS = 7 * 24 * 60 * 60_000L

    fun mode(v: Verdict?, now: Long): Mode = when {
        v == null -> Mode.TEST
        v.quick -> if (now - v.at < QUICK_RECHECK_MS) Mode.QUICK else Mode.TEST
        else -> if (now - v.at < BROWSER_RECHECK_MS) Mode.FULL else Mode.TEST
    }

    fun encode(v: Verdict) = (if (v.quick) "q:" else "b:") + v.at

    fun decode(s: String?): Verdict? {
        if (s == null || s.length < 3 || s[1] != ':') return null
        val at = s.substring(2).toLongOrNull() ?: return null
        return when (s[0]) { 'q' -> Verdict(true, at); 'b' -> Verdict(false, at); else -> null }
    }

    private val SCRIPT = Regex("<script\\b[^>]*>.*?</script\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val SCRIPT_OPEN = Regex("<script\\b[^>]*/?>", RegexOption.IGNORE_CASE)
    private val REFRESH = Regex("<meta\\b[^>]*http-equiv\\s*=\\s*[\"']?refresh[^>]*>", RegexOption.IGNORE_CASE)

    /** The page without its scripts or a meta refresh, so nothing on it runs, redirects or loads ads. */
    fun stripScripts(html: String): String = html.replace(SCRIPT, "").replace(SCRIPT_OPEN, "").replace(REFRESH, "")

    /**
     * Does the quick read give the same events as the full one? At least 90% of the full read's events must appear;
     * events that are not ESPN games ([espn] false) must also have the same start time and, where the full read saw
     * a live marker, the same marker (the site's own time and Live drive those); and the quick read may not add many
     * extra rows (junk). An empty full read proves nothing (false).
     */
    fun matches(full: List<ListItem>, quick: List<ListItem>, espn: (ListItem) -> Boolean = { false }): Boolean {
        if (full.isEmpty() || quick.isEmpty()) return false
        if (quick.size > full.size + maxOf(2, full.size / 2)) return false
        val byUrl = HashMap<String, ListItem>()
        for (q in quick) byUrl.putIfAbsent(q.url, q)
        val agree = full.count { f ->
            val q = byUrl[f.url]
            // A game ESPN knows: Live/Final and timing come from ESPN, so only the link has to be there.
            q != null && (espn(f) || (q.startMillis == f.startMillis && (!f.liveHint || q.liveHint)))
        }
        return agree * 10 >= full.size * 9
    }
}

/** The saved verdict per site (its own small settings file). */
object QuickReads {
    private const val PREFS = "quick_reads"

    private fun key(host: String?) = (host ?: "").lowercase().removePrefix("www.")

    fun verdict(ctx: Context, host: String?): QuickLogic.Verdict? =
        QuickLogic.decode(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(host), null))

    /** [at] is when the verdict counts from; an earlier time makes the re-check come sooner. */
    fun set(ctx: Context, host: String?, quick: Boolean, at: Long = System.currentTimeMillis()) {
        if (key(host).isEmpty()) return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(key(host), QuickLogic.encode(QuickLogic.Verdict(quick, at))).apply()
    }
}

/** Downloads a page's HTML the way the hidden browser would ask for it (same browser name, the site's cookies). */
object QuickFetch {
    private const val MAX_BYTES = 3 * 1024 * 1024

    class Page(val url: String, val html: String)

    /** Call off the main thread. Throws with a short reason on anything but an HTML page. */
    fun download(ctx: Context, address: String): Page {
        val agent = try { android.webkit.WebSettings.getDefaultUserAgent(ctx).replace("; wv", "") } catch (_: Exception) { "Mozilla/5.0" }
        var url = address
        repeat(5) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 6000
                c.readTimeout = 6000
                c.setRequestProperty("User-Agent", agent)
                c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                try { android.webkit.CookieManager.getInstance().getCookie(url) } catch (_: Exception) { null }
                    ?.let { c.setRequestProperty("Cookie", it) }
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw IOException("redirect without an address")
                    url = URL(URL(url), loc).toString()
                    return@repeat
                }
                if (code != 200) throw IOException("HTTP $code")
                val type = (c.contentType ?: "").lowercase()
                if (!type.contains("html")) throw IOException("not a web page ($type)")
                val charset = Regex("charset=([\\w-]+)").find(type)?.groupValues?.get(1) ?: "UTF-8"
                val buf = ByteArrayOutputStream()
                c.inputStream.use { ins ->
                    val tmp = ByteArray(16 * 1024)
                    while (true) {
                        val n = ins.read(tmp)
                        if (n < 0) break
                        buf.write(tmp, 0, n)
                        if (buf.size() > MAX_BYTES) throw IOException("page too large")
                    }
                }
                val html = try { buf.toString(charset) } catch (_: Exception) { buf.toString("UTF-8") }
                return Page(url, html)
            } finally {
                c.disconnect()
            }
        }
        throw IOException("too many redirects")
    }
}
