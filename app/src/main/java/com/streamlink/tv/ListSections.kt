package com.streamlink.tv

import java.util.Locale
import java.util.TimeZone

/** Pure grouping and row text for the list screen (unit tested). */
object ListSections {
    data class Section(val status: String, val label: String, val items: List<ListItem>)

    fun label(status: String): String = when (status) {
        ListStatus.LIVE -> "LIVE NOW"
        ListStatus.SOON -> "STARTING SOON"
        ListStatus.UPCOMING -> "LATER"
        ListStatus.UNKNOWN -> "NO TIME GIVEN"
        else -> "MAYBE ENDED"
    }

    /** Groups already-sorted items (ListJson.visible order) into labelled sections; empty sections are left out. */
    fun group(sorted: List<ListItem>, now: Long): List<Section> {
        val order = listOf(ListStatus.LIVE, ListStatus.SOON, ListStatus.UPCOMING, ListStatus.UNKNOWN, ListStatus.FINISHED)
        val by = sorted.groupBy { ListStatus.of(it, now) }
        return order.mapNotNull { st -> by[st]?.takeIf { it.isNotEmpty() }?.let { Section(st, label(st), it) } }
    }

    /** "started 25 min ago", "started 1 h 05 min ago"; null when there is no start time. */
    fun startedAgo(start: Long?, now: Long): String? =
        if (start == null || start > now) null else "started ${ListText.duration(now - start)} ago"

    /** "IN 12 MIN" for events starting within the hour, else null. */
    fun inMinutes(item: ListItem, now: Long): String? {
        val s = item.startMillis ?: return null
        if (s <= now) return null
        return "IN ${((s - now + ListText.MIN - 1) / ListText.MIN).coerceAtLeast(1)} MIN"
    }

    /** Second line of a row: subtitle / section, then when. */
    fun meta(item: ListItem, now: Long, zone: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault()): String {
        val parts = mutableListOf<String>()
        item.subtitle.trim().takeIf { it.isNotEmpty() }?.let { parts += it }
        item.section.trim().takeIf { it.isNotEmpty() && it != item.subtitle.trim() }?.let { parts += it }
        val st = item.startMillis
        val whenPart = when {
            st == null -> item.timeText.trim().takeIf { it.isNotEmpty() }
            ListStatus.of(item, now) == ListStatus.LIVE && st <= now -> startedAgo(st, now)
            ListStatus.of(item, now) == ListStatus.FINISHED -> ListText.whenText(st, now, zone, locale)
            else -> ListText.whenText(st, now, zone, locale)
        }
        whenPart?.let { parts += it }
        return parts.joinToString("  ·  ")
    }

    /** Right-hand badge: (text, isLive, isPill). Pill false means plain secondary text. */
    data class Badge(val text: String, val live: Boolean, val pill: Boolean)

    fun badge(item: ListItem, now: Long): Badge = when (ListStatus.of(item, now)) {
        ListStatus.LIVE -> Badge("LIVE", true, true)
        ListStatus.SOON -> Badge(inMinutes(item, now) ?: "SOON", false, true)
        ListStatus.FINISHED -> Badge("Maybe ended", false, false)
        else -> Badge("Wait for it", false, false)
    }
}

/** Pure text/state rules for the waiting screen (unit tested). */
object WaitingText {
    const val DONE = 2
    const val ACTIVE = 1
    const val TODO = 0

    /** States of the 3 steps (Opening page, Finding the stream, Starting video) from StreamSniffer.stage; [handedOff] = all done. */
    fun steps(stage: Int, handedOff: Boolean): List<Int> {
        if (handedOff) return listOf(DONE, DONE, DONE)
        return when (stage.coerceIn(0, 2)) {
            0 -> listOf(ACTIVE, TODO, TODO)
            1 -> listOf(DONE, ACTIVE, TODO)
            else -> listOf(DONE, DONE, ACTIVE)
        }
    }

    /** Big text of the calm waiting state. */
    fun headline(start: Long?, now: Long, zone: TimeZone, locale: Locale): String = when {
        start == null -> "Waiting for the stream"
        start > now && start - now < ListText.HOUR -> "Starts in ${ListText.duration(start - now)}"
        start > now -> ListText.whenText(start, now, zone, locale)
        else -> "Started ${ListText.duration(now - start)} ago"
    }
}
