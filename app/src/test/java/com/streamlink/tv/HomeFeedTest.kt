package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class HomeFeedTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L
    private val hour = 60 * min
    private fun item(t: String, start: Long?, live: Boolean = false) =
        ListItem(t, "", "https://e.example/$t", start, "", "", live, null)

    @Test fun liveFirstThenSoonThenUpcomingWithinWindow() {
        val a = SavedList("a", "A", "https://a.example")
        val b = SavedList("b", "B", "https://b.example")
        val rows = HomeFeed.liveRow(listOf(
            a to listOf(item("later", now + 2 * hour), item("far", now + 9 * hour), item("old", now - 6 * hour), item("notime", null)),
            b to listOf(item("soon", now + 10 * min), item("live2", now - 10 * min), item("live1", now - 50 * min), item("badge", null, live = true)),
        ), now)
        assertEquals(listOf("live1", "live2", "badge", "soon", "later"), rows.map { it.item.title })
        assertEquals("B", rows[0].list.name)
    }

    @Test fun sameUrlInTwoListsShowsOnce() {
        val a = SavedList("a", "A", "https://a.example")
        val b = SavedList("b", "B", "https://b.example")
        val x = item("x", now - min)
        assertEquals(1, HomeFeed.liveRow(listOf(a to listOf(x), b to listOf(x)), now).size)
    }

    @Test fun capped() {
        val a = SavedList("a", "A", "https://a.example")
        val many = (1..20).map { item("e$it", now - it * min) }
        assertEquals(HomeFeed.MAX_CARDS, HomeFeed.liveRow(listOf(a to many), now).size)
    }

    @Test fun countsAndText() {
        val c = HomeFeed.counts(listOf(item("l", now - min), item("s", now + 5 * min), item("u", now + 5 * hour), item("n", null), item("f", now - 9 * hour)), now)
        assertEquals(HomeFeed.Counts(1, 3), c)
        assertEquals("1 live · 3 later", HomeFeed.countsText(c))
        assertEquals("Nothing live · 2 later", HomeFeed.countsText(HomeFeed.Counts(0, 2)))
        assertEquals("2 live", HomeFeed.countsText(HomeFeed.Counts(2, 0)))
        assertEquals("Nothing listed", HomeFeed.countsText(HomeFeed.Counts(0, 0)))
    }

    @Test fun whenLine() {
        val z = TimeZone.getTimeZone("UTC")
        assertEquals("Started 25 min ago", HomeFeed.whenLine(item("a", now - 25 * min), now, z, Locale.US))
        assertEquals("Starts in 12 min", HomeFeed.whenLine(item("a", now + 12 * min), now, z, Locale.US))
        assertEquals("Live", HomeFeed.whenLine(item("a", null, live = true), now, z, Locale.US))
        assertEquals("", HomeFeed.whenLine(item("a", null), now, z, Locale.US))
    }

    @Test fun badges() {
        val z = TimeZone.getTimeZone("UTC")
        assertEquals("LIVE", HomeFeed.badge(item("a", now - min), ListStatus.LIVE, now, z, Locale.US))
        assertEquals("IN 12 MIN", HomeFeed.badge(item("a", now + 12 * min - 1000), ListStatus.SOON, now, z, Locale.US))
        assertEquals("", HomeFeed.badge(item("a", null), ListStatus.UNKNOWN, now, z, Locale.US))
    }

    @Test fun whenLineAlwaysOn() {
        val z = TimeZone.getTimeZone("UTC")
        assertEquals("Started 3 h 00 min ago", HomeFeed.whenLine(item("a", now - 3 * hour), now, z, Locale.US))
        assertEquals("Always on", HomeFeed.whenLine(item("a", now - 1234 * hour), now, z, Locale.US))
        assertEquals("Always on", HomeFeed.whenLine(item("a", now - 25 * hour), now, z, Locale.US))
    }

    @Test fun realLiveEventsBeforeAlwaysOnChannels() {
        val a = SavedList("a", "A", "https://a.example")
        val rows = HomeFeed.liveRow(listOf(a to listOf(item("channel", now - 30 * 24 * hour), item("game", now - 20 * min))), now)
        assertEquals(listOf("game", "channel"), rows.map { it.item.title })
    }
}
