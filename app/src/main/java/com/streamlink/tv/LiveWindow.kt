package com.streamlink.tv

/**
 * Where to sit in a short live playlist (2026-11-22 logs). Many relays list only 3-4 pieces (12-20 s of video). The
 * smooth delay asked for ~20 s behind live, which Media3 clamps to the very first listed piece: one slow download and
 * that piece is already deleted (404s on pieces, then "fell behind live" in a loop, 40 stalls in 10 min). Keep the
 * play point at least [START_MARGIN] pieces after the oldest listed piece, and [EDGE_MARGIN] pieces from the newest.
 */
object LiveWindow {
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
