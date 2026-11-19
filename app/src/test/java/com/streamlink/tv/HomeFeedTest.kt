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

    // ---- narrow row (user's channel words) ----
    private fun ts(d: String, s: String, a: String, l: String, n: String) = TeamSide(d, s, a, l, n, "0", "")
    private val wsh = ts("Washington Commanders", "Commanders", "WSH", "Washington", "Commanders")
    private val nyg = ts("New York Giants", "Giants", "NYG", "New York", "Giants")
    private fun game(id: String, start: Long, state: String) = Game(id, "nfl", start, state, "", 0, "", wsh, nyg, "")
    private val words = ChannelFilter.DEFAULT_WORDS

    @Test fun narrowKeepsUpcomingMatchedGamesAndWordChannelsOnly() {
        val a = SavedList("a", "A", "https://a.example")
        val items = listOf(
            item("Giants at Commanders", now + 2 * hour),   // matched, later: kept
            item("Fox Footy", null, live = true),            // channel, no word: dropped
            item("NFL Network", null, live = true),          // channel with a word: kept
            item("nba tv hd", null, live = true),            // case-insensitive: kept
            item("Cricket One vs Cricket Two", now - 10 * min), // unmatched event: dropped
            item("ESPN vs Fox", now - 10 * min),             // two-sided, unmatched: not a channel: dropped
        )
        val rows = HomeFeed.liveRow(listOf(a to items), now, games = listOf(game("1", now + 2 * hour, "pre")), words = words)
        assertEquals(listOf("NFL Network", "nba tv hd", "Giants at Commanders"), rows.map { it.item.title })
    }

    @Test fun narrowKeepsLiveMatchedGamesSoTheyNeverVanish() {
        val a = SavedList("a", "A", "https://a.example")
        val items = listOf(item("Giants at Commanders", now - 20 * min), item("ESPN 2", null, live = true))
        val rows = HomeFeed.liveRow(listOf(a to items), now, games = listOf(game("1", now - 20 * min, "in")), words = words)
        assertEquals(listOf("Giants at Commanders", "ESPN 2"), rows.map { it.item.title })
    }

    @Test fun narrowSkipsFinishedMatchedGames() {
        val a = SavedList("a", "A", "https://a.example")
        val rows = HomeFeed.liveRow(listOf(a to listOf(item("Giants at Commanders", now - 5 * hour))), now, games = listOf(game("1", now - 5 * hour, "post")), words = words)
        assertTrue(rows.isEmpty())
    }

    @Test fun liveMatchedGameInLiveGamesIsNotRepeatedButUntaggedOneShows() {
        val tagged = SavedList("t", "T", "https://t.example", setOf("nfl"))
        val untagged = SavedList("u", "U", "https://u.example")
        val g = listOf(game("1", now - 20 * min, "in"))
        fun cache(title: String, url: String) = CachedList(ListResult(url, "T", listOf(ListItem(title, "", url + "/g", now - 20 * min, "", "", false, null)), "automatic", null), now)
        val z = java.time.ZoneId.of("UTC")
        val m1 = HomeModel.build(listOf(tagged), mapOf("t" to cache("Giants at Commanders", "https://t.example")), g, now, z)
        assertTrue(m1.liveRows.isEmpty())
        val m2 = HomeModel.build(listOf(untagged), mapOf("u" to cache("Giants at Commanders", "https://u.example")), g, now, z)
        assertEquals(listOf("Giants at Commanders"), m2.liveRows.map { it.item.title })
    }

    @Test fun noWordsMeansNoChannels() {
        val a = SavedList("a", "A", "https://a.example")
        val rows = HomeFeed.liveRow(listOf(a to listOf(item("ESPN 2", null, live = true))), now, games = emptyList(), words = emptyList())
        assertTrue(rows.isEmpty())
    }

    @Test fun nullWordsKeepsOldBehaviour() {
        val a = SavedList("a", "A", "https://a.example")
        assertEquals(1, HomeFeed.liveRow(listOf(a to listOf(item("Fox Footy", null, live = true))), now, games = emptyList()).size)
    }

    @Test fun homeLookChangesWithTheWords() {
        val a = SavedList("a", "A", "https://a.example")
        val c = CachedList(ListResult("https://a.example", "T", listOf(item("ESPN 2", null, live = true), item("Fox Footy", null, live = true)), "automatic", null), now)
        val m1 = HomeModel.build(listOf(a), mapOf("a" to c), emptyList(), now, java.time.ZoneId.of("UTC"), listOf("ESPN"))
        val m2 = HomeModel.build(listOf(a), mapOf("a" to c), emptyList(), now, java.time.ZoneId.of("UTC"), listOf("ESPN", "Footy"))
        assertEquals(listOf("ESPN 2"), m1.liveRows.map { it.item.title })
        assertEquals(listOf("ESPN 2", "Fox Footy"), m2.liveRows.map { it.item.title })
        assertFalse(m1.look == m2.look)
    }
}
