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
)

/** Live/upcoming status from an item and the current time. Pure, unit-tested. */
object ListStatus {
    const val LIVE = "live"
    const val SOON = "soon"
    const val UPCOMING = "upcoming"
    const val FINISHED = "finished"
    const val UNKNOWN = "unknown"

    private const val LIVE_WINDOW_MS = 4 * 60 * 60 * 1000L
    private const val SOON_MS = 30 * 60 * 1000L

    /** live: liveHint, or started and less than 4h ago; soon: starts within 30 min; upcoming: later;
     *  finished: started more than 4h ago; unknown: no time and no liveHint. */
    fun of(item: ListItem, nowMillis: Long): String {
        if (item.liveHint) return LIVE
        val start = item.startMillis ?: return UNKNOWN
        val delta = start - nowMillis
        return when {
            delta > SOON_MS -> UPCOMING
            delta > 0 -> SOON
            -delta < LIVE_WINDOW_MS -> LIVE
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
    fun sorted(items: List<ListItem>, nowMillis: Long): List<ListItem> {
        val tagged = items.map { it to of(it, nowMillis) }
        val cmp = Comparator<Pair<ListItem, String>> { a, b ->
            val ra = rank(a.second)
            val rb = rank(b.second)
            if (ra != rb) return@Comparator ra.compareTo(rb)
            val sa = a.first.startMillis
            val sb = b.first.startMillis
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
