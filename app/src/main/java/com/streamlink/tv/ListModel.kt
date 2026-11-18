package com.streamlink.tv

/** One event read off an index page. */
data class ListItem(
    val title: String,        // e.g. "Community Garden Volunteer Day"
    val subtitle: String,     // organiser / category / extra line, "" if none
    val url: String,          // absolute http(s) URL of the event's own page
    val startMillis: Long?,   // parsed start time (epoch ms, TV's time zone), null if unknown
    val timeText: String,     // time exactly as shown, e.g. "Sat, May 24 • 9:00 AM" or "Start time: 1:00 PM"
    val section: String,      // group heading above the item if any (e.g. "Thursday, November 19, 2026"), else ""
    val liveHint: Boolean,    // the page itself marks it live ("LIVE", "Live now", "On air", red dot class…)
    val imageUrl: String?,    // thumbnail if the item has one
)

data class ListResult(
    val pageUrl: String,      // the index page actually loaded (after redirects)
    val pageTitle: String,    // document title or main heading
    val items: List<ListItem>,
    val method: String,       // "automatic" or "recipe:<host>"
    val error: String?,       // null on success; human-readable reason otherwise (items may be empty)
    val noEvents: Boolean = false, // the page loaded fine but holds no events (an error text is still set for display)
) {
    companion object { const val NO_EVENTS = "No events found on this page" }
}

/** Whether a finished read replaces the saved cache. Pure; unit tested. */
object ListCachePolicy {
    /** Events found: save. Loaded but empty: save the empty list (stops old junk showing). Real failure: keep the old cache. */
    fun shouldSave(result: ListResult): Boolean = result.items.isNotEmpty() || result.error == null || result.noEvents
}

/** Live/upcoming status from an item and the current time. Pure, unit-tested. */
object ListStatus {
    const val LIVE = "live"
    const val SOON = "soon"
    const val UPCOMING = "upcoming"
    const val FINISHED = "finished"
    const val UNKNOWN = "unknown"

    private const val LIVE_WINDOW_MS = 4 * 60 * 60 * 1000L
    const val SOON_MS = 30 * 60 * 1000L
    const val ALWAYS_ON_MS = 24 * 60 * 60 * 1000L

    /** A start more than 24 h ago means a channel, not a one-time event: "always on". */
    fun isAlwaysOn(start: Long, nowMillis: Long): Boolean = nowMillis - start > ALWAYS_ON_MS

    /** live: liveHint (until 4h after the start), started less than 4h ago, or started more than 24h ago (always on); soon: starts
     *  within 30 min; upcoming: later; finished: started 4h..24h ago; unknown: no time and no liveHint.
     *  Index pages drop real one-time events once they are over, so an entry still listed a day or more
     *  after its start is treated as a channel. */
    fun of(item: ListItem, nowMillis: Long, games: List<Game> = ScoreFeed.latest()): String =
        ScoreStatus.resolve(item, nowMillis, games).status

    /** The status from the item's own time and marker only (no scores). */
    fun byTime(item: ListItem, nowMillis: Long): String {
        val start = item.startMillis
        // A site's live marker counts until 4 h after the listed start; some sites never take it down.
        if (item.liveHint && (start == null || nowMillis - start < LIVE_WINDOW_MS)) return LIVE
        if (start == null) return UNKNOWN
        val delta = start - nowMillis
        return when {
            delta > SOON_MS -> UPCOMING
            delta > 0 -> SOON
            -delta < LIVE_WINDOW_MS -> LIVE
            -delta > ALWAYS_ON_MS -> LIVE
            else -> FINISHED
        }
    }

    private fun rank(status: String) = when (status) {
        LIVE -> 0; SOON -> 1; UPCOMING -> 2; UNKNOWN -> 3; else -> 4
    }

    /**
     * Sort for display: live first (earliest start first, unknown start last), then soon and
     * upcoming (by start), unknown (page order kept), finished last (most recent first).
     */
    fun sorted(items: List<ListItem>, nowMillis: Long, games: List<Game> = ScoreFeed.latest()): List<ListItem> {
        // Matched items sort by ESPN's status and kickoff; the original items are returned.
        val tagged = items.map { val r = ScoreStatus.resolve(it, nowMillis, games); Triple(it, r.status, r.item.startMillis) }
        val cmp = Comparator<Triple<ListItem, String, Long?>> { a, b ->
            val ra = rank(a.second)
            val rb = rank(b.second)
            if (ra != rb) return@Comparator ra.compareTo(rb)
            val sa = a.third
            val sb = b.third
            // Real live events before always-on channels.
            if (a.second == LIVE && sa != null && sb != null) {
                val aa = isAlwaysOn(sa, nowMillis)
                val ab = isAlwaysOn(sb, nowMillis)
                if (aa != ab) return@Comparator if (aa) 1 else -1
            }
            when (a.second) {
                LIVE, SOON, UPCOMING -> when {
                    sa == null && sb == null -> 0
                    sa == null -> 1
                    sb == null -> -1
                    else -> sa.compareTo(sb)
                }
                FINISHED -> (sb ?: 0L).compareTo(sa ?: 0L)
                else -> 0
            }
        }
        return tagged.sortedWith(cmp).map { it.first } // sortedWith is stable
    }
}
