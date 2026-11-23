package com.streamlink.tv

/**
 * Many short stalls in a row (2026-11-22, build 315 report): a picture-hidden Crackstreams stream "buffered" 85 times in
 * 12 minutes with 4-50 s of video downloaded. No 20 s stall ever happened, so nothing moved off it. [STALLS] stalls within
 * [WINDOW_MS] while playing = this source is bad. Pure; the player feeds it stall times.
 */
class StallStorm {
    companion object {
        const val STALLS = 4
        const val WINDOW_MS = 60_000L
    }

    private val times = ArrayDeque<Long>()

    fun reset() = times.clear()

    /** A stall at [now]; true when it makes [STALLS] within [WINDOW_MS] (then starts counting again). */
    fun note(now: Long): Boolean {
        times.addLast(now)
        while (times.isNotEmpty() && now - times.first() > WINDOW_MS) times.removeFirst()
        if (times.size >= STALLS) { times.clear(); return true }
        return false
    }

    fun count(): Int = times.size
}
