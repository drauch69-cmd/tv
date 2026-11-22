package com.streamlink.tv

import java.net.URLEncoder
import java.util.Locale

/**
 * Pure rules behind the polished look (team logos, sport photos, the player's ping line). No Android APIs, so they
 * are unit tested; the loaders and screens that use them are in Logos.kt, SportPhotos.kt and the activities.
 */

/** Where to download a team logo from, smallest and best-looking first. */
object LogoUrls {
    private const val HOST = "https://a.espncdn.com"

    /**
     * Addresses to try for ESPN logo [url] drawn [px] pixels wide, in order: the logo made for dark backgrounds,
     * shrunk by ESPN's image service; the normal logo shrunk; the normal logo as sent. Anything that is not an ESPN
     * logo address is tried as is, alone.
     */
    fun candidates(url: String, px: Int): List<String> {
        if (!url.startsWith("$HOST/i/")) return if (url.startsWith("https://")) listOf(url) else emptyList()
        val path = url.removePrefix(HOST).substringBefore('?')
        val size = px.coerceIn(24, 256)
        fun small(p: String) = "$HOST/combiner/i?img=" + URLEncoder.encode(p, "UTF-8") + "&w=$size&h=$size"
        val out = ArrayList<String>()
        val dark = darkPath(path)
        if (dark != null) out.add(small(dark))
        out.add(small(path))
        out.add(url)
        return out
    }

    /** "/i/teamlogos/nfl/500/nyg.png" -> "/i/teamlogos/nfl/500-dark/nyg.png"; null if there is no "/500/" part. */
    fun darkPath(path: String): String? = if (path.contains("/500/")) path.replaceFirst("/500/", "/500-dark/") else null
}

/** How a sport photo is placed in its box so the spot the user picked stays in view. */
object PhotoFrame {
    /** Scale, then shift (in view pixels) for an image drawn into a view: cover the view, focus point centred if it can be. */
    data class Placement(val scale: Float, val dx: Float, val dy: Float)

    /**
     * Covers a [viewW]x[viewH] box with a [imgW]x[imgH] image (no empty edges), with the image point at
     * ([fx], [fy]) (0..1 fractions of the image) as close to the box centre as the edges allow.
     */
    fun place(imgW: Int, imgH: Int, viewW: Int, viewH: Int, fx: Float, fy: Float): Placement {
        if (imgW <= 0 || imgH <= 0 || viewW <= 0 || viewH <= 0) return Placement(1f, 0f, 0f)
        val scale = maxOf(viewW.toFloat() / imgW, viewH.toFloat() / imgH)
        val w = imgW * scale
        val h = imgH * scale
        val dx = (viewW / 2f - fx.coerceIn(0f, 1f) * w).coerceIn(minOf(viewW - w, 0f), 0f)
        val dy = (viewH / 2f - fy.coerceIn(0f, 1f) * h).coerceIn(minOf(viewH - h, 0f), 0f)
        return Placement(scale, dx, dy)
    }

    /**
     * The exact size to scale a decoded [imgW]x[imgH] bitmap down to so it just covers [viewW]x[viewH]; null if it is
     * already within about 20% of that (not worth a copy). The power-of-two decode can leave up to ~4x the pixels needed.
     */
    fun coverSize(imgW: Int, imgH: Int, viewW: Int, viewH: Int): Pair<Int, Int>? {
        if (imgW <= 0 || imgH <= 0 || viewW <= 0 || viewH <= 0) return null
        val s = maxOf(viewW.toFloat() / imgW, viewH.toFloat() / imgH)
        if (s >= 0.8f) return null
        return maxOf(1, Math.ceil(imgW * s.toDouble()).toInt()) to maxOf(1, Math.ceil(imgH * s.toDouble()).toInt())
    }

    /** Power-of-two shrink for decoding an [imgW]x[imgH] picture that will cover a [viewW]x[viewH] box. */
    fun sampleSize(imgW: Int, imgH: Int, viewW: Int, viewH: Int): Int {
        if (viewW <= 0 || viewH <= 0) return 1
        var s = 1
        while (imgW / (s * 2) >= viewW && imgH / (s * 2) >= viewH) s *= 2
        return s
    }
}

/** Rules for the per-sport photos the phone uploads. */
object SportPhotoRules {
    const val MAX_BYTES = 3_000_000

    /** The sports that can have a photo. */
    fun validLeague(league: String?): Boolean = league != null && league in Sports.ALL

    /** A JPEG or PNG picture (by its first bytes) of a sane size. */
    fun acceptable(bytes: ByteArray): Boolean {
        if (bytes.size < 64 || bytes.size > MAX_BYTES) return false
        val jpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        val png = bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
        return jpeg || png
    }

    /** A focus fraction from the phone ("0.62"), kept inside 0..1; null if it is not a number. */
    fun focus(raw: String?): Float? = raw?.trim()?.toFloatOrNull()?.takeIf { !it.isNaN() }?.coerceIn(0f, 1f)
}

