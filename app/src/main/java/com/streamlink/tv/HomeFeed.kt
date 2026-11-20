package com.streamlink.tv

/** What the home screen shows from the saved lists. Pure (no Android), unit tested. */
object HomeFeed {
    /** A list item shown on the home screen (the RedZone card), with its status. */
    data class Entry(val list: SavedList, val item: ListItem, val status: String)

    /** Per-list counts for the list tiles. */
    data class Counts(val live: Int, val later: Int)

    /** RedZone only airs while several NFL games are on at once (Sunday windows), so it needs this many live. */
    const val REDZONE_MIN_NFL = 2

    /**
     * NFL RedZone for the front of the Live games row (user, 2026-11-19): the first RedZone channel in list order
     * (best-ranked list first), only while at least [REDZONE_MIN_NFL] NFL games are live by ESPN. Its own status
     * must be live or unknown (an always-on channel, or no time given); one with a later start time waits.
     * One card even when several lists carry it. Null otherwise.
     */
    fun redZone(lists: List<Pair<SavedList, List<ListItem>>>, now: Long, games: List<Game>): Entry? {
        if (LiveGames.nflLiveCount(games) < REDZONE_MIN_NFL) return null
        for ((l, items) in lists) for (i in items) {
            if (!ChannelFilter.isRedZone(ListText.displayTitle(i))) continue
            val r = ScoreStatus.resolve(i, now, games)
            if (r.status == ListStatus.LIVE || r.status == ListStatus.UNKNOWN) return Entry(l, r.item, ListStatus.LIVE)
        }
        return null
    }

    fun counts(items: List<ListItem>, now: Long, games: List<Game> = ScoreFeed.latest()): Counts {
        var live = 0
        var later = 0
        for (i in items) when (ListStatus.of(i, now, games)) {
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
