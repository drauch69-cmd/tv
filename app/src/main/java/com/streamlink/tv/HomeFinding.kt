package com.streamlink.tv

/**
 * Games show on home before their streams are found (Lab, 2026-11-21). Right after the app starts, live games
 * whose sport still has a list waiting for its first read are shown with "Finding streams…" instead of being
 * hidden until the hidden page reader gets to them. Once every list carrying a sport has been read (or failed),
 * or after [MAX_MS], home goes back to games with a stream only. Pure, unit tested.
 */
object HomeFinding {
    /** Never longer than this after start: a slow or stuck list must not keep stream-less games on home. */
    const val MAX_MS = 60_000L
    /** A list read this recently (before the app started) counts as read. */
    const val FRESH_MS = 5 * 60_000L

    /**
     * The sports still being searched: those carried by a list (an untagged list carries every sport) that hasn't
     * finished a read since start ([readThisRun]) and wasn't read in the last [FRESH_MS] ([readAt], 0 = never).
     */
    fun leagues(
        lists: List<SavedList>, readThisRun: (String) -> Boolean, readAt: (String) -> Long, now: Long, startedAt: Long,
    ): Set<String> {
        if (now - startedAt > MAX_MS || startedAt > now) return emptySet()
        val out = HashSet<String>()
        for (l in lists) {
            if (readThisRun(l.id)) continue
            val at = readAt(l.id)
            if (at > 0 && now - at in 0..FRESH_MS) continue
            if (l.sports.isEmpty()) out.addAll(Sports.ALL) else out.addAll(l.sports)
        }
        return out
    }

    /** Whether [gf] may show on home: it has a stream, or its sport is still being searched. */
    fun shows(gf: GameFolder, finding: Set<String>): Boolean = gf.streams.isNotEmpty() || gf.game.league in finding
}
