package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListReadRulesTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L
    private val side = TeamSide("A", "A", "A", "A", "A", "", "")
    private fun game(league: String, state: String, start: Long) = Game("g", league, start, state, "", 0, "", side, side, "")
    private fun item(start: Long?) = ListItem("x", "", "https://s.example/1", start, "", "", false, null)
    private val upcoming: (ListItem) -> String = { ListStatus.UPCOMING }

    @Test
    fun backoffWaitsAfterThreeBadReads() {
        assertEquals(0L, ListReadLogic.backoffMs(0))
        assertEquals(0L, ListReadLogic.backoffMs(2))
        assertEquals(30 * min, ListReadLogic.backoffMs(3))
        assertEquals(120 * min, ListReadLogic.backoffMs(4))
        assertEquals(120 * min, ListReadLogic.backoffMs(9))
        assertTrue(ListReadLogic.isBad(ListResult("u", "", emptyList(), "automatic", null)))
        assertTrue(ListReadLogic.isBad(ListResult("u", "", listOf(item(now)), "automatic", "oops")))
        assertFalse(ListReadLogic.isBad(ListResult("u", "", listOf(item(now)), "automatic", null)))
    }

    @Test
    fun quietListsWaitForTheirSport() {
        val read = now - 20 * min
        val tomorrow = listOf(item(now + 20 * 60 * min))
        // Nothing on: saved copy is enough
        assertFalse(ListReadLogic.worthReading(setOf("nfl"), tomorrow, upcoming, listOf(game("nfl", "pre", now + 40 * 60 * min)), read, now))
        // Its sport has a game live, or starting within 2 h
        assertTrue(ListReadLogic.worthReading(setOf("nfl"), tomorrow, upcoming, listOf(game("nfl", "in", now - 60 * min)), read, now))
        assertTrue(ListReadLogic.worthReading(setOf("nfl"), tomorrow, upcoming, listOf(game("nfl", "pre", now + 90 * min)), read, now))
        // Another sport's live game doesn't count
        assertFalse(ListReadLogic.worthReading(setOf("nfl"), tomorrow, upcoming, listOf(game("nba", "in", now)), read, now))
        // One of its own events is live, or starts soon
        assertTrue(ListReadLogic.worthReading(emptySet(), tomorrow, { ListStatus.LIVE }, emptyList(), read, now))
        assertTrue(ListReadLogic.worthReading(emptySet(), listOf(item(now + 60 * min)), upcoming, emptyList(), read, now))
        // Never read, or read 2 h ago: always
        assertTrue(ListReadLogic.worthReading(emptySet(), tomorrow, upcoming, emptyList(), null, now))
        assertTrue(ListReadLogic.worthReading(emptySet(), tomorrow, upcoming, emptyList(), now - 121 * min, now))
    }
}
