package com.streamlink.tv

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Pure text and timing rules for stream lists and the waiting screen (unit tested). */
object ListText {
    const val MIN = 60_000L
    const val HOUR = 60 * MIN
    /** An event that started less than this long ago may still be about to go live. */
    const val RECENT_START_MS = 30 * MIN
    /** How long to keep waiting after the start time (or after waiting began, if there is no time). */
    const val GIVE_UP_MS = 3 * HOUR

    /** "just now", "3 min ago", "2 h ago", "3 days ago"; "never" when it was not read yet. */
    fun ago(readAt: Long, now: Long): String {
        if (readAt <= 0) return "never"
        val d = (now - readAt).coerceAtLeast(0)
        return when {
            d < MIN -> "just now"
            d < HOUR -> "${d / MIN} min ago"
            d < 24 * HOUR -> "${d / HOUR} h ago"
            else -> "${d / (24 * HOUR)} days ago"
        }
    }

    /** "45 s", "12 min", "1 h 05 min". */
    fun duration(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min"
            else -> String.format(Locale.US, "%d h %02d min", s / 3600, (s % 3600) / 60)
        }
    }

    /** "starts in 12 min" / "started 5 min ago" / "no start time". */
    fun countdown(startMillis: Long?, now: Long): String {
        if (startMillis == null) return "no start time"
        val diff = startMillis - now
        return if (diff >= 0) "starts in ${duration(diff)}" else "started ${duration(-diff)} ago"
    }

    /** The status badge for a list row: LIVE, Starts in 12 min, Sat 9:00 AM, Unknown time. */
    fun badge(item: ListItem, now: Long, zone: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault()): String =
        when (ListStatus.of(item, now)) {
            ListStatus.LIVE -> "LIVE"
            ListStatus.FINISHED -> "Maybe ended"
            ListStatus.SOON -> "Starts in ${minutesCeil(item, now)} min"
            ListStatus.UPCOMING -> {
                val s = item.startMillis ?: now
                if (s - now < HOUR) "Starts in ${minutesCeil(item, now)} min" else whenText(s, now, zone, locale)
            }
            else -> item.timeText.trim().takeIf { it.isNotEmpty() }?.let { if (it.length > 24) it.take(23) + "…" else it } ?: "Unknown time"
        }

    private fun minutesCeil(item: ListItem, now: Long): Long =
        (((item.startMillis ?: now) - now + MIN - 1) / MIN).coerceAtLeast(1)

    /** "Today 9:00 AM", "Tomorrow 9:00 AM", "Sat 9:00 AM" (within a week), "Sat, May 24 9:00 AM". */
    fun whenText(start: Long, now: Long, zone: TimeZone, locale: Locale): String {
        val time = SimpleDateFormat("h:mm a", locale).apply { timeZone = zone }.format(Date(start))
        val days = dayNumber(start, zone) - dayNumber(now, zone)
        return when {
            days == 0L -> "Today $time"
            days == 1L -> "Tomorrow $time"
            days in 2..6 -> SimpleDateFormat("EEE", locale).apply { timeZone = zone }.format(Date(start)) + " $time"
            else -> SimpleDateFormat("EEE, MMM d", locale).apply { timeZone = zone }.format(Date(start)) + " $time"
        }
    }

    private fun dayNumber(ms: Long, zone: TimeZone): Long {
        val c = Calendar.getInstance(zone).apply { timeInMillis = ms }
        // Days since an arbitrary epoch in the given zone; the zone offset puts midnight in the right place.
        return Math.floorDiv(c.timeInMillis + zone.getOffset(c.timeInMillis), 24 * HOUR)
    }

    /**
     * An event from a list that has no stream yet: keep waiting if it starts later, started less than
     * 30 minutes ago, or has no time at all. Otherwise show the site's own player like a direct link.
     */
    fun shouldWait(startMillis: Long?, now: Long): Boolean =
        startMillis == null || startMillis > now || now - startMillis < RECENT_START_MS

    /** Stop waiting 3 hours after the start time, or 3 hours after waiting began if there is no time. */
    fun gaveUp(startMillis: Long?, waitStartedAt: Long, now: Long): Boolean =
        if (startMillis != null) now > startMillis + GIVE_UP_MS else now - waitStartedAt > GIVE_UP_MS

    /** The title to show: the item's own, or the end of its link when the page gave none. */
    fun displayTitle(item: ListItem): String {
        item.title.trim().takeIf { it.isNotEmpty() }?.let { return it }
        val u = try { java.net.URI(item.url) } catch (_: Exception) { null }
        val path = u?.path?.trim('/')?.takeIf { it.isNotEmpty() }
        return path ?: u?.host ?: item.url.ifEmpty { "Untitled event" }
    }
}
