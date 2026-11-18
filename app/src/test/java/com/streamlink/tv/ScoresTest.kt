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

    private val lal = ts("Los Angeles Lakers", "Lakers", "LAL", "Los Angeles", "Lakers")
    private val lac = ts("Los Angeles Clippers", "Clippers", "LAC", "Los Angeles", "Clippers")
    private val bos = ts("Boston Celtics", "Celtics", "BOS", "Boston", "Celtics")
    private val den = ts("Denver Nuggets", "Nuggets", "DEN", "Denver", "Nuggets")

    @Test fun parsesNbaScoreboard() {
        val json = feed(jEvent("501", "2026-11-18T00:30Z", "in", "8:42 - 3rd",
            jTeam("away", "Boston Celtics", "Celtics", "BOS", "Boston", "Celtics", "88", "9-2") + "," +
                jTeam("home", "Los Angeles Lakers", "Lakers", "LAL", "Los Angeles", "Lakers", "85", "7-4")))
        val g = ScoreParse.parse(json, "nba").single()
        assertEquals("nba", g.league)
        assertEquals("in", g.state)
        assertEquals("LAL", g.home.abbr)
        assertEquals("Los Angeles", g.home.location)
        assertEquals("Celtics", g.away.nickname)
        assertEquals("BOS 88 – LAL 85 · Q3 8:42", ScoreText.rowLine(g))
        assertEquals("Halftime", ScoreText.cardLines(g.copy(shortDetail = "Halftime", period = 2, clock = "0.0"))[1])
        assertEquals("BOS 88 – LAL 85 · OT 2:10", ScoreText.rowLine(g.copy(period = 5, clock = "2:10")))
        val pre = g.copy(state = "pre", period = 0)
        assertEquals("Tip-off", ScoreText.cardLines(pre)[1].substringBefore(' '))
    }

    @Test fun nbaTeamNamesMatch() {
        val games = listOf(game("n1", "nba", bos, lal), game("n2", "nba", den, lac))
        assertEquals("n1", ScoreMatch.find("Lakers vs Celtics", base, games)?.id)
        assertEquals("n1", ScoreMatch.find("Celtics at Los Angeles Lakers", base, games)?.id)
    }

    @Test fun nbaSharedLocationIsIgnored() {
        val games = listOf(game("n1", "nba", bos, lal), game("n2", "nba", den, lac))
        assertNull(ScoreMatch.find("Los Angeles vs Boston", base, games))
        assertEquals("n2", ScoreMatch.find("Clippers vs Nuggets", base, games)?.id)
    }
}
