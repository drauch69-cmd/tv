package com.streamlink.tv

/**
 * Lab (2026-11-21): main stream servers whose servers the user's network blocks (broken secure connection on both
 * engines), shared by every player in the app. Once a main server is blocked, a new player for it (another site that
 * uses the same main server) skips the re-asks and page reloads that can only hit the same block, and the "No working
 * stream" screen suggests the VPN. Pure apart from the clock passed in; unit tested.
 */
object NetBlock {
    const val WINDOW_MS = 10 * 60_000L
    const val THRESHOLD = 3
    private val events = HashMap<String, ArrayDeque<Long>>()

    @Synchronized fun record(mainHost: String?, now: Long) {
        val k = mainHost?.lowercase() ?: return
        val q = events.getOrPut(k) { ArrayDeque() }
        q.addLast(now)
        while (q.isNotEmpty() && now - q.first() > WINDOW_MS) q.removeFirst()
        while (q.size > 20) q.removeFirst()
    }

    /** [THRESHOLD] or more blocks on servers named by [mainHost] within [WINDOW_MS]. */
    @Synchronized fun isBlocked(mainHost: String?, now: Long): Boolean {
        val q = events[mainHost?.lowercase() ?: return false] ?: return false
        return q.count { now - it in 0..WINDOW_MS } >= THRESHOLD
    }

    /** Any main server blocked recently (for the "No working stream" screen). */
    @Synchronized fun anyBlocked(now: Long): Boolean = events.keys.any { isBlocked(it, now) }

    @Synchronized internal fun clear() = events.clear()
}
