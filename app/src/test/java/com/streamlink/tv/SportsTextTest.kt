package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SportsTextTest {
    private val zone = ZoneId.of("UTC")
    private fun ts(d: String, s: String, a: String, score: String = "0") = TeamSide(d, s, a, "", s, score, "")
    private val buf = ts("Buffalo Bills", "Bills", "BUF")
    private val kc = ts("Kansas City Chiefs", "Chiefs", "KC")
    private val start = Instant.parse("2026-11-19T19:15:00Z").toEpochMilli()

    private fun game(state: String = "pre", broadcast: String = "", away: TeamSide = buf, home: TeamSide = kc) =
        Game("1", "nfl", start, state, "", 0, "", home, away, broadcast)

    private fun item(url: String) = ListItem("t", "", url, null, "", "", false, null)
    private fun folder(g: Game, n: Int) =
        GameFolder(g, (1..n).map { GameStream("l", "List", item("https://x/$it")) })

    @Test fun matchupNames() {
        assertEquals("Bills at Chiefs", SportsText.matchup(game()))
        val bare = ts("Ohio State Buckeyes", "", "")
        assertEquals("Ohio State Buckeyes at Chiefs", SportsText.matchup(game(away = bare)))
    }

    @Test fun streamsWording() {
        assertEquals("No streams", SportsText.streamsText(0))
        assertEquals("1 stream", SportsText.streamsText(1))
        assertEquals("3 streams", SportsText.streamsText(3))
    }

    @Test fun gameMetaShowsKickoffOrScore() {
        val pre = SportsText.gameMeta(game(broadcast = "CBS"), zone)
        assertTrue(pre, pre.startsWith("7:15"))
        assertTrue(pre, pre.endsWith("CBS"))
        val live = game(state = "in", away = buf.copy(score = "10"), home = kc.copy(score = "7"))
        val line = SportsText.gameMeta(live, zone)
        assertTrue(line, line.startsWith("BUF 10 – KC 7"))
        assertTrue(line, !line.contains("PM"))
    }

    @Test fun tileLine() {
        assertEquals("No list tagged yet", SportsText.sportTileLine(0, null))
        assertEquals("No streams yet", SportsText.sportTileLine(1, null))
        val none = SportFolders("nfl", listOf(folder(game(), 0)), emptyList())
        assertEquals("No streams yet", SportsText.sportTileLine(1, none))
        val some = SportFolders("nfl", listOf(folder(game(), 2), folder(game(), 0)), emptyList())
        assertEquals("1 with streams", SportsText.sportTileLine(1, some))
        val live = SportFolders("nfl", listOf(folder(game("in"), 1), folder(game("in"), 0), folder(game(), 3)), emptyList())
        assertEquals("1 live · 2 with streams", SportsText.sportTileLine(2, live))
    }

    @Test fun alwaysOnLine() {
        assertEquals("No channels yet", SportsText.alwaysOnLine(0))
        assertEquals("1 channel", SportsText.alwaysOnLine(1))
        assertEquals("5 channels", SportsText.alwaysOnLine(5))
    }

    @Test fun emptyMessages() {
        val empty = SportFolders("nfl", emptyList(), emptyList())
        val noStreams = SportFolders("nfl", listOf(folder(game(), 0)), emptyList())
        assertEquals("Tag a stream list as NFL on the phone page (Lists tab) to see games here.", SportsText.emptyMessage("nfl", 0, empty, true))
        assertEquals("No NFL games today or in the next 2 days.", SportsText.emptyMessage("nfl", 1, empty, false))
        assertEquals("No games with streams yet. Turn on All games to see the schedule.", SportsText.emptyMessage("nfl", 1, noStreams, true))
        assertTrue(SportsText.emptyMessage("cfb", 0, empty, true).contains("College football"))
    }

    @Test fun chip() {
        assertEquals("All games: Off", SportsText.allGamesChip(false))
        assertEquals("All games: On", SportsText.allGamesChip(true))
    }

    @Test fun nbaEmptyMessage() {
        val empty = SportFolders("nba", emptyList(), emptyList())
        assertEquals("No NBA games today or in the next 2 days.", SportsText.emptyMessage("nba", 1, empty, false))
    }
}
