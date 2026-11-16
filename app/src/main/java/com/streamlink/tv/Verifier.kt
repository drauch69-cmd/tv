package com.streamlink.tv

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Playlists already downloaded while checking streams. The player takes them from here instead
 * of downloading again, which matters for links that only work once.
 */
object PrefetchCache {
    private val map = ConcurrentHashMap<String, Pair<ByteArray, String>>()

    fun put(url: String, bytes: ByteArray, finalUrl: String) {
        if (map.size > 20) map.clear()
        map[url] = Pair(bytes, finalUrl)
    }

    fun take(url: String): Pair<ByteArray, String>? = map.remove(url)

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
    ) {
        val score: Int
            get() = (if (ok) 10 else 0) + (if (master) 3 else 0) + (if (live) 2 else 0) + (if (adLike) -10 else 0)
    }

    private val adWords = listOf("/ads/", "/ad/", "vast", "preroll", "pre-roll", "advert", "doubleclick", "imasdk")

    fun rank(cands: List<StreamCandidate>): List<Source> {
        val checked = cands.map { c ->
            try {
                check(c)
            } catch (e: Exception) {
                Report.log("✗ ${Report.short(c.url)}: ${e.javaClass.simpleName}")
                Checked(c, false, false, false, false, looksLikeAd(c.url), null, emptyList())
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

        return list.sortedByDescending { it.score }.take(6).map { c ->
            val kind = when {
                c.dash -> "DASH"
                c.master -> "adaptive"
                else -> "single quality"
            }
            val label = Report.host(c.cand.url) + " · " + kind + (if (c.ok) "" else " · didn't respond")
            val refs = if (c.workingReferer != null) {
                listOf(c.workingReferer) + c.cand.referers.filter { it != c.workingReferer }
            } else c.cand.referers
            Source(c.cand.url, c.cand.headers, refs, label)
        }
    }

    private fun check(c: StreamCandidate): Checked {
        val refs = (c.referers.take(3) + "").distinct()
        var note = "no response"
        for (ref in refs) {
            val r = fetch(c.url, c.headers, ref) ?: continue
            if (r.code !in 200..299) {
                note = "server said ${r.code}"
                continue
            }
            val text = String(r.body, Charsets.UTF_8)
            val t = text.trimStart('﻿', ' ', '\r', '\n', '\t')
            val isHls = t.startsWith("#EXTM3U")
            val isDash = !isHls && text.contains("<MPD")
            if (!isHls && !isDash) {
                note = if (t.startsWith("<")) "got a web page instead of video" else "not a playlist"
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
            Report.log("✓ ${Report.short(c.url)}: $kind, ${if (live) "live" else "recorded"} (referer: $refText)" +
                if (adLike) " — looks like an ad" else "")
            return Checked(c, true, master, isDash, live, adLike, ref, variants)
        }
        Report.log("✗ ${Report.short(c.url)}: $note")
        return Checked(c, false, false, false, false, looksLikeAd(c.url), null, emptyList())
    }

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

    private fun fetch(url: String, headers: Map<String, String>, referer: String): Resp? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.instanceFollowRedirects = true
            for ((k, v) in headers) {
                if (k.equals("Referer", true) || k.equals("Origin", true)) continue
                conn.setRequestProperty(k, v)
            }
            if (referer.isNotEmpty()) {
                conn.setRequestProperty("Referer", referer)
                conn.setRequestProperty("Origin", Net.origin(referer))
            }
            val code = conn.responseCode
            val stream: InputStream? = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { readUpTo(it, 512 * 1024) } ?: ByteArray(0)
            Resp(code, body, conn.url.toString())
        } catch (e: Exception) {
            Report.log("  ${Report.short(url)}: ${e.javaClass.simpleName}")
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
