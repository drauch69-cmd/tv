package com.streamlink.tv

/** What the home screen shows from the saved lists. Pure (no Android), unit tested. */
object HomeFeed {
    /** One card in the "Live now" row. */
    data class Entry(val list: SavedList, val item: ListItem, val status: String)

    /** Per-list counts for the list tiles. */
    data class Counts(val live: Int, val later: Int)

    const val MAX_CARDS = 10
    /** Upcoming events that start within this window fill the row after the live and soon ones. */
    const val UPCOMING_WINDOW_MS = 6 * 60 * 60 * 1000L

    /**
     * Live events from every list first (earliest start first), then ones starting within 30 min,
     * then (to fill the row) ones starting within [UPCOMING_WINDOW_MS]. The same event URL in two
     * lists shows once.
     */
    fun liveRow(lists: List<Pair<SavedList, List<ListItem>>>, now: Long, max: Int = MAX_CARDS): List<Entry> {
        val all = ArrayList<Entry>()
        val seen = HashSet<String>()
        for ((l, items) in lists) for (i in items) {
            if (!seen.add(i.url)) continue
            all.add(Entry(l, i, ListStatus.of(i, now)))
        }
        fun startKey(e: Entry) = e.item.startMillis ?: Long.MAX_VALUE
        // Real live events first (earliest start first), always-on channels after them.
        val live = all.filter { it.status == ListStatus.LIVE }
            .sortedWith(compareBy({ e -> e.item.startMillis?.let { ListStatus.isAlwaysOn(it, now) } == true }, { startKey(it) }))
        val soon = all.filter { it.status == ListStatus.SOON }.sortedBy { startKey(it) }
        val later = all.filter {
            it.status == ListStatus.UPCOMING && (it.item.startMillis ?: Long.MAX_VALUE) - now <= UPCOMING_WINDOW_MS
        }.sortedBy { startKey(it) }
        return (live + soon + later).take(max)
    }

    fun counts(items: List<ListItem>, now: Long): Counts {
        var live = 0
        var later = 0
        for (i in items) when (ListStatus.of(i, now)) {
            ListStatus.LIVE -> live++
            ListStatus.SOON, ListStatus.UPCOMING, ListStatus.UNKNOWN -> later++
        }
        return Counts(live, later)
    }

    /** "2 live · 13 later", "Nothing live · 1 later", "Nothing listed". */
    fun countsText(c: Counts): String = when {
        c.live > 0 && c.later > 0 -> "${c.live} live · ${c.later} later"
        c.live > 0 -> "${c.live} live"
        c.later > 0 -> "Nothing live · ${c.later} later"
        else -> "Nothing listed"
    }

    /** The card's badge: "LIVE", "IN 12 MIN", or the start time ("7:30 PM"); "" when there is nothing to say. */
    fun badge(item: ListItem, status: String, now: Long, zone: java.util.TimeZone, locale: java.util.Locale): String {
        if (status == ListStatus.LIVE) return "LIVE"
        val s = item.startMillis ?: return ""
        if (status == ListStatus.SOON) return "IN ${((s - now + 59_999) / 60_000).coerceAtLeast(1)} MIN"
        return java.text.SimpleDateFormat("h:mm a", locale).apply { timeZone = zone }.format(java.util.Date(s))
    }

    /** Bottom line of a card: "Started 25 min ago" / "Always on" (started over 24 h ago) / "Starts in 12 min" / "Today 7:30 PM" / "Live". */
    fun whenLine(item: ListItem, now: Long, zone: java.util.TimeZone, locale: java.util.Locale): String {
        val s = item.startMillis ?: return if (item.liveHint) "Live" else ""
        val d = s - now
        return when {
            d <= 0 && ListStatus.isAlwaysOn(s, now) -> "Always on"
            d <= 0 -> "Started ${ListText.duration(-d)} ago"
            d < 60 * 60 * 1000L -> "Starts in ${ListText.duration(d)}"
            else -> ListText.whenText(s, now, zone, locale)
        }
    }
}
