package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ScoreMatchCacheTest {
    private val base = 1_800_000_000_000L
    private val hour = 3_600_000L

    private fun ts(d: String, s: String, a: String, l: String, n: String) = TeamSide(d, s, a, l, n, "0", "")
    private fun game(id: String, lg: String, away: TeamSide, home: TeamSide, start: Long = base) =
        Game(id, lg, start, "pre", "", 0, "", home, away, "")

    private val nyg = ts("New York Giants", "Giants", "NYG", "New York", "Giants")
    private val nyj = ts("New York Jets", "Jets", "NYJ", "New York", "Jets")
    private val buf = ts("Buffalo Bills", "Bills", "BUF", "Buffalo", "Bills")
    private val chi = ts("Chicago Bears", "Bears", "CHI", "Chicago", "Bears")
    private val lal = ts("Los Angeles Lakers", "Lakers", "LAL", "Los Angeles", "Lakers")
    private val lac = ts("Los Angeles Clippers", "Clippers", "LAC", "Los Angeles", "Clippers")
    private val bos = ts("Boston Celtics", "Celtics", "BOS", "Boston", "Celtics")
    private val mic = ts("Michigan Wolverines", "Michigan", "MICH", "Michigan", "Wolverines")
    private val msu = ts("Michigan State Spartans", "Michigan State", "MSU", "Michigan State", "Spartans")
    private val ohs = ts("Ohio State Buckeyes", "Ohio State", "OSU", "Ohio State", "Buckeyes")

    private fun games() = listOf(
        game("1", "nfl", buf, nyg), game("2", "nfl", chi, nyj, base + 2 * hour),
        game("3", "nba", lal, bos), game("4", "nba", lac, bos, base + 3 * hour),
        game("5", "cfb", mic, ohs), game("6", "cfb", msu, ohs, base + hour),
    )

    private val titles = listOf(
        "Bills vs Giants", "Chicago Bears at New York Jets", "New York vs Buffalo", "Lakers vs Celtics",
        "Los Angeles vs Boston", "Clippers at Celtics", "Michigan vs Ohio State", "Michigan State v Ohio State",
        "Giants", "", "Jets Bears live", "Buffalo Bills - NY Giants NFL",
    )

    @Test fun sameAnswersAsReference() {
        val g = games()
        for (t in titles) for (start in listOf(null, base, base + 2 * hour, base + 30 * hour)) {
            assertEquals("$t @ $start", ScoreMatch.referenceFind(t, start, g)?.id, ScoreMatch.find(t, start, g)?.id)
            // second call comes from the cache and must still agree
            assertEquals("$t @ $start (cached)", ScoreMatch.referenceFind(t, start, g)?.id, ScoreMatch.find(t, start, g)?.id)
        }
    }

    @Test fun alternatingListsKeepTheirAnswers() {
        val a = games()
        val b = games().filter { it.league != "nba" }
        repeat(50) {
            assertEquals("3", ScoreMatch.find("Lakers vs Celtics", null, a)?.id)
            assertEquals(null, ScoreMatch.find("Lakers vs Celtics", null, b)?.id)
            assertEquals("1", ScoreMatch.find("Bills vs Giants", base, b)?.id)
        }
    }

    @Test fun forLeaguesIsStableAndFiltered() {
        val src = games()
        val x = GameViews.forLeagues(src, setOf("nfl", "nba"))
        assertSame(x, GameViews.forLeagues(src, setOf("nba", "nfl")))
        assertEquals(listOf("1", "2", "3", "4"), x.map { it.id })
        assertSame(src, GameViews.forLeagues(src, emptySet()))
        assertNotSame(x, GameViews.forLeagues(games(), setOf("nfl", "nba"))) // another source instance
        assertSame(GameViews.window(src, base, base + 5 * hour), GameViews.window(src, base, base + 5 * hour))
    }

    @Test fun scoreFeedForLeaguesKeepsInstanceWhileDataUnchanged() {
        assertSame(ScoreFeed.forLeagues(setOf("nfl")), ScoreFeed.forLeagues(setOf("nfl")))
    }
}