/** The player overlay's connection pill. */
object PlayerStats {
    enum class Level { MEASURING, GOOD, TIGHT, SLOW }
    data class Line(val text: String, val level: Level)

    private fun mbps(bps: Long) = String.format(Locale.US, "%.1f", bps / 1_000_000.0)

    /**
     * Compact pill text: "⇄ 24 ms   ▸ 6.2", plus "  3 dropped" only when frames were dropped. ⇄ is the ping
     * [ping] (is it me or the site?), ▸ what the stream needs in Mbps [needBps] (unknown when 0 or less: "…"),
     * [dropped] the dropped-frame count (unknown when negative). The level (pill color) comes from the ping alone:
     * ▸ is shown for reference, it says nothing about the connection.
     */
    fun line(ping: PingStats.Reading, needBps: Long, dropped: Int): Line {
        val need = if (needBps > 0) mbps(needBps) else "…"
        val text = "\u21C4 ${ping.text}   \u25B8 $need" + if (dropped > 0) "   $dropped dropped" else ""
        return Line(text, ping.level)
    }
}

/**
 * Recent ping results (a quick connection to a fast public server every few seconds), newest last; null = no reply.
 * Answers "is it my connection or the stream?": steady and low means the connection is fine.
 * Not thread-safe: use from one thread.
 */
class PingStats {
    companion object {
        const val WINDOW = 5
        const val GOOD_MS = 100L   // median at or under: GOOD
        const val SLOW_MS = 300L   // median over: SLOW
    }
    data class Reading(val text: String, val level: PlayerStats.Level)

    private val samples = ArrayDeque<Long?>()

    fun clear() = samples.clear()

    /** One result: the round trip in ms, or null when there was no reply. */
    fun add(ms: Long?) {
        samples.addLast(ms)
        while (samples.size > WINDOW) samples.removeFirst()
    }

    /** Median of the replies in the window, or null if there were none. */
    fun medianMs(): Long? {
        val ok = samples.filterNotNull().sorted()
        return if (ok.isEmpty()) null else ok[ok.size / 2]
    }

    fun lost(): Int = samples.count { it == null }

    /**
     * Text and level. The number is the median of recent replies (one slow reply doesn't flash the pill).
     * SLOW: the last two got no reply, 2+ lost in the window, or median over [SLOW_MS]. TIGHT: one lost or median over
     * [GOOD_MS]. MEASURING: nothing yet. The newest result failing shows "lost" so a dead connection shows at once.
     */
    fun reading(): Reading {
        if (samples.isEmpty()) return Reading("…", PlayerStats.Level.MEASURING)
        val med = medianMs()
        val lost = lost()
        val lastTwoLost = samples.size >= 2 && samples.last() == null && samples.elementAt(samples.size - 2) == null
        val level = when {
            med == null || lastTwoLost || lost >= 2 || med > SLOW_MS -> PlayerStats.Level.SLOW
            lost == 1 || med > GOOD_MS -> PlayerStats.Level.TIGHT
            else -> PlayerStats.Level.GOOD
        }
        val text = if (samples.last() == null || med == null) "lost" else "$med ms"
        return Reading(text, level)
    }

    /** For the report: "ping 24 ms (0 of 5 lost)" or "ping: no replies (5 of 5 lost)". */
    fun summary(): String {
        if (samples.isEmpty()) return "ping not measured yet"
        val med = medianMs()
        return (if (med == null) "ping: no replies" else "ping $med ms") + " (${lost()} of ${samples.size} lost)"
    }
}

/**
 * What a stream really needs, measured from the media pieces it downloads: their size against the seconds of video
 * (or audio) they hold. Kept per track kind (video, audio, mixed) over the last [WINDOW] pieces each; the kinds add up,
 * since separate audio and video pieces cover the same seconds. Not thread-safe: use from the player's thread.
 */
class StreamRate {
    companion object { const val WINDOW = 8 }
    private val pieces = HashMap<Int, ArrayDeque<Pair<Long, Long>>>() // kind -> (bytes, media ms)

    fun clear() = pieces.clear()

    /** One finished media piece of [kind] (any number), [bytes] long, holding [mediaMs] of media; ignored if odd. */
    fun add(kind: Int, bytes: Long, mediaMs: Long) {
        if (bytes <= 0 || mediaMs < 200 || mediaMs > 60_000) return
        val q = pieces.getOrPut(kind) { ArrayDeque() }
        q.addLast(bytes to mediaMs)
        while (q.size > WINDOW) q.removeFirst()
    }

    /** Bits per second, or 0 until something was measured. */
    fun bps(): Long = pieces.values.sumOf { q ->
        val ms = q.sumOf { it.second }
        if (ms <= 0) 0L else q.sumOf { it.first } * 8_000L / ms
    }
}
