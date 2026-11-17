package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class ListSectionsTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L
    private val hour = 60 * min

    private fun item(t: String, start: Long?, live: Boolean = false, sub: String = "", sec: String = "", timeText: String = "") =
        ListItem(t, sub, "https://example.com/$t", start, timeText, sec, live, null)

    @Test fun groupsInOrderAndSkipsEmpty() {
        val items = listOf(
            item("fin", now - 5 * hour), item("later", now + 3 * hour), item("live", now - 10 * min),
            item("soon", now + 10 * min), item("none", null),
        )
        val g = ListSections.group(ListJson.visible(items, now), now)
        assertEquals(listOf("LIVE NOW", "STARTING SOON", "LATER", "NO TIME GIVEN", "MAYBE ENDED"), g.map { it.label })
        assertEquals(listOf("live"), g[0].items.map { it.title })
        val g2 = ListSections.group(listOf(item("a", now + 2 * hour)), now)
        assertEquals(listOf("LATER"), g2.map { it.label })
        assertTrue(ListSections.group(emptyList(), now).isEmpty())
    }

    @Test fun startedAgoAndMinutes() {
        assertEquals("started 25 min ago", ListSections.startedAgo(now - 25 * min, now))
        assertEquals("started 1 h 05 min ago", ListSections.startedAgo(now - 65 * min, now))
        assertNull(ListSections.startedAgo(now + min, now))
        assertNull(ListSections.startedAgo(null, now))
        assertEquals("IN 12 MIN", ListSections.inMinutes(item("a", now + 11 * min + 1), now))
        assertEquals("IN 1 MIN", ListSections.inMinutes(item("a", now + 1), now))
    }

    @Test fun badges() {
        assertEquals(ListSections.Badge("LIVE", true, true), ListSections.badge(item("a", now - min), now))
        assertEquals(ListSections.Badge("IN 10 MIN", false, true), ListSections.badge(item("a", now + 10 * min), now))
        assertEquals("Wait for it", ListSections.badge(item("a", now + 2 * hour), now).text)
        assertEquals("Wait for it", ListSections.badge(item("a", null), now).text)
        assertEquals("Maybe ended", ListSections.badge(item("a", now - 6 * hour), now).text)
    }

    @Test fun metaLine() {
        val z = TimeZone.getTimeZone("UTC")
        val l = Locale.US
        assertEquals("Main  ·  started 25 min ago", ListSections.meta(item("a", now - 25 * min, sub = "Main"), now, z, l))
        assertEquals("Sat 9:00", ListSections.meta(item("a", null, timeText = "Sat 9:00"), now, z, l))
        assertEquals("", ListSections.meta(item("a", null), now, z, l))
        // section equal to subtitle is not repeated
        assertEquals("Main", ListSections.meta(item("a", null, sub = "Main", sec = "Main"), now, z, l))
    }
}

class WaitingTextTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L
    private val z = TimeZone.getTimeZone("UTC")

    @Test fun stepStates() {
        assertEquals(listOf(1, 0, 0), WaitingText.steps(0, false))
        assertEquals(listOf(2, 1, 0), WaitingText.steps(1, false))
        assertEquals(listOf(2, 2, 1), WaitingText.steps(2, false))
        assertEquals(listOf(2, 2, 2), WaitingText.steps(0, true))
        assertEquals(listOf(2, 2, 1), WaitingText.steps(9, false))
    }

    @Test fun headline() {
        assertEquals("Starts in 12 min", WaitingText.headline(now + 12 * min, now, z, Locale.US))
        assertTrue(WaitingText.headline(now + 5 * 60 * min, now, z, Locale.US).let { it.startsWith("Today ") || it.startsWith("Tomorrow ") })
        assertEquals("Started 5 min ago", WaitingText.headline(now - 5 * min, now, z, Locale.US))
        assertEquals("Waiting for the stream", WaitingText.headline(null, now, z, Locale.US))
    }
}
