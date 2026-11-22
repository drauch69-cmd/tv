package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFindingTest {
    private val t0 = 1_000_000_000L
    private val nfl = SavedList("a", "A", "https://a.example", setOf("nfl"))
    private val any = SavedList("b", "B", "https://b.example")

    @Test fun unreadListMarksItsSports() {
        assertEquals(setOf("nfl"), HomeFinding.leagues(listOf(nfl), { false }, { 0L }, t0 + 5_000, t0))
        assertEquals(Sports.ALL.toSet(), HomeFinding.leagues(listOf(any), { false }, { 0L }, t0 + 5_000, t0))
    }

    @Test fun readOrFreshOrLateEndsIt() {
        assertTrue(HomeFinding.leagues(listOf(nfl), { true }, { 0L }, t0 + 5_000, t0).isEmpty())
        assertTrue(HomeFinding.leagues(listOf(nfl), { false }, { t0 - 60_000 }, t0 + 5_000, t0).isEmpty())
        assertEquals(setOf("nfl"), HomeFinding.leagues(listOf(nfl), { false }, { t0 - 3_600_000 }, t0 + 5_000, t0))
        assertTrue(HomeFinding.leagues(listOf(nfl), { false }, { 0L }, t0 + HomeFinding.MAX_MS + 1, t0).isEmpty())
    }

    @Test fun streamlessLiveGamesShowOnlyWhileFinding() {
        val side = TeamSide("Chicago Bears", "Bears", "CHI", "Chicago", "Bears", "7", "")
        val other = TeamSide("Detroit Lions", "Lions", "DET", "Detroit", "Lions", "3", "")
        val g = Game("g1", "nfl", t0, "in", "Q2", 2, "5:00", side, other, "")
        val folders = listOf(SportFolders("nfl", listOf(GameFolder(g, emptyList())), emptyList()))
        assertTrue(LiveGames.sample(folders).isEmpty())
        assertEquals(1, LiveGames.sample(folders, finding = setOf("nfl")).size)
        assertTrue(LiveGames.sample(folders, finding = setOf("nba")).isEmpty())
        val pick = FeaturedPick.choose(folders, t0, false, FeaturedPick.State(), finding = setOf("nfl"))
        assertEquals("g1", pick?.folder?.game?.id)
        assertEquals(null, FeaturedPick.choose(folders, t0, false, FeaturedPick.State()))
    }
}
