package com.streamlink.tv

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Playlists already downloaded while checking streams. The player takes them from here instead
 * of downloading again, which matters for links that only work once.
 */
object PrefetchCache {
    /** A saved live playlist goes stale fast: older than this, download it fresh instead. */
    private const val MAX_AGE_MS = 15_000L

    private class Entry(val bytes: ByteArray, val finalUrl: String, val at: Long)

    private val map = ConcurrentHashMap<String, Entry>()

    fun put(url: String, bytes: ByteArray, finalUrl: String) {
        if (map.size > 20) map.clear()
        map[url] = Entry(bytes, finalUrl, System.currentTimeMillis())
    }

    fun take(url: String): Pair<ByteArray, String>? {
        val e = map.remove(url) ?: return null
        if (System.currentTimeMillis() - e.at > MAX_AGE_MS) return null
        return Pair(e.bytes, e.finalUrl)
    }

    fun clear() = map.clear()
}

/** Checks each found stream really is a playlist, then ranks them best first. */
object Verifier {

    private class Resp(val code: Int, val body: ByteArray, val finalUrl: String)

    private class Checked(
        val cand: StreamCandidate,
        val ok: Boolean,
        val master: Boolean,
        val dash: Boolean,
        val live: Boolean,
        val adLike: Boolean,
        val workingReferer: String?,
        val variants: List<String>,
        /** Why it failed, when it did (what the server or the page answered); "" when it passed. */
        val note: String = "",
    ) {
        val score: Int
            get() = (if (ok) 10 else 0) + (if (master) 3 else 0) + (if (live) 2 else 0) + (if (adLike) -10 else 0)
    }

    private const val TOTAL_LIMIT_MS = 10_000L

    private val adWords = listOf("/ads/", "/ad/", "vast", "preroll", "pre-roll", "advert", "doubleclick", "imasdk")

    fun rank(cands: List<StreamCandidate>, log: (String) -> Unit = { Report.log(it) }): List<Source> {
        // Check all candidates at once, with a hard overall time limit, so a slow site can't keep
        // the TV on "Finding the video…" for minutes.
        val pool = Executors.newFixedThreadPool(cands.size.coerceIn(1, 12))
        val futures = try {
            pool.invokeAll(cands.map { c -> Callable { check(c, log) } }, TOTAL_LIMIT_MS, TimeUnit.MILLISECONDS)
        } finally {
            pool.shutdownNow()
        }
        val checked = cands.mapIndexed { i, c ->
            val f = futures[i]
            try {
                if (f.isCancelled) {
                    log("✗ ${Report.short(c.url)}: too slow to answer")
                    unchecked(c, "too slow to answer")
                } else {
                    f.get()
                }
            } catch (e: Exception) {
                log("✗ ${Report.short(c.url)}: ${(e.cause ?: e).javaClass.simpleName}")
                unchecked(c, "checking failed")
            }
        }

        // Quality levels inside an adaptive playlist aren't separate sources.
        val children = HashSet<String>()
        checked.filter { it.ok && it.master }.forEach { m ->
            m.variants.forEach { v -> children.add(v); children.add(v.substringBefore('?')) }
        }
        var list = checked.filter { it.cand.url !in children && it.cand.url.substringBefore('?') !in children }
        if (list.isEmpty()) list = checked

        if (list.any { it.ok && !it.adLike }) list = list.filter { !it.adLike }

        // Best first; but the stream of the page's own (default) link stays first when it works.
        val ranked = LinkOrder.defaultFirst(list.sortedByDescending { it.score }) { it.cand.isDefault && it.ok && !it.adLike }.take(8)
        val labels = LinkOrder.uniqueLabels(ranked.map { c ->
            val kind = when {
                c.dash -> "DASH"
                c.master -> "adaptive"
                else -> "single quality"
            }
            LinkOrder.sourceLabel(c.cand.linkLabel, Report.host(c.cand.url), kind, c.ok)
        })
        return ranked.mapIndexed { i, c ->
            val refs = if (c.workingReferer != null) {
                listOf(c.workingReferer) + c.cand.referers.filter { it != c.workingReferer }
            } else c.cand.referers
            Source(c.cand.url, c.cand.headers, refs, labels[i], c.cand.isDefault, verified = c.ok, note = if (c.ok) "" else c.note)
        }
    }

