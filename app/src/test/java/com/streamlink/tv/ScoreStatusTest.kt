package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class ScoreStatusTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L
    private val hour = 60 * min

    private val wsh = TeamSide("Washington Commanders", "Commanders", "WSH", "Washington", "Commanders", "24", "")
    private val nyg = TeamSide("New York Giants", "Giants", "NYG", "New York", "Giants", "21", "")
    private val title = "Commanders vs Giants"

    private fun game(start: Long, state: String, detail: String = "", name: String = "") =
        Game("1", "nfl", start, state, detail, 0, "", nyg, wsh, "", name)

    private fun item(start: Long?, live: Boolean = false, t: String = title) =
        ListItem(t, "", "https://e.example/" + t.replace(' ', '-'), start, "", "", live, null)

    private fun st(i: ListItem, games: List<Game>) = ListStatus.of(i, now, games)

    @Test fun inProgressLongAfterStartIsLive() {
        val i = item(now - 4 * hour - 30 * min)
        assertEquals(ListStatus.FINISHED, st(i, emptyList()))
        assertEquals(ListStatus.LIVE, st(i, listOf(game(now - 4 * hour - 30 * min, "in"))))
        // weather delay is still "in"
        assertEquals(ListStatus.LIVE, st(i, listOf(game(now - 4 * hour - 30 * min, "in", "Delayed"))))
    }

    @Test fun finalIsFinishedEvenWithLiveHintOrRecentStart() {
        val g = listOf(game(now - 3 * hour, "post", "Final"))
        for (hint in listOf(false, true)) {
            val i = item(now - 3 * hour, live = hint)
            assertEquals(ListStatus.FINISHED, st(i, g))
            assertEquals("Final", ScoreStatus.resolve(i, now, g).badge)
            assertEquals("Final", ListSections.badge(i, now, g).text)
            assertEquals("Final", ListText.badge(i, now, games = g))
        }
    }

    @Test fun postponedAndCanceled() {
        val i = item(now + hour)
        val p = listOf(game(now + hour, "post", "Postponed", "STATUS_POSTPONED"))
        assertEquals(ListStatus.FINISHED, st(i, p))
        assertEquals("Postponed", ListSections.badge(i, now, p).text)
        val c = listOf(game(now + hour, "pre", "Canceled", "STATUS_CANCELED"))
        assertEquals("Canceled", ScoreStatus.resolve(i, now, c).badge)
        // by wording only
        val w = listOf(game(now + hour, "pre", "Postponed"))
        assertEquals(ListStatus.FINISHED, st(i, w))
        assertEquals("Canceled", ScoreStatus.cancelLabel(game(now, "post", "Cancelled")))
        assertNull(ScoreStatus.cancelLabel(game(now, "in", "Delayed")))
    }

    @Test fun preWithNoItemTimeUsesEspnStart() {
        val i = item(null)
        assertEquals(ListStatus.UNKNOWN, st(i, emptyList()))
        assertEquals(ListStatus.UPCOMING, st(i, listOf(game(now + 2 * hour, "pre"))))
        assertEquals(ListStatus.SOON, st(i, listOf(game(now + 10 * min, "pre"))))
        assertEquals(now + 2 * hour, ScoreStatus.resolve(i, now, listOf(game(now + 2 * hour, "pre"))).item.startMillis)
    }

    @Test fun preIgnoresStaleLiveHintUnlessKickoffIsClose() {
        val i = item(now + 2 * hour, live = true)
        assertEquals(ListStatus.LIVE, st(i, emptyList()))
        assertEquals(ListStatus.UPCOMING, st(i, listOf(game(now + 2 * hour, "pre"))))
        assertEquals(ListStatus.SOON, st(i, listOf(game(now + 10 * min, "pre"))))
    }

    @Test fun unmatchedItemsBehaveAsToday() {
        val other = listOf(game(now - hour, "in"))
        val cases = listOf(
            item(null, t = "Chess Open"), item(now + hour, t = "Chess Open"), item(now + 10 * min, t = "Chess Open"),
            item(now - hour, t = "Chess Open"), item(now - 6 * hour, t = "Chess Open"), item(now - 3 * 24 * hour, t = "Chess Open"),
            item(null, live = true, t = "Chess Open"), item(now + 3 * hour, live = true, t = "Chess Open"),
        )
        for (i in cases) {
            assertEquals(ListStatus.byTime(i, now), st(i, other))
            assertEquals(ListStatus.byTime(i, now), st(i, emptyList()))
            assertNull(ScoreStatus.resolve(i, now, other).badge)
            assertEquals(i, ScoreStatus.resolve(i, now, other).item)
        }
    }

    @Test fun staleDataIsNotFresh() {
        assertFalse(ScoreFeed.isFresh(null, now))
        assertTrue(ScoreFeed.isFresh(now, now))
        assertTrue(ScoreFeed.isFresh(now - ScoreFeed.MAX_AGE_MS, now))
        assertFalse(ScoreFeed.isFresh(now - ScoreFeed.MAX_AGE_MS - 1, now))
    }

    @Test fun latestWithNoFetchesIsEmpty() {
        assertTrue(ScoreFeed.latest().isEmpty())
    }

    @Test fun matchIsCachedPerGamesList() {
        val games = listOf(game(now, "in"))
        val a = ScoreMatch.find(title, now, games)
        assertEquals("1", a?.id)
        assertTrue(a === ScoreMatch.find(title, now, games))
        assertNull(ScoreMatch.find(title, now, listOf(game(now + 40 * hour, "in"))))
    }

    @Test fun sortingUsesEspnStatus() {
        val live = item(now - 5 * hour)                 // would be finished by time
        val fin = item(now - hour, t = "Chess Open")    // live by time
        val g = listOf(game(now - 5 * hour, "in"))
        assertEquals(listOf(live, fin), ListStatus.sorted(listOf(fin, live), now, g))
        val g2 = listOf(game(now - 5 * hour, "post"))
        assertEquals(listOf(fin, live), ListStatus.sorted(listOf(live, fin), now, g2))
    }

    @Test fun sectionsAgreeWithOverride() {
        val live = item(now - 5 * hour)
        val done = item(now - hour, live = true, t = "Chess Open")
        val g = listOf(game(now - 5 * hour, "in"))
        val sections = ListSections.group(ListStatus.sorted(listOf(done, live), now, g), now, g)
        assertEquals(listOf(ListStatus.LIVE, ListStatus.LIVE), sections.flatMap { s -> s.items.map { s.status } })
        val g2 = listOf(game(now - 5 * hour, "post"))
        val s2 = ListSections.group(ListStatus.sorted(listOf(live, done), now, g2), now, g2)
        assertEquals(listOf(ListStatus.LIVE, ListStatus.FINISHED), s2.map { it.status })
        assertEquals("Final", ListSections.badge(live, now, g2).text)
        // kickoff from ESPN shows in the meta line for an item with no time
        val nt = item(null)
        val g3 = listOf(game(now + 5 * hour, "pre"))
        val meta = ListSections.meta(nt, now, TimeZone.getTimeZone("UTC"), Locale.US, g3)
        assertTrue(meta, meta.contains("PM") || meta.contains("AM"))
    }

    @Test fun homeCountsAgreeWithOverride() {
        val live = item(now - 5 * hour)
        val none = item(null, t = "Chess Open")
        val g = listOf(game(now - 5 * hour, "in"))
        assertEquals(1, HomeFeed.counts(listOf(live, none), now, g).live)
        assertEquals(0, HomeFeed.counts(listOf(live), now, listOf(game(now - 5 * hour, "post"))).live)
    }

    @Test fun cardTextForPostponed() {
        val g = game(now, "post", "Postponed", "STATUS_POSTPONED")
        assertEquals("Postponed", ScoreText.rowLine(g))
        assertEquals("Postponed", ScoreText.cardLines(g)[1])
    }

    @Test fun finalNbaGameIsFinishedEvenWithLiveHint() {
        val cha = TeamSide("Charlotte Hornets", "Hornets", "CHA", "Charlotte", "Hornets", "90", "")
        val lal = TeamSide("Los Angeles Lakers", "Lakers", "LAL", "Los Angeles", "Lakers", "95", "")
        val g = listOf(Game("9", "nba", now - 3 * hour, "post", "Final", 4, "0.0", lal, cha, "", "STATUS_FINAL"))
        for (hint in listOf(false, true)) {
            val i = item(now - 3 * hour, live = hint, t = "Los Angeles Lakers vs Charlotte Hornets")
            assertEquals(ListStatus.FINISHED, st(i, g))
            assertEquals("Final", ScoreStatus.resolve(i, now, g).badge)
        }
    }
}
