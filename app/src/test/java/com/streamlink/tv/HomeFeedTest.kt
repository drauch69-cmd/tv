package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class HomeFeedTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L
    private val hour = 60 * min
    private fun item(t: String, start: Long?, live: Boolean = false) =
        ListItem(t, "", "https://e.example/$t", start, "", "", live, null)

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

    // ---- RedZone card ----
    private fun ts(d: String, s: String, a: String, l: String, n: String) = TeamSide(d, s, a, l, n, "0", "")
    private val wsh = ts("Washington Commanders", "Commanders", "WSH", "Washington", "Commanders")
    private val nyg = ts("New York Giants", "Giants", "NYG", "New York", "Giants")
    private fun game(id: String, start: Long, state: String) = Game(id, "nfl", start, state, "", 0, "", wsh, nyg, "")
    private val twoLive = listOf(game("1", now - 20 * min, "in"), game("2", now - 20 * min, "in"))

    @Test fun redZoneNeedsTwoLiveNflGames() {
        val a = SavedList("a", "A", "https://a.example")
        val rz = listOf(item("NFL RedZone", now - 30 * 24 * hour))
        assertEquals(null, HomeFeed.redZone(listOf(a to rz), now, listOf(game("1", now - 20 * min, "in"))))
        assertEquals(null, HomeFeed.redZone(listOf(a to rz), now, emptyList()))
        assertEquals("NFL RedZone", HomeFeed.redZone(listOf(a to rz), now, twoLive)?.item?.title)
    }

    @Test fun redZoneOneCardPreferringListOrder() {
        val a = SavedList("a", "A", "https://a.example")
        val b = SavedList("b", "B", "https://b.example")
        val e = HomeFeed.redZone(listOf(a to listOf(item("ESPN", null, live = true), item("Red Zone", null, live = true)), b to listOf(item("NFL RedZone", null, live = true))), now, twoLive)
        assertEquals("A", e?.list?.name)
        assertEquals("Red Zone", e?.item?.title)
        assertEquals(ListStatus.LIVE, e?.status)
    }

    @Test fun redZoneWithALaterStartWaits() {
        val a = SavedList("a", "A", "https://a.example")
        assertEquals(null, HomeFeed.redZone(listOf(a to listOf(item("NFL RedZone", now + 3 * hour))), now, twoLive))
        // no time and no marker: shown while the games are on
        assertEquals("NFL RedZone", HomeFeed.redZone(listOf(a to listOf(item("NFL RedZone", null))), now, twoLive)?.item?.title)
    }

    @Test fun homeModelCarriesRedZoneAndLookChanges() {
        val a = SavedList("a", "A", "https://a.example")
        val c = CachedList(ListResult("https://a.example", "T", listOf(item("NFL RedZone", null, live = true)), "automatic", null), now)
        val z = java.time.ZoneId.of("UTC")
        val off = HomeModel.build(listOf(a), mapOf("a" to c), emptyList(), now, z)
        val on = HomeModel.build(listOf(a), mapOf("a" to c), twoLive, now, z)
        assertEquals(null, off.redZone)
        assertEquals("NFL RedZone", on.redZone?.item?.title)
        assertFalse(off.look == on.look)
    }
}
