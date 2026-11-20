package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameTilesTest {
    private fun ts(abbr: String, score: String = "0") = TeamSide("$abbr Team", abbr, abbr, abbr, abbr, score, "")
    private fun game(id: String, state: String = "in", start: Long = 1000L, league: String = "nfl", a: String = "A$id", h: String = "H$id") =
        Game(id, league, start, state, "", 3, "4:12", ts(h, "21"), ts(a, "17"), "")
    private fun stream(list: String, url: String) = GameStream(list, "List $list", ListItem("t", "", url, null, "", "", false, null))
    private fun folder(g: Game, vararg s: GameStream) = GameFolder(g, s.toList())
    private fun nfl(vararg f: GameFolder) = listOf(SportFolders("nfl", f.toList(), emptyList()))

    @Test fun currentGameFoundByItsStreamAddress() {
        val folders = nfl(folder(game("1"), stream("a", "https://x.example/1/")), folder(game("2")))
        assertEquals("nfl" to "1", GameTiles.current(folders, "https://x.example/1", "", emptyList()))
    }

    @Test fun redZoneCountsAsNflWithNoGame() {
        assertEquals("nfl" to null, GameTiles.current(emptyList(), null, "NFL RedZone", emptyList()))
        assertNull(GameTiles.current(emptyList(), null, "Some cooking show", emptyList()))
    }

    @Test fun leavesOutCurrentAndNotLiveGames_streamsFirstThenStart() {
        val folders = nfl(
            folder(game("1"), stream("a", "u1")),
            folder(game("2", start = 500L)),
            folder(game("3", start = 2000L), stream("b", "u3")),
            folder(game("4", state = "pre"), stream("a", "u4")),
            folder(game("5", state = "post"), stream("a", "u5")),
            folder(game("6", start = 900L), stream("a", "u6")),
        )
        val t = GameTiles.pick(folders, "nfl", "1")!!
        assertEquals(listOf("6", "3", "2"), t.tiles.map { it.game.id })
        assertEquals(0, t.more)
        assertNull(t.tiles.last().stream)
    }

    @Test fun usesTheBestRankedStream() {
        val folders = nfl(folder(game("2"), stream("best", "u-best"), stream("worse", "u-worse")))
        assertEquals("u-best", GameTiles.pick(folders, "nfl", null)!!.tiles.single().stream!!.item.url)
    }

    @Test fun eightFitAndMoreBecomeSevenPlusCount() {
        val eight = nfl(*(1..8).map { folder(game("$it", start = it.toLong()), stream("a", "u$it")) }.toTypedArray())
        GameTiles.pick(eight, "nfl", null)!!.let { assertEquals(8, it.tiles.size); assertEquals(0, it.more) }
        val eleven = nfl(*(1..11).map { folder(game("$it", start = it.toLong()), stream("a", "u$it")) }.toTypedArray())
        GameTiles.pick(eleven, "nfl", null)!!.let { assertEquals(7, it.tiles.size); assertEquals(4, it.more) }
    }

    @Test fun nothingWhenNoOtherLiveGame() {
        assertNull(GameTiles.pick(nfl(folder(game("1"), stream("a", "u1"))), "nfl", "1"))
        assertNull(GameTiles.pick(nfl(folder(game("1"))), "nba", null))
    }

    @Test fun textLines() {
        val g = game("1", a = "BUF", h = "KC")
        assertEquals("BUF 17  KC 21", GameTiles.scoreLine(g))
        assertEquals("▶ Q3 4:12", GameTiles.statusLine(g, true))
        assertEquals("Q3 4:12", GameTiles.statusLine(g, false))
    }

    @Test fun sameLookIgnoresNewObjectsButSeesScoreChanges() {
        val a = GameTiles.pick(nfl(folder(game("1"), stream("a", "u1"))), "nfl", null)
        val b = GameTiles.pick(nfl(folder(game("1"), stream("a", "u1"))), "nfl", null)
        assertTrue(GameTiles.sameLook(a, b))
        val c = GameTiles.pick(nfl(folder(game("1").copy(clock = "3:50"), stream("a", "u1"))), "nfl", null)
        assertFalse(GameTiles.sameLook(a, c))
    }
}
