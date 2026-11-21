package com.streamlink.tv

/** Pure rules for reading stream lists: what the hidden reader skips, when it snapshots, when it re-reads. Unit tested in ListReadLogicTest. */
object ListReadLogic {

    // ---- A1: heavy files the list reader never needs (layout comes from HTML, CSS and scripts) ----

    private val HEAVY_EXT = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico",
        "woff", "woff2", "ttf", "otf",
        "mp4", "webm", "m3u8", "ts", "m4s", "mp3", "aac",
    )

    /** Pictures, fonts, audio and video, by the Accept header or the path's extension. Never the main frame. */
    fun isHeavy(accept: String?, path: String?, mainFrame: Boolean): Boolean {
        if (mainFrame) return false
        val a = (accept ?: "").trim().lowercase()
        if (a.startsWith("image/") || a.startsWith("font/") || a.startsWith("audio/") || a.startsWith("video/")) return true
        val last = (path ?: "").substringBefore('?').substringBefore('#').substringAfterLast('/').lowercase()
        if (!last.contains('.')) return false
        return last.substringAfterLast('.') in HEAVY_EXT
    }

    // ---- A2: the Save-sample snapshot is only taken when a read failed or found nothing ----

    fun shouldSnapshot(result: ListResult): Boolean = result.error != null || result.items.isEmpty()

    // ---- A3: the list screen rebuilds only when order, sections or statuses change ----

    /** One row as drawn: its section label, link and status look (status|badge). */
    data class RowKey(val section: String, val url: String, val look: String)

    fun listLook(rows: List<RowKey>): String =
        rows.joinToString("\n") { it.section + '\u0001' + it.url + '\u0001' + it.look }

    /** True when the rows must be rebuilt; false when only their texts need refreshing in place. */
    fun needsRebuild(drawnLook: String?, newLook: String): Boolean = drawnLook == null || drawnLook != newLook

    // ---- B2: skip a list that was read a moment ago ----

    const val FRESH_MS = 150_000L
    /** Never re-read one list faster than this on a timer. */
    const val MIN_READ_GAP_MS = 60_000L

    enum class Reason { MANUAL, OPEN, TIMER }

    /** True when a request should be skipped: never for [Reason.MANUAL]; OPEN skips within [FRESH_MS] of the last good read, TIMER within [MIN_READ_GAP_MS]. */
    fun skipFresh(reason: Reason, lastOkAt: Long?, now: Long): Boolean {
        if (reason == Reason.MANUAL || lastOkAt == null || lastOkAt <= 0L) return false
        val age = now - lastOkAt
        if (age < 0) return false
        return when (reason) {
            Reason.OPEN -> age < FRESH_MS
            Reason.TIMER -> age < MIN_READ_GAP_MS
            Reason.MANUAL -> false
        }
    }

    /**
     * Adds [id] to [queue] (id -> manual), merging duplicates: a list being read now ([current]) or already queued is
     * not added twice (a queued one becomes manual if this request is). Returns true when it was newly queued.
     */
    fun merge(queue: MutableMap<String, Boolean>, current: String?, id: String, manual: Boolean): Boolean {
        if (id == current) return false
        val had = queue[id]
        if (had != null) {
            if (manual && !had) queue[id] = true
            return false
        }
        queue[id] = manual
        return true
    }

    // ---- B3: adaptive re-read timing ----

    const val NORMAL_DELAY_MS = 5 * 60_000L
    const val NEAR_DELAY_MS = 90_000L
    private const val SOON_WINDOW_MS = 15 * 60_000L
    private const val JUST_STARTED_MS = 10 * 60_000L

    /** Every 5 min; every 90 s while an event starts within 15 min or started within the last 10 min (new links appear then). Never under 60 s. */
    fun nextReadDelay(items: List<ListItem>, now: Long): Long {
        val near = items.any { val s = it.startMillis; s != null && s - now <= SOON_WINDOW_MS && now - s <= JUST_STARTED_MS }
        return (if (near) NEAR_DELAY_MS else NORMAL_DELAY_MS).coerceAtLeast(MIN_READ_GAP_MS)
    }

    // ---- Back-off for lists that keep failing or finding nothing (user, 2026-11-20) ----

    /** A read that failed or found no events. */
    fun isBad(result: ListResult): Boolean = result.error != null || result.items.isEmpty()

    /** After [streak] bad reads in a row: no wait under 3, 30 min at 3, then 2 h. Manual reads always go. */
    fun backoffMs(streak: Int): Long = when {
        streak < 3 -> 0L
        streak == 3 -> 30 * 60_000L
        else -> 2 * 60 * 60_000L
    }

    // ---- Read only when it can matter (user, 2026-11-20) ----

    /** A list with nothing on now or soon is still re-read this often, to catch newly posted games. */
    const val QUIET_REREAD_MS = 2 * 60 * 60_000L
    /** "Soon" for this rule: a game or event starting within this. */
    const val MATTERS_WINDOW_MS = 2 * 60 * 60_000L

    /**
     * Should a timer re-read happen? Yes when never read or read [QUIET_REREAD_MS] ago; when one of its saved events is
     * live or starts within [MATTERS_WINDOW_MS] ([statusOf] gives ESPN-aware statuses); or when one of its sports
     * ([sports], ESPN leagues) has a game live or starting within that window. Otherwise the saved copy is enough.
     */
    fun worthReading(
        sports: Set<String>, items: List<ListItem>, statusOf: (ListItem) -> String, games: List<Game>, lastOkAt: Long?, now: Long,
    ): Boolean {
        if (lastOkAt == null || lastOkAt <= 0L || now - lastOkAt >= QUIET_REREAD_MS) return true
        for (it in items) {
            val st = statusOf(it)
            if (st == ListStatus.LIVE || st == ListStatus.SOON) return true
            val s = it.startMillis
            if (s != null && s >= now && s - now <= MATTERS_WINDOW_MS) return true
        }
        return games.any { g ->
            g.league in sports && (g.state == "in" || (g.state == "pre" && g.startMillis >= now - 15 * 60_000L && g.startMillis - now <= MATTERS_WINDOW_MS))
        }
    }

    // ---- C2: finish when the events stop changing ----

    const val MAX_LOOKS = 4

    /** The events a read found: links, titles and time texts, in order. Empty when there are none. */
    fun eventsKey(items: List<ListItem>): String =
        items.joinToString("\n") { it.url + '\u0001' + it.title + '\u0001' + it.timeText }

    /** Two looks in a row gave the same non-empty events. */
    fun stable(previousKey: String?, key: String): Boolean = key.isNotEmpty() && key == previousKey
}
