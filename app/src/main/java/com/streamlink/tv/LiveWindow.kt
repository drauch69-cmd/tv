package com.streamlink.tv

/**
 * Where to sit in a short live playlist (2026-11-22 logs; reworked in build 314). Many relays list only 3-4 pieces (12-20 s of video). The
 * smooth delay asked for ~20 s behind live, which Media3 clamps to the very first listed piece: one slow download and
 * that piece is already deleted (404s on pieces, then "fell behind live" in a loop, 40 stalls in 10 min). Keep the
 * play point at least [START_MARGIN] pieces after the oldest listed piece, and [EDGE_MARGIN] pieces from the newest.
 */
object LiveWindow {
    /** Playlist length and piece length last seen per stream ([LowDelayMemory.streamKey]), for this app run. */
    private val seen = java.util.concurrent.ConcurrentHashMap<String, LongArray>()

    fun remember(streamUrl: String?, windowMs: Long, pieceMs: Long) {
        val k = LowDelayMemory.streamKey(streamUrl) ?: return
        if (windowMs > 0 && pieceMs > 0) seen[k] = longArrayOf(windowMs, pieceMs)
    }

    fun clearMemory() = seen.clear()

    /**
     * The live delay to put in the stream's settings before it starts, or null when nothing is known about it (then the
     * player's usual choice). Build 313 moved the play point with a seek once the playlist arrived instead: that cut the
     * first piece's download mid-way and every start failed ("Unexpected read attempt"), so the delay is set up front now.
     */
    fun offsetFor(streamUrl: String?, smooth: Boolean): Long? {
        val k = LowDelayMemory.streamKey(streamUrl) ?: return null
        val (w, t) = seen[k]?.let { it[0] to it[1] } ?: return null
        val max = maxOffsetMs(w, t)
        val min = minOf((t * EDGE_MARGIN).toLong(), max)
        val want = if (smooth) 20_000L else 3 * t
        return want.coerceIn(min, max)
    }

    const val START_MARGIN = 1.5
    const val EDGE_MARGIN = 2.0
    /** Don't bother moving for less than this. */
    const val SLACK_MS = 500L

    /** Furthest safe distance behind the newest piece, for a playlist of [windowMs] with pieces of [pieceMs]. */
    fun maxOffsetMs(windowMs: Long, pieceMs: Long): Long {
        val max = windowMs - (pieceMs * START_MARGIN).toLong()
        val min = (pieceMs * EDGE_MARGIN).toLong()
        return if (max < min) windowMs / 2 else max
    }

    /**
     * The distance behind the newest piece to move to, or null when [currentOffsetMs] is already safe.
     * [wantMs]: what the delay setting asks for (smooth ~20 s; low delay: Media3's own choice).
     */
    fun correctedOffsetMs(windowMs: Long, pieceMs: Long, currentOffsetMs: Long, wantMs: Long): Long? {
        if (windowMs <= 0 || pieceMs <= 0) return null
        val max = maxOffsetMs(windowMs, pieceMs)
        if (currentOffsetMs <= max + SLACK_MS) return null
        val min = minOf((pieceMs * EDGE_MARGIN).toLong(), max)
        return wantMs.coerceIn(min, max)
    }
}
