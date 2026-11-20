package com.streamlink.tv

import java.net.URLEncoder
import java.util.Locale

/**
 * Pure rules behind the polished look (team logos, sport photos, the player's speed line). No Android APIs, so they
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

/** The player overlay's connection line. */
object PlayerStats {
    data class Line(val text: String, val warn: Boolean)

    private fun mbps(bps: Long) = String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0)

    /**
     * "Download 18.4 Mbps · stream needs 6.2 Mbps · 0 dropped frames". [haveBps] is the connection estimate,
     * [needBps] the playing quality's bit rate (unknown when 0 or less), [dropped] the dropped-frame count (unknown
     * when negative). [Line.warn] when the connection is under 1.2x what the stream needs.
     */
    fun line(haveBps: Long, needBps: Long, dropped: Int): Line {
        val parts = ArrayList<String>()
        parts.add(if (haveBps > 0) "Download ${mbps(haveBps)}" else "Download: measuring…")
        if (needBps > 0) parts.add("stream needs ${mbps(needBps)}")
        if (dropped >= 0) parts.add(if (dropped == 1) "1 dropped frame" else "$dropped dropped frames")
        val warn = haveBps > 0 && needBps > 0 && haveBps < needBps * 1.2
        return Line(parts.joinToString(" · "), warn)
    }
}