    private fun check(c: StreamCandidate, log: (String) -> Unit): Checked {
        // Up to 4 real referers, then the no-referer attempt last.
        val refs = (c.referers.filter { it.isNotEmpty() }.take(4) + "").distinct()
        var note = "no response"
        for (ref in refs) {
            val r = fetch(c.url, c.headers, ref, log) ?: continue
            if (r.code !in 200..299) {
                note = "server said ${r.code}"
                continue
            }
            val text = String(r.body, Charsets.UTF_8)
            val t = text.trimStart('\uFEFF', ' ', '\r', '\n', '\t')
            val isHls = t.startsWith("#EXTM3U")
            val isDash = !isHls && text.contains("<MPD")
            if (!isHls && !isDash) {
                // What came back instead, for the report: size and the first characters (non-text as ·).
                val head = r.body.take(24).joinToString("") { b ->
                    val ch = (b.toInt() and 0xFF).toChar()
                    if (ch in ' '..'~') ch.toString() else "·"
                }
                val what = "${r.body.size} bytes, starts \"$head\"" + if (r.finalUrl != c.url) ", after a redirect" else ""
                note = (if (t.startsWith("<")) "got a web page instead of video" else "not a playlist") + " ($what)"
                continue
            }
            PrefetchCache.put(c.url, r.body, r.finalUrl)
            val master = isHls && text.contains("#EXT-X-STREAM-INF")
            val live = isDash || master || !text.contains("#EXT-X-ENDLIST")
            val duration = if (isHls && !master) {
                Regex("#EXTINF:\\s*([0-9.]+)").findAll(text).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
            } else 0.0
            val adLike = looksLikeAd(c.url) || (!live && duration < 90.0)
            val variants = if (master) variantUrls(text, r.finalUrl) else emptyList()
            val kind = when {
                isDash -> "DASH"
                master -> "adaptive"
                else -> "single quality"
            }
            val refText = if (ref.isEmpty()) "none" else Report.short(ref)
            log("✓ ${Report.short(c.url)}: $kind, ${if (live) "live" else "recorded"} (referer: $refText)" +
                if (adLike) " — looks like an ad" else "")
            return Checked(c, true, master, isDash, live, adLike, ref, variants)
        }
        log("✗ ${Report.short(c.url)}: $note")
        return Checked(c, false, false, false, false, looksLikeAd(c.url), null, emptyList(), note)
    }

    private fun unchecked(c: StreamCandidate, note: String) =
        Checked(c, false, false, false, false, looksLikeAd(c.url), null, emptyList(), note)

    private fun looksLikeAd(url: String): Boolean {
        val u = url.lowercase()
        return adWords.any { u.contains(it) }
    }

    private fun variantUrls(text: String, base: String): List<String> {
        val out = ArrayList<String>()
        val lines = text.lines()
        for (i in lines.indices) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
            for (j in i + 1 until lines.size) {
                val l = lines[j].trim()
                if (l.isEmpty() || l.startsWith("#")) continue
                try { out.add(URI(base).resolve(l).toString()) } catch (_: Exception) { out.add(l) }
                break
            }
        }
        return out
    }

    private fun fetch(url: String, headers: Map<String, String>, referer: String, log: (String) -> Unit): Resp? {
        var conn: HttpURLConnection? = null
        return try {
            conn = ChromeNet.open(url)
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.instanceFollowRedirects = true
            for ((k, v) in headers) {
                if (k.equals("Referer", true) || k.equals("Origin", true) || k.equals("X-Requested-With", true)) continue
                conn.setRequestProperty(k, v)
            }
            if (referer.isNotEmpty()) {
                conn.setRequestProperty("Referer", referer)
                Headers.originFor(referer, Headers.capturedOrigin(headers))?.let { conn.setRequestProperty("Origin", it) }
            }
            val code = conn.responseCode
            val stream: InputStream? = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { readUpTo(it, 512 * 1024) } ?: ByteArray(0)
            Resp(code, body, conn.url.toString())
        } catch (e: Exception) {
            log("  ${Report.short(url)}: ${e.javaClass.simpleName}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun readUpTo(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
