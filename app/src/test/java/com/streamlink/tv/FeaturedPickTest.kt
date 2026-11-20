package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FeaturedPickTest {
    private val now = Instant.parse("2026-11-15T20:00:00Z").toEpochMilli()
    private val min = 60_000L

    private fun side(abbr: String, score: Int, record: String = "5-4", rank: Int? = null, conf: String = "") =
        TeamSide(abbr, abbr, abbr, abbr, abbr, score.toString(), record, rank = rank, conferenceId = conf)

    private fun game(
        id: String, away: TeamSide, home: TeamSide, period: Int, clock: String, league: String = "nfl",
        wp: Double? = null, spread: Double? = null, nets: List<String> = emptyList(), status: String = "STATUS_IN_PROGRESS",
    ) = Game(id, league, now - 120 * min, "in", "", period, clock, home, away, nets.joinToString(" / "),
        statusName = status, homeWinProb = wp, homeSpread = spread, networks = nets)

    private fun folder(g: Game, streams: Int = 1) =
        GameFolder(g, (1..streams).map { GameStream("l", "L", ListItem(g.id, "", "https://x/${g.id}/$it", null, "", "", true, null)) })

    private fun sports(vararg f: GameFolder) = f.groupBy { it.game.league }.map { (lg, gs) -> SportFolders(lg, gs, emptyList()) }

    private fun rate(g: Game) = FeaturedPick.rate(g, now, 1, 10)

    // ---- rating ----

    @Test fun lateTieBeatsEarlyTieAndBlowoutIsLow() {
        val earlyTie = rate(game("a", side("A", 7), side("B", 7), 1, "2:00"))
        val lateTie = rate(game("b", side("A", 17), side("B", 17), 4, "4:00"))
        val blowout = rate(game("c", side("A", 3), side("B", 31), 3, "5:00"))
        assertTrue(lateTie.score > earlyTie.score)
        assertTrue(blowout.score < 10)
        assertTrue(earlyTie.firstHalf)
        assertFalse(lateTie.firstHalf)
    }

    @Test fun espnWinProbabilityIsUsedWhenSent() {
        val sure = rate(game("a", side("A", 10), side("B", 13), 4, "6:00", wp = 0.95))
        val coin = rate(game("a", side("A", 10), side("B", 13), 4, "6:00", wp = 0.5))
        assertEquals(0.1, sure.doubt, 1e-9)
        assertEquals(1.0, coin.doubt, 1e-9)
    }

    @Test fun finalMinuteOfOneScoreGameIsAlwaysUndecided() {
        val r = rate(game("a", side("A", 17), side("B", 17), 4, "0:05", wp = 0.92))
        assertTrue(r.finalMinute)
        assertEquals(1.0, r.doubt, 1e-9)
        assertTrue(r.crunch)
        assertFalse(rate(game("b", side("A", 10), side("B", 24), 4, "0:30")).finalMinute)
    }

    @Test fun halftimeIsMarkedDown() {
        val play = rate(game("a", side("A", 14), side("B", 14), 2, "0:01"))
        val half = rate(game("a", side("A", 14), side("B", 14), 2, "0:00", status = "STATUS_HALFTIME"))
        assertTrue(half.score < play.score * 0.7)
    }

    @Test fun comebackNeedsBigLeadCutToOneScore() {
        val g = game("a", side("A", 21), side("B", 24), 4, "8:00")
        val hist = listOf(Triple(now - 12 * min, 21, 7), Triple(now - 5 * min, 21, 14), Triple(now - 1 * min, 24, 21))
        assertTrue("comeback" in FeaturedPick.rate(g, now, 1, 10, hist).why)
        val old = listOf(Triple(now - 30 * min, 21, 7))
        assertFalse("comeback" in FeaturedPick.rate(g, now, 1, 10, old).why)
    }

    @Test fun upsetBrewingFromSpread() {
        // home is a 7.5-point underdog and leads in the second half
        val r = rate(game("a", side("A", 10), side("B", 17), 3, "5:00", spread = 7.5))
        assertTrue("upset" in r.why)
        assertFalse("upset" in rate(game("a", side("A", 10), side("B", 17), 3, "5:00", spread = -7.5)).why)
    }

    @Test fun collegeUpsetOnlyAgainstRankedOrBigFavorite() {
        // unranked favorite by 3, underdog leads: not an upset in college
        val small = game("a", side("A", 10), side("B", 17), 3, "5:00", league = "cfb", spread = 3.0)
        assertFalse("upset" in rate(small).why)
        val ranked = game("a", side("A", 10, rank = 8), side("B", 17), 3, "5:00", league = "cfb", spread = 3.0)
        assertTrue("upset" in rate(ranked).why)
    }

    @Test fun streamsAddUpToFivePoints() {
        val g = game("a", side("A", 7), side("B", 7), 3, "5:00")
        val one = FeaturedPick.rate(g, now, 1, 10).score
        assertEquals(2.5, FeaturedPick.rate(g, now, 2, 10).score - one, 1e-6)
        assertEquals(5.0, FeaturedPick.rate(g, now, 9, 10).score - one, 1e-6)
    }

    // ---- booster ----

    @Test fun boosterRankedNationalTvAndBigTen() {
        val plain = game("a", side("A", 0), side("B", 0), 1, "15:00", league = "cfb")
        val ranked = game("b", side("A", 0, rank = 5), side("B", 0, rank = 12), 1, "15:00", league = "cfb", nets = listOf("ABC"))
        val bigTen = game("c", side("A", 0, conf = "5"), side("B", 0, conf = "5"), 1, "15:00", league = "cfb")
        // every team here is 5-4, so each game also gets .05 for two winning records
        assertEquals(1.05, FeaturedPick.booster(plain, 12), 1e-9)
        assertEquals(1.7, FeaturedPick.booster(ranked, 12), 1e-9) // both ranked .4, one top-10 .1, national TV .15
        assertEquals(1.15, FeaturedPick.booster(bigTen, 12), 1e-9) // Big Ten .05 per team
    }

    @Test fun boosterNflDivisionQualityAndSpread() {
        val g = game("a", side("MIN", 0, "6-2"), side("GB", 0, "6-3"), 1, "15:00", spread = -2.5)
        // quality: avg .708 -> .2*.88 = .176; both >= .500 (week 10) .08; division .05; spread <= 3 .1
        assertEquals(1.406, FeaturedPick.booster(g, 10), 0.002)
        // 9-0 vs 9-0, not division rivals: quality .2, both >= .500 .08, spread .1
        assertEquals(1.38, FeaturedPick.booster(game("b", side("A", 0, "9-0"), side("B", 0, "9-0"), 1, "15:00", spread = 1.0), 10), 1e-9)
    }

    @Test fun topTierRules() {
        assertFalse(FeaturedPick.topTier(game("a", side("A", 0), side("B", 0), 1, "15:00", league = "cfb")))
        assertTrue(FeaturedPick.topTier(game("a", side("A", 0, rank = 20), side("B", 0), 1, "15:00", league = "cfb")))
        assertTrue(FeaturedPick.topTier(game("a", side("A", 0, conf = "5"), side("B", 0, conf = "5"), 1, "15:00", league = "cfb")))
        assertFalse(FeaturedPick.topTier(game("a", side("A", 0, conf = "5"), side("B", 0, conf = "8"), 1, "15:00", league = "cfb")))
        assertTrue(FeaturedPick.topTier(game("a", side("A", 0), side("B", 0), 1, "15:00", league = "nfl")))
    }

    @Test fun helpers() {
        assertEquals(522.0, FeaturedPick.clockSeconds("8:42"), 1e-9)
        assertEquals(45.2, FeaturedPick.clockSeconds("45.2"), 1e-9)
        assertEquals(0.75, FeaturedPick.winPct("6-2"), 1e-9)
        assertEquals(0.5, FeaturedPick.winPct(""), 1e-9)
        assertEquals(10, FeaturedPick.nflWeek(Instant.parse("2026-11-15T18:00:00Z").toEpochMilli()))
        assertEquals(1, FeaturedPick.nflWeek(Instant.parse("2026-09-13T18:00:00Z").toEpochMilli()))
    }

    // ---- choosing ----

    @Test fun noStreamsNoCard() {
        val st = FeaturedPick.State()
        assertNull(FeaturedPick.choose(sports(folder(game("a", side("A", 7), side("B", 7), 2, "5:00"), streams = 0)), now, false, st, 10))
    }

    @Test fun unrankedCollegeOnlyAsFallback() {
        val st = FeaturedPick.State()
        val small = folder(game("s", side("A", 21), side("B", 21), 4, "3:00", league = "cfb"))
        assertEquals("s", FeaturedPick.choose(sports(small), now, false, st, 10)?.folder?.game?.id)
        // a ranked game, even a dull one, replaces the fallback game at once
        val ranked = folder(game("r", side("A", 0, rank = 9), side("B", 7), 1, "10:00", league = "cfb"))
        val p = FeaturedPick.choose(sports(small, ranked), now + min, false, st, 10)
        assertEquals("r", p?.folder?.game?.id)
    }

    @Test fun holdsFiveMinutesThenSwitchesWhenClearlyBeaten() {
        val st = FeaturedPick.State()
        val dull = folder(game("d", side("A", 7), side("B", 10), 3, "10:00"))
        assertEquals("d", FeaturedPick.choose(sports(dull), now, false, st, 10)?.folder?.game?.id)
        val hot = folder(game("h", side("C", 20), side("D", 20), 4, "6:00"))
        assertEquals("d", FeaturedPick.choose(sports(dull, hot), now + 2 * min, false, st, 10)?.folder?.game?.id)
        assertEquals("d", FeaturedPick.choose(sports(dull, hot), now + 6 * min, true, st, 10)?.folder?.game?.id) // focused
        val p = FeaturedPick.choose(sports(dull, hot), now + 6 * min, false, st, 10)
        assertEquals("h", p?.folder?.game?.id)
        assertEquals("beat", p?.reason)
    }

    @Test fun crunchTimeKeepsTheCardButTieBreakWins() {
        val st = FeaturedPick.State()
        val led = folder(game("l", side("A", 24), side("B", 20), 4, "1:30", wp = 0.4))
        assertTrue(FeaturedPick.nfcNorth(game("x", side("GB", 0), side("NE", 0), 1, "15:00")))
        assertFalse(FeaturedPick.nfcNorth(game("x", side("GB", 0), side("NE", 0), 1, "15:00", league = "cfb")))
        assertEquals("l", FeaturedPick.choose(sports(led), now, false, st, 10)?.folder?.game?.id)
        val other = folder(game("o", side("C", 20), side("D", 17), 4, "0:40"))
        assertEquals("protected", FeaturedPick.choose(sports(led, other), now + 10 * min, false, st, 10)?.reason)
        val tied = folder(game("t", side("C", 20), side("D", 20), 4, "0:40"))
        val p = FeaturedPick.choose(sports(led, tied), now + 11 * min, false, st, 10)
        assertEquals("t", p?.folder?.game?.id)
        assertEquals("tie-break", p?.reason)
    }

    @Test fun nfcNorthWinsOverlappingFinishes() {
        val st = FeaturedPick.State()
        val led = folder(game("l", side("HOU", 24), side("CLE", 20), 4, "1:30", wp = 0.4))
        assertEquals("l", FeaturedPick.choose(sports(led), now, false, st, 10)?.folder?.game?.id)
        // a close Bears finish takes over even though it is not tied
        val bears = folder(game("b", side("CHI", 20), side("TB", 17), 4, "1:50", wp = 0.45))
        val p = FeaturedPick.choose(sports(led, bears), now + 2 * min, false, st, 10)
        assertEquals("b", p?.folder?.game?.id)
        assertEquals("NFC North", p?.reason)
        // and keeps the card against a tied non-NFC North final minute
        val tied = folder(game("t", side("SF", 20), side("DAL", 20), 4, "0:40"))
        assertEquals("b", FeaturedPick.choose(sports(bears, tied), now + 4 * min, false, st, 10)?.folder?.game?.id)
    }

    @Test fun endedGameIsReplacedEvenWhileFocused() {
        val st = FeaturedPick.State()
        val a = folder(game("a", side("A", 7), side("B", 7), 2, "5:00"))
        val b = folder(game("b", side("C", 3), side("D", 0), 1, "5:00"))
        assertEquals("a", FeaturedPick.choose(sports(a, b), now, false, st, 10)?.folder?.game?.id)
        assertEquals("b", FeaturedPick.choose(sports(b), now + min, true, st, 10)?.folder?.game?.id)
    }

    @Test fun badScoreDataIsIgnoredInHistory() {
        val st = FeaturedPick.State()
        st.record(game("a", side("A", 7), side("B", 14), 3, "5:00"), now)
        st.record(game("a", side("A", 0), side("B", 0), 4, "3:00"), now + min)
        assertEquals(listOf(Triple(now, 14, 7)), st.historyOf("a"))
    }

    // ---- ESPN fields the pick uses ----

    private fun event(odds: String, situation: String = "") = """{"events":[{"id":"9","date":"2026-11-14T20:30Z",
        "status":{"period":4,"displayClock":"0:41","type":{"state":"in","name":"STATUS_IN_PROGRESS","shortDetail":"0:41 - 4th"}},
        "competitions":[{"competitors":[
          {"homeAway":"home","score":"17","curatedRank":{"current":7},"records":[{"summary":"7-2"}],
           "team":{"displayName":"Ohio State Buckeyes","abbreviation":"OSU","conferenceId":"5"}},
          {"homeAway":"away","score":"17","curatedRank":{"current":99},"records":[{"summary":"5-4"}],
           "team":{"displayName":"Northwestern Wildcats","abbreviation":"NU","conferenceId":"5"}}],
          "broadcasts":[{"names":["FOX"]},{"names":["FOX One"]},{"names":["Big Ten Network"]}]
          $odds $situation }]}]}"""

    @Test fun parsesRankConferenceOddsAndSituation() {
        val g = ScoreParse.parse(event(""","odds":[{"details":"OSU -14.5","spread":-14.5,"homeTeamOdds":{"favorite":true}}]""",
            ""","situation":{"isRedZone":true,"lastPlay":{"probability":{"homeWinPercentage":0.62}}}"""), "cfb").single()
        assertEquals(7, g.home.rank)
        assertNull(g.away.rank)
        assertEquals("5", g.away.conferenceId)
        assertEquals(-14.5, g.homeSpread!!, 1e-9)
        assertEquals(0.62, g.homeWinProb!!, 1e-9)
        assertTrue(g.redZone)
        assertEquals(listOf("FOX", "FOX One", "Big Ten Network"), g.networks)
        assertEquals("FOX / FOX One", g.broadcast)
    }

    @Test fun spreadSignFromFavoriteFlagOrDetails() {
        val awayFav = ScoreParse.parse(event(""","odds":[{"spread":5.5,"homeTeamOdds":{"favorite":false},"awayTeamOdds":{"favorite":true}}]"""), "cfb").single()
        assertEquals(5.5, awayFav.homeSpread!!, 1e-9)
        val details = ScoreParse.parse(event(""","odds":[{"details":"NU -3"}]"""), "cfb").single()
        assertEquals(3.0, details.homeSpread!!, 1e-9)
        val none = ScoreParse.parse(event(""), "cfb").single()
        assertNull(none.homeSpread)
        assertNull(none.homeWinProb)
        assertFalse(none.redZone)
    }
}
