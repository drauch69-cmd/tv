package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ScoresTest {
    private val base = 1_800_000_000_000L
    private val min = 60_000L
    private val hour = 60 * min

    // ---- fixtures ----

    private fun jTeam(ha: String, d: String, s: String, a: String, l: String, n: String, score: String = "0", rec: String? = null): String {
        val r = if (rec == null) "" else ""","records":[{"summary":"$rec"}]"""
        return """{"homeAway":"$ha","score":"$score","team":{"displayName":"$d","shortDisplayName":"$s","abbreviation":"$a","location":"$l","name":"$n"}$r}"""
    }

    private fun jEvent(id: String, date: String, state: String, detail: String, comps: String, bcast: String = ""): String =
        """{"id":"$id","date":"$date","competitions":[{"competitors":[$comps]$bcast}],"status":{"displayClock":"8:42","period":3,"type":{"state":"$state","shortDetail":"$detail"}}}"""

    private fun feed(vararg events: String) = """{"events":[${events.joinToString(",")}]}"""

    private fun ts(d: String, s: String, a: String, l: String, n: String, score: String = "0", rec: String = "") =
        TeamSide(d, s, a, l, n, score, rec)

    private fun game(id: String, league: String, away: TeamSide, home: TeamSide, start: Long = base,
                     state: String = "pre", detail: String = "", period: Int = 0, clock: String = "", bc: String = "") =
        Game(id, league, start, state, detail, period, clock, home, away, bc)

    private val wsh = ts("Washington Commanders", "Commanders", "WSH", "Washington", "Commanders", "24", "3-5-1")
    private val nyg = ts("New York Giants", "Giants", "NYG", "New York", "Giants", "21", "5-3-1")
    private val nyj = ts("New York Jets", "Jets", "NYJ", "New York", "Jets")
    private val buf = ts("Buffalo Bills", "Bills", "BUF", "Buffalo", "Bills")
    private val chi = ts("Chicago Bears", "Bears", "CHI", "Chicago", "Bears")
    private val det = ts("Detroit Lions", "Lions", "DET", "Detroit", "Lions")

    private fun nflDay() = listOf(game("1", "nfl", wsh, nyg), game("2", "nfl", buf, nyj), game("3", "nfl", chi, det))

    // ---- parse ----

    @Test fun parsesFields() {
        val json = feed(
            jEvent("401", "2026-11-13T01:15Z", "in", "Q3 - 8:42",
                jTeam("away", "Washington Commanders", "Commanders", "WSH", "Washington", "Commanders", "24", "3-5-1") + "," +
                    jTeam("home", "New York Giants", "Giants", "NYG", "New York", "Giants", "21", "5-3-1"),
                bcast = ""","broadcasts":[{"names":["Prime Video"]}]"""),
            jEvent("402", "2026-11-13T02:00:30Z", "pre", "11/13 - 7:00 PM EST",
                jTeam("away", "Buffalo Bills", "Bills", "BUF", "Buffalo", "Bills") + "," +
                    jTeam("home", "New York Jets", "Jets", "NYJ", "New York", "Jets")),
        )
        val games = ScoreParse.parse(json, "nfl")
        assertEquals(2, games.size)
        val g = games[0]
        assertEquals("401", g.id)
        assertEquals("nfl", g.league)
        assertEquals(Instant.parse("2026-11-13T01:15:00Z").toEpochMilli(), g.startMillis)
        assertEquals("in", g.state)
        assertEquals("Q3 - 8:42", g.shortDetail)
        assertEquals(3, g.period)
        assertEquals("8:42", g.clock)
        assertEquals("WSH", g.away.abbr)
        assertEquals("24", g.away.score)
        assertEquals("3-5-1", g.away.record)
        assertEquals("New York", g.home.location)
        assertEquals("Giants", g.home.nickname)
        assertEquals("Prime Video", g.broadcast)
        assertEquals(Instant.parse("2026-11-13T02:00:30Z").toEpochMilli(), games[1].startMillis)
    }

    @Test fun missingRecordsAndBroadcastsAreEmpty() {
        val json = feed(jEvent("1", "2026-11-13T01:15Z", "pre", "x",
            jTeam("away", "A Team", "A", "AAA", "A", "Team") + "," + jTeam("home", "B Team", "B", "BBB", "B", "Team")))
        val g = ScoreParse.parse(json, "cfb").single()
        assertEquals("", g.away.record)
        assertEquals("", g.broadcast)
    }

    @Test fun badJsonGivesEmpty() {
        assertEquals(emptyList<Game>(), ScoreParse.parse("not json at all", "nfl"))
        assertEquals(emptyList<Game>(), ScoreParse.parse("""{"nothing":1}""", "nfl"))
        assertEquals(emptyList<Game>(), ScoreParse.parse("", "nfl"))
    }

    @Test fun malformedEventIsSkipped() {
        val good = jEvent("9", "2026-11-13T01:15Z", "post", "Final",
            jTeam("away", "A Team", "A", "AAA", "A", "Team") + "," + jTeam("home", "B Team", "B", "BBB", "B", "Team"))
        val json = """{"events":[{"id":"1"},{"id":"2","date":"garbage","competitions":[]},"oops",null,$good]}"""
        val games = ScoreParse.parse(json, "nfl")
        assertEquals(listOf("9"), games.map { it.id })
    }

    // ---- NFL matching ----

    @Test fun nflTitlesMatch() {
        val games = nflDay()
        for (t in listOf("Commanders vs Giants", "WSH @ NYG", "Washington Commanders at New York Giants", "Giants v Commanders | Week 11")) {
            assertEquals(t, "1", ScoreMatch.find(t, base, games)?.id)
        }
    }

    @Test fun oneTeamAloneIsNotAGame() {
        assertNull(ScoreMatch.find("Giants", base, nflDay()))
    }

    @Test fun sharedLocationUsesNicknames() {
        val games = nflDay() // Giants and Jets both play: "New York" is ambiguous
        assertEquals("1", ScoreMatch.find("Commanders at New York Giants", base, games)?.id)
        assertEquals("2", ScoreMatch.find("Bills vs NY Jets", base, games)?.id)
        assertNull(ScoreMatch.find("New York vs Commanders", base, games))
    }

    @Test fun wholeWordsOnly() {
        val games = nflDay()
        assertNull(ScoreMatch.find("Bearsville Festival vs Lions", base, games))
        assertNull(ScoreMatch.find("LAB vs Giantsville", base, games))
    }

    @Test fun accentsAndPunctuationAreIgnored() {
        assertEquals("3", ScoreMatch.find("CHÍ. Bears — Detroit Lions!", base, nflDay())?.id)
    }

    // ---- CFB matching ----

    private val tigers = ts("Auburn Tigers", "Auburn", "AUB", "Auburn", "Tigers")
    private val bulldogs = ts("Georgia Bulldogs", "Georgia", "UGA", "Georgia", "Bulldogs")
    private val lsu = ts("LSU Tigers", "LSU", "LSU", "LSU", "Tigers")
    private val msBulldogs = ts("Mississippi State Bulldogs", "Mississippi State", "MSST", "Mississippi State", "Bulldogs")
    private val osu = ts("Ohio State Buckeyes", "Ohio State", "OSU", "Ohio State", "Buckeyes")
    private val mich = ts("Michigan Wolverines", "Michigan", "MICH", "Michigan", "Wolverines")
    private val msu = ts("Michigan State Spartans", "Michigan State", "MSU", "Michigan State", "Spartans")
    private val tam = ts("Texas A&M Aggies", "Texas A&M", "TAMU", "Texas A&M", "Aggies")
    private val tex = ts("Texas Longhorns", "Texas", "TEX", "Texas", "Longhorns")

    @Test fun cfbNicknamePairMatchesWhenUnique() {
        val games = listOf(game("a", "cfb", tigers, bulldogs), game("b", "cfb", osu, mich))
        assertEquals("a", ScoreMatch.find("Tigers vs Bulldogs", base, games)?.id)
        assertEquals("a", ScoreMatch.find("Georgia vs Auburn", base, games)?.id)
    }

    @Test fun cfbAmbiguousNicknamesNeedStartTime() {
        val g1 = game("a", "cfb", tigers, bulldogs, start = base)
        val g2 = game("b", "cfb", lsu, msBulldogs, start = base + 4 * hour)
        val games = listOf(g1, g2)
        assertNull(ScoreMatch.find("Tigers vs Bulldogs", base + 2 * hour, games)) // neither within 90 min
        assertNull(ScoreMatch.find("Tigers vs Bulldogs", null, games))
        assertEquals("a", ScoreMatch.find("Tigers vs Bulldogs", base + 10 * min, games)?.id)
        assertEquals("b", ScoreMatch.find("Tigers vs Bulldogs", base + 4 * hour - 30 * min, games)?.id)
    }

    @Test fun cfbTwoSameDayNoStartTimeIsNull() {
        val games = listOf(game("a", "cfb", tigers, bulldogs), game("b", "cfb", lsu, msBulldogs))
        assertNull(ScoreMatch.find("Tigers vs Bulldogs", null, games))
    }

    @Test fun cfbMichiganStateNotConfusedWithMichigan() {
        val games = listOf(game("a", "cfb", osu, mich), game("b", "cfb", msu, tex))
        assertEquals("a", ScoreMatch.find("Ohio State vs Michigan", base, games)?.id)
        assertEquals("b", ScoreMatch.find("Michigan State vs Texas", base, games)?.id)
        // "Michigan" inside "Michigan State" must not make Ohio State vs Michigan a match
        assertNull(ScoreMatch.find("Ohio State vs Michigan State", base, games))
    }

    @Test fun cfbAmpersandNameMatches() {
        val games = listOf(game("a", "cfb", tam, tex))
        assertEquals("a", ScoreMatch.find("Texas A&M at Texas", base, games)?.id)
        assertEquals("a", ScoreMatch.find("TAMU vs TEX", base, games)?.id)
    }

    // ---- day rule ----

    @Test fun gameTwoDaysAwayIsIgnored() {
        val games = listOf(game("1", "nfl", wsh, nyg, start = base + 48 * hour))
        assertNull(ScoreMatch.find("Commanders vs Giants", base, games))
        assertEquals("1", ScoreMatch.find("Commanders vs Giants", base + 40 * hour, games)?.id)
        assertEquals("1", ScoreMatch.find("Commanders vs Giants", null, games)?.id)
    }

    // ---- text ----

    private fun live() = game("1", "nfl", wsh, nyg, state = "in", detail = "Q3 - 8:42", period = 3, clock = "8:42", bc = "Prime Video")

    @Test fun liveText() {
        val g = live()
        assertEquals("WSH 24 – NYG 21 · Q3 8:42", ScoreText.rowLine(g))
        assertEquals(listOf("Commanders 24 – Giants 21", "Q3 · 8:42", "3-5-1 · 5-3-1 · Prime Video"), ScoreText.cardLines(g))
    }

    @Test fun halftimeText() {
        val g = live().copy(shortDetail = "Halftime", period = 2, clock = "0:00")
        assertEquals("WSH 24 – NYG 21 · Half", ScoreText.rowLine(g))
        assertEquals("Halftime", ScoreText.cardLines(g)[1])
    }

    @Test fun finalText() {
        val g = live().copy(state = "post", shortDetail = "Final", broadcast = "")
        assertEquals("Final · WSH 24 – NYG 21", ScoreText.rowLine(g))
        assertEquals(listOf("Commanders 24 – Giants 21", "Final", "3-5-1 · 5-3-1"), ScoreText.cardLines(g))
        assertEquals("Final/OT · WSH 24 – NYG 21", ScoreText.rowLine(g.copy(shortDetail = "Final/OT")))
    }

    @Test fun liveTextUsesPeriodAndClockNotEspnWording() {
        val g = live().copy(shortDetail = "8:42 - 3rd")
        assertEquals("WSH 24 – NYG 21 · Q3 8:42", ScoreText.rowLine(g))
        assertEquals("Q3 · 8:42", ScoreText.cardLines(g)[1])
        assertEquals("End of 3rd", ScoreText.cardLines(g.copy(shortDetail = "End of 3rd", clock = "0:00"))[1])
        assertEquals("WSH 24 – NYG 21 · OT 4:10", ScoreText.rowLine(g.copy(period = 5, clock = "4:10")))
    }

    @Test fun preText() {
        val g = live().copy(state = "pre", shortDetail = "11/22 - 7:15 PM EST")
        assertNull(ScoreText.rowLine(g))
        val ct = java.time.ZoneId.of("America/Chicago")
        val kick = "Kickoff " + java.time.format.DateTimeFormatter.ofPattern("EEE h:mm a").format(java.time.Instant.ofEpochMilli(g.startMillis).atZone(ct))
        assertEquals(listOf("Commanders (3-5-1) at Giants (5-3-1)", kick, "Prime Video"), ScoreText.cardLines(g, ct))
        val bare = g.copy(away = g.away.copy(record = ""), home = g.home.copy(record = ""), broadcast = "")
        assertEquals(listOf("Commanders at Giants", kick), ScoreText.cardLines(bare, ct))
        assertNotNull(ScoreText.cardLines(bare))
    }

    // ---- NBA ----

    private val lal = ts("Los Angeles Lakers", "Lakers", "LAL", "Los Angeles", "Lakers", "88", "10-4")
    private val lac = ts("LA Clippers", "Clippers", "LAC", "LA", "Clippers")
    private val cha = ts("Charlotte Hornets", "Hornets", "CHA", "Charlotte", "Hornets", "80", "5-9")
    private val nyk = ts("New York Knicks", "Knicks", "NY", "New York", "Knicks", "99", "")
    private val tor = ts("Toronto Raptors", "Raptors", "TOR", "Toronto", "Raptors", "101", "")
    private val bkn = ts("Brooklyn Nets", "Nets", "BKN", "Brooklyn", "Nets")
    private val lalB = ts("Los Angeles Lakers", "Lakers", "LAL", "Los Angeles", "Lakers")
    private val lacB = ts("Los Angeles Clippers", "Clippers", "LAC", "Los Angeles", "Clippers")
    private val phx = ts("Phoenix Suns", "Suns", "PHX", "Phoenix", "Suns")
    private val utah = ts("Utah Jazz", "Jazz", "UTA", "Utah", "Jazz")

    private fun nbaEvent(id: String, date: String, state: String, detail: String, period: Int, clock: String,
                         away: String, home: String) =
        """{"id":"$id","date":"$date","competitions":[{"competitors":[$away,$home]}],"status":{"displayClock":"$clock","period":$period,"type":{"state":"$state","name":"X","shortDetail":"$detail"}}}"""

    @Test fun parsesNbaScoreboard() {
        val json = feed(
            nbaEvent("1", "2026-11-18T00:30Z", "in", "8:42 - 3rd", 3, "8:42",
                jTeam("away", "Charlotte Hornets", "Hornets", "CHA", "Charlotte", "Hornets", "80", "5-9"),
                jTeam("home", "Los Angeles Lakers", "Lakers", "LAL", "Los Angeles", "Lakers", "88", "10-4")),
            nbaEvent("2", "2026-11-17T23:00Z", "post", "Final", 4, "0.0",
                jTeam("away", "Toronto Raptors", "Raptors", "TOR", "Toronto", "Raptors", "101"),
                jTeam("home", "New York Knicks", "Knicks", "NY", "New York", "Knicks", "99")),
            nbaEvent("3", "2026-11-18T02:00Z", "pre", "11/17 - 9:00 PM EST", 0, "0:00",
                jTeam("away", "Phoenix Suns", "Suns", "PHX", "Phoenix", "Suns"),
                jTeam("home", "Utah Jazz", "Jazz", "UTA", "Utah", "Jazz")),
        )
        val games = ScoreParse.parse(json, "nba")
        assertEquals(3, games.size)
        assertEquals(listOf("in", "post", "pre"), games.map { it.state })
        assertEquals("nba", games[0].league)
        assertEquals(3, games[0].period)
        assertEquals("88", games[0].home.score)
        assertEquals("Final", games[1].shortDetail)
        assertEquals("pre", games[2].state)
        assertEquals("CHA 80 – LAL 88 · Q3 8:42", ScoreText.rowLine(games[0]))
        assertEquals("Final · TOR 101 – NY 99", ScoreText.rowLine(games[1]))
        assertNull(ScoreText.rowLine(games[2]))
    }

    @Test fun nbaMatchesByTeamNames() {
        val games = listOf(
            game("1", "nba", cha, lal, state = "in"),
            game("2", "nba", tor, nyk),
            game("3", "nba", phx, utah),
        )
        assertEquals("1", ScoreMatch.find("Los Angeles Lakers vs Charlotte Hornets", null, games)?.id)
        assertEquals("2", ScoreMatch.find("Knicks vs Raptors", null, games)?.id)
        assertEquals("1", ScoreMatch.find("Lakers @ Hornets live", null, games)?.id)
        // a title naming only one team matches nothing
        assertNull(ScoreMatch.find("Lakers highlights", null, games))
    }

    @Test fun sharedNbaCityDoesNotMatchWhenBothTeamsPlay() {
        val games = listOf(game("1", "nba", cha, lalB), game("2", "nba", phx, lacB))
        assertNull(ScoreMatch.find("Los Angeles vs Charlotte", null, games))
        assertEquals("1", ScoreMatch.find("Lakers vs Charlotte", null, games)?.id)
        assertEquals("2", ScoreMatch.find("Phoenix Suns vs Los Angeles Clippers", null, games)?.id)
        // with only one LA team playing, the bare city is fine
        assertEquals("1", ScoreMatch.find("Los Angeles vs Charlotte", null, listOf(game("1", "nba", cha, lalB)))?.id)
    }

    @Test fun newYorkAndBrooklynAreTold() {
        val games = listOf(game("1", "nba", bkn, nyk), game("2", "nba", tor, lalB))
        assertEquals("1", ScoreMatch.find("Brooklyn vs New York", null, games)?.id)
        assertEquals("1", ScoreMatch.find("Nets vs Knicks", null, games)?.id)
    }

    @Test fun nflAndNbaSameDayDoNotConfuse() {
        // NFL has two New York teams; NBA has one. Cities are judged per league.
        val games = nflDay() + listOf(game("10", "nba", tor, nyk))
        assertEquals("10", ScoreMatch.find("Knicks vs Raptors", null, games)?.id)
        assertEquals("10", ScoreMatch.find("New York vs Toronto", null, games)?.id) // NBA New York is not ambiguous
        assertNull(ScoreMatch.find("New York vs Buffalo", null, games)) // NFL New York still is
        assertEquals("2", ScoreMatch.find("Bills vs Jets", null, games)?.id)
        // two LA teams in the NBA do not block an NFL name
        val mixed = listOf(game("1", "nfl", chi, ts("Los Angeles Rams", "Rams", "LAR", "Los Angeles", "Rams")),
            game("2", "nba", cha, lalB), game("3", "nba", phx, lacB))
        assertEquals("1", ScoreMatch.find("Los Angeles vs Chicago", null, mixed)?.id)
    }

    @Test fun nbaStatusText() {
        val g = game("1", "nba", cha, lal, state = "in", detail = "8:42 - 3rd", period = 3, clock = "8:42")
        assertEquals("CHA 80 – LAL 88 · Q3 8:42", ScoreText.rowLine(g))
        assertEquals("Q3 · 8:42", ScoreText.cardLines(g)[1])
        assertEquals("CHA 80 – LAL 88 · Half", ScoreText.rowLine(g.copy(shortDetail = "Halftime", period = 2, clock = "0.0")))
        assertEquals("CHA 80 – LAL 88 · OT 2:10", ScoreText.rowLine(g.copy(period = 5, clock = "2:10", shortDetail = "2:10 - OT")))
        assertEquals("OT · 2:10", ScoreText.cardLines(g.copy(period = 5, clock = "2:10"))[1])
        assertEquals("CHA 80 – LAL 88 · 2OT 0:45", ScoreText.rowLine(g.copy(period = 6, clock = "0:45")))
        assertEquals("CHA 80 – LAL 88 · 3OT", ScoreText.rowLine(g.copy(period = 7, clock = "")))
        // football unchanged
        assertEquals("OT 4:10", ScoreText.rowLine(live().copy(period = 5, clock = "4:10"))!!.substringAfter("· "))
    }
}
