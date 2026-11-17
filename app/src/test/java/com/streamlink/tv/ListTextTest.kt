package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class ListTextTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val us = Locale.US
    private val min = 60_000L
    private val hour = 60 * min
    // Wednesday 2026-06-03 10:00 UTC
    private val now = 1_780_480_800_000L

    private fun item(start: Long?, title: String = "An event", live: Boolean = false, timeText: String = "") =
        ListItem(title, "", "https://events.example/e/one", start, timeText, "", live, null)

    @Test fun agoText() {
        assertEquals("never", ListText.ago(0, now))
        assertEquals("just now", ListText.ago(now - 20_000, now))
        assertEquals("3 min ago", ListText.ago(now - 3 * min - 5000, now))
        assertEquals("2 h ago", ListText.ago(now - 2 * hour - min, now))
        assertEquals("3 days ago", ListText.ago(now - 73 * hour, now))
        assertEquals("just now", ListText.ago(now + 5 * min, now)) // clock skew never shows a negative time
    }

    @Test fun durationAndCountdown() {
        assertEquals("45 s", ListText.duration(45_000))
        assertEquals("12 min", ListText.duration(12 * min))
        assertEquals("1 h 05 min", ListText.duration(hour + 5 * min))
        assertEquals("starts in 12 min", ListText.countdown(now + 12 * min, now))
        assertEquals("started 5 min ago", ListText.countdown(now - 5 * min, now))
        assertEquals("no start time", ListText.countdown(null, now))
    }

    @Test fun badges() {
        assertEquals("LIVE", ListText.badge(item(now - 20 * min), now, utc, us))
        assertEquals("LIVE", ListText.badge(item(null, live = true), now, utc, us))
        assertEquals("Starts in 12 min", ListText.badge(item(now + 12 * min), now, utc, us))
        assertEquals("Starts in 1 min", ListText.badge(item(now + 10_000), now, utc, us))
        assertEquals("Today 1:30 PM", ListText.badge(item(now + 3 * hour + 30 * min), now, utc, us))
        assertEquals("Tomorrow 10:00 AM", ListText.badge(item(now + 24 * hour), now, utc, us))
        assertEquals("Sat 10:00 AM", ListText.badge(item(now + 72 * hour), now, utc, us))
        assertEquals("Fri, Jun 12 10:00 AM", ListText.badge(item(now + 9 * 24 * hour), now, utc, us))
        assertEquals("Unknown time", ListText.badge(item(null), now, utc, us))
        assertEquals("Doors at 7", ListText.badge(item(null, timeText = " Doors at 7 "), now, utc, us))
    }

    @Test fun dayBoundaryUsesTheZone() {
        // 23:30 local vs 00:30 local the next day, in a zone 5 hours behind UTC.
        val z = TimeZone.getTimeZone("GMT-05:00")
        val late = 1_780_540_200_000L // 2026-06-04 02:30 UTC = 2026-06-03 21:30 local
        assertEquals("Today 9:30 PM", ListText.whenText(late, now, z, us))
        assertEquals("Tomorrow 12:30 AM", ListText.whenText(late + 3 * hour, now, z, us))
    }

    @Test fun blankTitleFallsBackToTheLinkPath() {
        assertEquals("An event", ListText.displayTitle(item(null)))
        assertEquals("e/one", ListText.displayTitle(item(null, title = "   ")))
        val noPath = ListItem("", "", "https://events.example/", null, "", "", false, null)
        assertEquals("events.example", ListText.displayTitle(noPath))
        assertEquals("Untitled event", ListText.displayTitle(ListItem("", "", "", null, "", "", false, null)))
    }

    @Test fun waitingDecision() {
        assertTrue(ListText.shouldWait(now + 5 * hour, now))      // later
        assertTrue(ListText.shouldWait(now - 10 * min, now))      // just started
        assertFalse(ListText.shouldWait(now - 31 * min, now))     // started a while ago: site player
        assertTrue(ListText.shouldWait(null, now))                // from a list, no time
    }

    @Test fun giveUpRule() {
        val start = now - hour
        assertFalse(ListText.gaveUp(start, now - 10 * hour, now))              // 1 h after start
        assertTrue(ListText.gaveUp(now - 4 * hour, now - 10 * hour, now))      // 4 h after start
        assertFalse(ListText.gaveUp(now + 10 * hour, now - 2 * hour, now))     // far in the future: keep waiting
        assertFalse(ListText.gaveUp(null, now - 2 * hour, now))                // no time: 3 h of waiting
        assertTrue(ListText.gaveUp(null, now - 3 * hour - min, now))
    }

    @Test fun visibleHidesFinishedAndSorts() {
        val live = item(now - hour, "live")
        val later = item(now + 5 * hour, "later")
        val soon = item(now + 10 * min, "soon")
        val done = item(now - 6 * hour, "done")
        val unknown = item(null, "unknown")
        val shown = ListJson.visible(listOf(done, later, unknown, soon, live), now).map { it.title }
        assertEquals(listOf("live", "soon", "later", "unknown"), shown)
    }

    @Test fun cacheJsonRoundTrip() {
        val nasty = "<img src=x onerror=alert(1)> \"quoted\" \\ and\nnewline"
        val r = ListResult("https://events.example/index", "Index", listOf(
            ListItem(nasty, "sub", "https://events.example/e/1", now, "Sat", "Heading", true, "https://events.example/i.png"),
            ListItem("", "", "https://events.example/e/2", null, "", "", false, null),
        ), "automatic", null)
        val back = ListJson.resultFromJson(org.json.JSONObject(ListJson.resultToJson(r).toString()))
        assertEquals(r, back)
        assertNull(back.error)
        val withError = r.copy(error = "boom")
        assertEquals("boom", ListJson.resultFromJson(org.json.JSONObject(ListJson.resultToJson(withError).toString())).error)
    }

    @Test fun phoneJsonKeepsTextAsData() {
        val title = "<b>x</b>"
        val l = SavedList("a1", "Mine", "https://events.example/index")
        val c = CachedList(ListResult(l.url, "T", listOf(item(now + 5 * hour, title)), "automatic", null), now - 3 * min)
        val o = org.json.JSONObject(ListJson.listForPhone(l, c, false, null, now))
        assertEquals("a1", o.getString("id"))
        assertEquals("3 min ago", o.getString("ago"))
        val i = o.getJSONArray("items").getJSONObject(0)
        assertEquals(title, i.getString("title"))
        assertEquals("upcoming", i.getString("status"))
        assertTrue(o.isNull("error"))
        val none = org.json.JSONObject(ListJson.listForPhone(l, null, true, "Couldn't read", now))
        assertEquals(0, none.getJSONArray("items").length())
        assertTrue(none.getBoolean("refreshing"))
    }
}
