package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class GamesTest {
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-11-19T15:00:00Z").toEpochMilli() // a Thursday
    private val hour = 3_600_000L
    private fun at(s: String) = Instant.parse(s).toEpochMilli()

    private fun ts(d: String, s: String, a: String, l: String, n: String, score: String = "0") = TeamSide(d, s, a, l, n, score, "")
    private val wsh = ts("Washington Commanders", "Commanders", "WSH", "Washington", "Commanders")
    private val nyg = ts("New York Giants", "Giants", "NYG", "New York", "Giants")
    private val buf = ts("Buffalo Bills", "Bills", "BUF", "Buffalo", "Bills")
    private val nyj = ts("New York Jets", "Jets", "NYJ", "New York", "Jets")
    private val chi = ts("Chicago Bears", "Bears", "CHI", "Chicago", "Bears")
    private val det = ts("Detroit Lions", "Lions", "DET", "Detroit", "Lions")
    private val mia = ts("Miami Dolphins", "Dolphins", "MIA", "Miami", "Dolphins")
    private val ne = ts("New England Patriots", "Patriots", "NE", "New England", "Patriots")
    private val osu = ts("Ohio State Buckeyes", "Ohio State", "OSU", "Ohio State", "Buckeyes")
    private val mich = ts("Michigan Wolverines", "Michigan", "MICH", "Michigan", "Wolverines")

    private fun game(id: String, away: TeamSide, home: TeamSide, start: Long, state: String = "pre", league: String = "nfl") =
        Game(id, league, start, state, "", 0, "", home, away, "")

    private fun item(title: String, url: String, start: Long? = null, sub: String = "", live: Boolean = false) =
        ListItem(title, sub, url, start, "", "", live, null)

    private fun list(id: String, sport: String = "nfl") = SavedList(id, "List $id", "https://$id.example", sport)

    private val gWsh = game("1", wsh, nyg, at("2026-11-19T20:00:00Z"))
    private val gBuf = game("2", buf, nyj, at("2026-11-20T18:00:00Z"))
    private val gChi = game("3", chi, det, at("2026-11-21T18:00:00Z"))

    private fun build(lists: List<Pair<SavedList, List<ListItem>>>, games: List<Game> = listOf(gWsh, gBuf, gChi), league: String = "nfl") =
        GameFolders.build(league, games, lists, now, zone)

    @Test fun matchesAcrossTwoListsIntoOneGameInListOrder() {
        val f = build(listOf(
            list("a") to listOf(item("Commanders vs Giants", "https://a.example/1")),
            list("b") to listOf(item("Giants at Commanders HD", "https://b.example/9"), item("Bills at Jets", "https://b.example/2")),
        ))
        assertEquals(3, f.games.size)
        val g = f.games.first { it.game.id == "1" }
        assertEquals(listOf("a", "b"), g.streams.map { it.listId })
        assertEquals(listOf("https://a.example/1", "https://b.example/9"), g.streams.map { it.item.url })
        assertEquals(1, f.games.first { it.game.id == "2" }.streams.size)
        assertTrue(f.other.isEmpty())
    }

    @Test fun duplicateUrlsAreKeptOnceFirstWins() {
        val f = build(listOf(
            list("a") to listOf(item("Commanders vs Giants", "https://x.example/e/1/")),
            list("b") to listOf(item("Commanders vs Giants again", "https://x.example/e/1"), item("Totally unrelated show", "https://x.example/o")),
            list("c") to listOf(item("Another unrelated", "https://x.example/o/")),
        ))
        assertEquals(listOf("a"), f.games.first { it.game.id == "1" }.streams.map { it.listId })
        assertEquals(1, f.other.size)
        assertEquals("b", f.other[0].listId)
    }

    @Test fun unmatchedGoesToOtherAndSubtitleIsTriedSecond() {
        val f = build(listOf(list("a") to listOf(
            item("Premium Channel 7", "https://a.example/ch7"),
            item("Main Event", "https://a.example/me", sub = "Bears vs Lions"),
        )))
        assertEquals(listOf("https://a.example/ch7"), f.other.map { it.item.url })
        assertEquals(1, f.games.first { it.game.id == "3" }.streams.size)
    }

    @Test fun alwaysOnAndFinishedAreNeverOther() {
        val f = build(listOf(list("a") to listOf(
            item("24/7 Football Channel", "https://a.example/247", now - 48 * hour),
            item("Commanders vs Giants replay", "https://a.example/old", now - 30 * hour),
            item("Old unrelated game", "https://a.example/fin", now - 10 * hour),
            item("Unknown time thing", "https://a.example/unk"),
        )))
        assertEquals(listOf("https://a.example/unk"), f.other.map { it.item.url })
        assertTrue(f.games.all { it.streams.isEmpty() })
    }

    @Test fun untaggedAndOtherSportListsAreIgnored() {
        val f = build(listOf(
            list("a", "") to listOf(item("Commanders vs Giants", "https://a.example/1")),
            list("b", "cfb") to listOf(item("Commanders vs Giants", "https://b.example/1")),
        ))
        assertTrue(f.games.all { it.streams.isEmpty() })
        assertTrue(f.other.isEmpty())
    }

    @Test fun windowEdges() {
        val yestFinal = game("y", wsh, nyg, at("2026-11-18T20:00:00Z"), "post")
        val yestLive = game("yl", buf, nyj, at("2026-11-18T23:30:00Z"), "in")
        val dayStart = game("d0", chi, det, at("2026-11-19T00:00:00Z"), "post")
        val lastMinute = game("d2", mia, ne, at("2026-11-21T23:59:00Z"))
        val day3 = game("d3", osu, mich, at("2026-11-22T00:00:00Z"))
        val f = build(emptyList(), listOf(yestFinal, yestLive, dayStart, lastMinute, day3))
        assertEquals(setOf("yl", "d0", "d2"), f.games.map { it.game.id }.toSet())
    }

    @Test fun onlyGamesOfTheLeagueAreUsed() {
        val c = game("c1", osu, mich, at("2026-11-19T20:00:00Z"), league = "cfb")
        val f = build(listOf(list("a", "cfb") to listOf(item("Ohio State vs Michigan", "https://a.example/c"))), listOf(gWsh, c), "cfb")
        assertEquals(listOf("c1"), f.games.map { it.game.id })
        assertEquals(1, f.games[0].streams.size)
    }

    private fun folder(g: Game, vararg urls: String) = GameFolder(g, urls.map { GameStream("a", "A", item("t", it)) })

    @Test fun sectionsOrderLabelsAndFilter() {
        val live = game("L", wsh, nyg, at("2026-11-19T13:00:00Z"), "in")
        val today2 = game("T2", chi, det, at("2026-11-19T20:00:00Z"))
        val today1 = game("T1", buf, nyj, at("2026-11-19T20:00:00Z"))
        val tom = game("M", mia, ne, at("2026-11-20T18:00:00Z"))
        val d3 = game("D", osu, mich, at("2026-11-21T18:00:00Z"))
        val fin1 = game("F1", wsh, nyg, at("2026-11-19T01:00:00Z"), "post")
        val fin2 = game("F2", buf, nyj, at("2026-11-19T10:00:00Z"), "post")
        val f = SportFolders("nfl", listOf(
            folder(d3, "u1"), folder(fin1, "u2"), folder(today2, "u3"), folder(today1), folder(fin2, "u4"),
            folder(live), folder(tom, "u5"),
        ), emptyList())
        val all = GameSections.group(f, now, zone, false)
        assertEquals(listOf("live", "today", "tomorrow", "day3", "final"), all.map { it.key })
        assertEquals(listOf("Live now", "Today", "Tomorrow", "Saturday", "Final"), all.map { it.label })
        assertEquals(listOf("T1", "T2"), all[1].games.map { it.game.id }) // same start: by away team name
        assertEquals(listOf("F2", "F1"), all[4].games.map { it.game.id })
        val some = GameSections.group(f, now, zone, true)
        assertEquals(listOf("today", "tomorrow", "day3", "final"), some.map { it.key })
        assertEquals(listOf("T2"), some[0].games.map { it.game.id })
    }

    @Test fun emptySectionsAreOmitted() {
        val f = SportFolders("nfl", listOf(folder(gBuf, "u")), emptyList())
        assertEquals(listOf("tomorrow"), GameSections.group(f, now, zone, false).map { it.key })
    }

    @Test fun alwaysOnCollectsFromAllListsAndDedupes() {
        val out = AlwaysOn.collect(listOf(
            list("a", "") to listOf(item("Ch 1", "https://a.example/c1", now - 48 * hour), item("Live", "https://a.example/l", now - hour), item("NoTime", "https://a.example/n")),
            list("b", "nfl") to listOf(item("Ch 1 again", "https://a.example/c1/", now - 72 * hour), item("Ch 2", "https://b.example/c2", now - 25 * hour)),
        ), now)
        assertEquals(listOf("https://a.example/c1", "https://b.example/c2"), out.map { it.item.url })
        assertEquals(listOf("a", "b"), out.map { it.listId })
    }

    @Test fun liveGamesOrderingAndMatchedUrls() {
        val l1 = game("c-early", osu, mich, at("2026-11-19T12:00:00Z"), "in", "cfb")
        val l2 = game("nfl-same", wsh, nyg, at("2026-11-19T13:00:00Z"), "in")
        val l3 = game("cfb-same", chi, det, at("2026-11-19T13:00:00Z"), "in", "cfb")
        val l4 = game("nostreams", buf, nyj, at("2026-11-19T11:00:00Z"), "in")
        val pre = game("pre", mia, ne, at("2026-11-19T20:00:00Z"))
        val nfl = SportFolders("nfl", listOf(folder(l2, "https://x/2/"), folder(l4), folder(pre, "https://x/pre")), emptyList())
        val cfb = SportFolders("cfb", listOf(folder(l3, "https://x/3"), folder(l1, "https://x/1")), emptyList())
        assertEquals(listOf("c-early", "nfl-same", "cfb-same"), LiveGames.sample(listOf(cfb, nfl)).map { it.game.id })
        assertEquals(2, LiveGames.sample(listOf(cfb, nfl), 2).size)
        val urls = LiveGames.matchedUrls(listOf(nfl, cfb))
        assertTrue("https://x/2/" in urls && "https://x/2" in urls && "https://x/1" in urls && "https://x/3" in urls)
        assertTrue("https://x/pre" !in urls)
    }

    @Test fun summaryLine() {
        val f = SportFolders("nfl", listOf(folder(gWsh, "a", "b"), folder(gBuf, "c"), folder(gChi)), listOf(GameStream("a", "A", item("t", "o"))))
        assertEquals("Sports: NFL 3 games (2 with streams), 3 streams, 1 other", GameFolders.summary(f))
    }

    @Test fun savedListJsonRoundTripAndMissingSport() {
        val lists = listOf(SavedList("a", "One", "https://a.example", "nfl"), SavedList("b", "Two", "https://b.example"))
        val back = SavedListsJson.parse(SavedListsJson.toJson(lists))
        assertEquals(lists, back)
        assertEquals("", back[1].sport)
        val old = SavedListsJson.parse("""[{"id":"x","name":"Old","url":"https://o.example"},{"id":"y","name":"Odd","url":"https://p.example","sport":"rugby"},{"id":"z","name":"C","url":"https://q.example","sport":"cfb"}]""")
        assertEquals(listOf("", "", "cfb"), old.map { it.sport })
        assertEquals(emptyList<SavedList>(), SavedListsJson.parse("not json"))
    }

    @Test fun sportsCleanAndLabels() {
        assertEquals("nfl", Sports.clean(" NFL "))
        assertEquals("", Sports.clean(null))
        assertEquals("", Sports.clean("hockey"))
        assertEquals("College football", Sports.label("cfb"))
    }

    @Test fun nbaSportAndFolders() {
        assertEquals("nba", Sports.clean(" NBA "))
        assertEquals("NBA", Sports.label("nba"))
        assertEquals(listOf("nfl", "cfb", "nba"), Sports.ALL)
        val lal = ts("Los Angeles Lakers", "Lakers", "LAL", "Los Angeles", "Lakers")
        val bos = ts("Boston Celtics", "Celtics", "BOS", "Boston", "Celtics")
        val g = game("b1", bos, lal, at("2026-11-19T20:00:00Z"), league = "nba")
        val f = build(listOf(list("a", "nba") to listOf(item("Lakers vs Celtics", "https://a.example/b"))), listOf(gWsh, g), "nba")
        assertEquals(listOf("b1"), f.games.map { it.game.id })
        assertEquals(1, f.games[0].streams.size)
        val live = game("b2", bos, lal, at("2026-11-19T13:00:00Z"), "in", "nba")
        val l3 = game("cfb-same", chi, det, at("2026-11-19T13:00:00Z"), "in", "cfb")
        val l2 = game("nfl-same", wsh, nyg, at("2026-11-19T13:00:00Z"), "in")
        val order = LiveGames.sample(listOf(SportFolders("nba", listOf(folder(live, "https://x/b")), emptyList()),
            SportFolders("cfb", listOf(folder(l3, "https://x/c")), emptyList()),
            SportFolders("nfl", listOf(folder(l2, "https://x/n")), emptyList())))
        assertEquals(listOf("nfl-same", "cfb-same", "b2"), order.map { it.game.id })
    }
}
