package com.streamlink.tv

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The home screen's featured game (user, 2026-11-20): a score for every live game with a stream, built from how
 * undecided it is, how late it is, drama bonuses and a big-game booster, plus stickiness so the card does not jump.
 * Weights were tuned by replaying ESPN's play-by-play of NFL weeks 8-10 and the college Saturday of 2026-11-14.
 * Pure Kotlin; only ESPN data is used, never site wording.
 */
object FeaturedPick {

    // ---- one game's score ----

    data class Rating(
        val score: Double,
        /** How undecided, 0 (decided) to 1 (coin flip). */
        val doubt: Double,
        val margin: Int,
        val overtime: Boolean,
        /** Close and in the last 2 minutes (football) / 5 minutes (basketball), or overtime. */
        val crunch: Boolean,
        /** Overtime, or a one-score game in its final minute. */
        val finalMinute: Boolean,
        val firstHalf: Boolean,
        val why: List<String>,
    )

    private class Sport(val length: Double, val periods: Int, val sigma: Double, val crunchMin: Double, val oneScore: Int, val bigLead: Int, val basketball: Boolean)
    private val NFL = Sport(60.0, 4, 13.5, 2.0, 8, 14, false)
    private val CFB = Sport(60.0, 4, 16.0, 2.0, 8, 14, false)
    private val NBA = Sport(48.0, 4, 12.0, 5.0, 5, 15, true)
    private val CBB = Sport(40.0, 2, 11.0, 5.0, 5, 15, true)
    private fun sport(league: String) = when (league) { "nfl" -> NFL; "cfb" -> CFB; "nba" -> NBA; "cbb" -> CBB; else -> NFL }

    private val NFL_DIVISIONS = listOf(
        setOf("BUF", "MIA", "NE", "NYJ"), setOf("BAL", "CIN", "CLE", "PIT"), setOf("HOU", "IND", "JAX", "TEN"),
        setOf("DEN", "KC", "LV", "LAC"), setOf("DAL", "NYG", "PHI", "WSH", "WAS"), setOf("CHI", "DET", "GB", "MIN"),
        setOf("ATL", "CAR", "NO", "TB"), setOf("ARI", "LAR", "SF", "SEA"),
    )
    const val BIG_TEN = "5"
    private val NFC_NORTH = setOf("CHI", "DET", "GB", "MIN")

    /** An NFL game with a Bears, Lions, Packers or Vikings team (user's tie-break, 2026-11-20). */
    fun nfcNorth(g: Game): Boolean = g.league == "nfl" && (g.home.abbr.uppercase() in NFC_NORTH || g.away.abbr.uppercase() in NFC_NORTH)
    private val TV_FOOTBALL = setOf("ABC", "CBS", "NBC", "FOX", "ESPN")
    private val TV_CBB = TV_FOOTBALL + setOf("TNT", "TBS", "TRUTV")
    private val TV_NBA = setOf("ESPN", "ABC", "NBC", "PEACOCK", "PRIME VIDEO")

    fun college(league: String) = league == "cfb" || league == "cbb"

    fun isFootball(league: String) = league == "nfl" || league == "cfb"

    /** College games with no ranked team count only when no better game is live, except Big Ten vs Big Ten. */
    fun topTier(g: Game): Boolean = !college(g.league) || g.home.rank != null || g.away.rank != null ||
        (g.home.conferenceId == BIG_TEN && g.away.conferenceId == BIG_TEN)

    /** Seconds left in the current period from ESPN's clock ("8:42", or "45.2" in a basketball final minute). */
    fun clockSeconds(clock: String): Double {
        val c = clock.trim()
        if (c.isEmpty()) return 0.0
        val parts = c.split(':')
        return if (parts.size == 2) (parts[0].toDoubleOrNull() ?: 0.0) * 60 + (parts[1].toDoubleOrNull() ?: 0.0)
        else c.toDoubleOrNull() ?: 0.0
    }

    /** Win % from "7-2" or "7-2-1" (ties count half); 0.5 when unknown. */
    fun winPct(record: String): Double {
        val n = record.split('-').mapNotNull { it.trim().toIntOrNull() }
        if (n.size < 2) return 0.5
        val games = n.sum()
        return if (games == 0) 0.5 else (n[0] + 0.5 * n.getOrElse(2) { 0 }) / games
    }

    /** Pregame record: ESPN's live record is the one before the game, so it is used as is. */
    private fun pct(t: TeamSide) = winPct(t.record)

    /** Standard normal cumulative distribution. */
    fun phi(z: Double): Double {
        val t = 1 / (1 + 0.2316419 * abs(z))
        val d = 0.3989423 * exp(-z * z / 2)
        val p = d * t * (0.3193815 + t * (-0.3565638 + t * (1.781478 + t * (-1.821256 + t * 1.330274))))
        return if (z > 0) 1 - p else p
    }

    /** NFL regular-season week (1 = the week of the Thursday after Labor Day). */
    fun nflWeek(now: Long, zone: ZoneId = ZoneId.of("America/New_York")): Int {
        val d = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val year = if (d.monthValue < 3) d.year - 1 else d.year
        val labor = LocalDate.of(year, 9, 1).with(TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY))
        val kickoff = labor.plusDays(3)
        val days = d.toEpochDay() - kickoff.toEpochDay()
        return if (days < 0) 0 else (days / 7 + 1).toInt()
    }

    /** The big-game booster, 1.0 and up. */
    fun booster(g: Game, week: Int): Double {
        var b = 0.0
        val spread = g.homeSpread?.let { abs(it) }
        val nets = g.networks.ifEmpty { g.broadcast.split('/') }.map { it.trim().uppercase() }
        when (g.league) {
            "nfl" -> {
                val w = (pct(g.home) + pct(g.away)) / 2
                b += 0.2 * ((w - 0.40) / 0.35).coerceIn(0.0, 1.0) * (if (week in 1..4) 0.5 else 1.0)
                if (week >= 10 && pct(g.home) >= 0.5 && pct(g.away) >= 0.5) b += 0.08
                if (NFL_DIVISIONS.any { g.home.abbr.uppercase() in it && g.away.abbr.uppercase() in it }) b += 0.05
            }
            "nba" -> {
                val w = (pct(g.home) + pct(g.away)) / 2
                b += 0.2 * ((w - 0.45) / 0.25).coerceIn(0.0, 1.0)
                if (nets.any { it in TV_NBA }) b += 0.1
            }
            else -> { // college
                val hr = g.home.rank
                val ar = g.away.rank
                b += if (hr != null && ar != null) 0.4 else if (hr != null || ar != null) 0.15 else 0.0
                b += 0.1 * listOf(hr, ar).count { it != null && it <= 10 }
                if (pct(g.home) > 0.5 && pct(g.away) > 0.5) b += 0.05
                if (nets.any { it in (if (g.league == "cfb") TV_FOOTBALL else TV_CBB) }) b += 0.15
                b += 0.05 * listOf(g.home, g.away).count { it.conferenceId == BIG_TEN } // slight Big Ten lean
            }
        }
        if (spread != null) b += if (spread <= 3) 0.1 else if (spread <= 6) 0.05 else 0.0
        return 1 + min(if (college(g.league)) 1.0 else 0.5, b)
    }

    /**
     * Rates a live game. [history]: recent (time, home, away) scores of this game, oldest first (for comebacks).
     * [streams]: how many streams it has.
     */
    fun rate(g: Game, now: Long, streams: Int, week: Int, history: List<Triple<Long, Int, Int>> = emptyList()): Rating {
        val s = sport(g.league)
        val h = g.home.score.toIntOrNull() ?: 0
        val a = g.away.score.toIntOrNull() ?: 0
        val m = abs(h - a)
        val perLen = s.length / s.periods
        val period = max(1, g.period)
        val ot = period > s.periods
        val halftime = g.statusName == "STATUS_HALFTIME" || g.shortDetail.equals("Halftime", ignoreCase = true)
        val left = if (ot) 0.0 else ((s.periods - period) * perLen + clockSeconds(g.clock) / 60).coerceIn(0.0, s.length)
        val finalMinute = (ot || (period == s.periods && left <= 1.0)) && m <= s.oneScore

        var doubt = g.homeWinProb?.let { 1 - abs(2 * it - 1) }
            ?: if (m == 0) 1.0 else 2 * (1 - phi(m / (s.sigma * sqrt(max(left, 0.5) / s.length))))
        if (finalMinute) doubt = 1.0 // a deciding kick or last shot: never "decided" by the odds
        val played = if (ot) 1.0 else (1 - left / s.length).coerceIn(0.0, 1.0)
        var timing = 0.2 + 0.8 * played.pow(1.5)
        if (halftime) timing *= 0.6
        val base = 100 * doubt * timing

        val why = ArrayList<String>()
        var drama = 0.0
        val crunch = (left <= s.crunchMin || ot) && doubt >= 0.5
        if (crunch) { drama += 15; why.add("crunch") }
        // comeback: in the last 15 minutes a team trailed by a big lead and is now within one score
        val recent = history.filter { now - it.first <= 15 * 60_000L }
        if (doubt >= 0.3 && recent.isNotEmpty()) {
            val homeWasDown = recent.maxOf { it.third - it.second }
            val awayWasDown = recent.maxOf { it.second - it.third }
            if ((homeWasDown >= s.bigLead && a - h <= s.oneScore) || (awayWasDown >= s.bigLead && h - a <= s.oneScore)) {
                drama += 10; why.add("comeback")
            }
        }
        // upset brewing: the underdog leads in the second half
        val spread = g.homeSpread
        var dog: Char? = when {
            spread != null && spread != 0.0 -> if (spread < 0) 'a' else 'h'
            g.home.rank != g.away.rank && (g.home.rank != null || g.away.rank != null) ->
                when { g.home.rank == null -> 'h'; g.away.rank == null -> 'a'; g.home.rank > g.away.rank -> 'h'; else -> 'a' }
            abs(pct(g.home) - pct(g.away)) >= 0.15 -> if (pct(g.home) < pct(g.away)) 'h' else 'a'
            else -> null
        }
        val bigDog = spread != null && abs(spread) >= 7
        if (college(g.league) && dog != null) {
            val favRanked = (if (dog == 'h') g.away.rank else g.home.rank) != null
            if (!favRanked && !bigDog) dog = null
        }
        if (dog != null && played >= 0.5 && (if (dog == 'h') h > a else a > h)) { drama += if (bigDog) 12.0 else 8.0; why.add("upset") }
        if (!s.basketball && g.redZone && doubt >= 0.4 && played >= 0.5) { drama += 5; why.add("red zone") }
        drama = min(25.0, drama)

        val streamPts = if (streams <= 1) 0.0 else min(5.0, 2.5 * ln(streams.toDouble()) / ln(2.0))
        val score = (base + drama) * booster(g, week) + streamPts
        return Rating(score, doubt, m, ot, crunch, finalMinute, played < 0.5, why)
    }

    // ---- choosing and keeping the card ----

    /** The "heating up" badge (a flame emoji). */
    const val FLAME = "\uD83D\uDD25"

    /** A game is "heating up" (flame badge): crunch time, a comeback, or an upset brewing. */
    fun heating(r: Rating?): Boolean = r != null && (r.crunch || "comeback" in r.why || "upset" in r.why)

    /** The app's one State: the home screen's card, and the score history and ratings other screens read. */
    val shared = State()

    /** What the card is showing and since when, each live game's recent scores and its latest rating. */
    class State {
        var currentId: String? = null
        var since: Long = 0
        internal val history = HashMap<String, ArrayDeque<Triple<Long, Int, Int>>>()
        /** Latest rating of every live game with a stream (by game id), from the last [choose]. */
        @Volatile var ratings: Map<String, Rating> = emptyMap()

        /** Rates one live game outside the home screen (sport screen), using and updating this history. */
        @Synchronized fun rateNow(gf: GameFolder, now: Long, week: Int = nflWeek(now)): Rating? {
            if (gf.game.state != "in") return null
            record(gf.game, now)
            return rate(gf.game, now, gf.streams.size, week, historyOf(gf.game.id))
        }

        /** Keeps 15 minutes of scores per game; a score lower than one already seen (bad data) is ignored. */
        @Synchronized fun record(g: Game, now: Long) {
            val h = g.home.score.toIntOrNull() ?: return
            val a = g.away.score.toIntOrNull() ?: return
            val q = history.getOrPut(g.id) { ArrayDeque() }
            val last = q.lastOrNull()
            if (last != null && (h < last.second || a < last.third)) return
            if (last == null || last.second != h || last.third != a) q.addLast(Triple(now, h, a))
            while (q.size > 1 && now - q.first().first > 15 * 60_000L) q.removeFirst()
        }

        @Synchronized fun historyOf(id: String): List<Triple<Long, Int, Int>> = history[id]?.toList() ?: emptyList()

        @Synchronized fun forget(liveIds: Set<String>) { history.keys.retainAll(liveIds) }
    }

    const val HOLD_MS = 5 * 60_000L
    const val TIE_BREAK_HOLD_MS = 60_000L

    data class Pick(val folder: GameFolder, val rating: Rating, val reason: String)

    /**
     * The featured game: live games with at least one stream are rated; while any football (NFL, college) game is live,
     * only football is considered; college games without a ranked team (except Big Ten vs Big Ten) are used only when
     * nothing else in that group is live. The card then changes only when:
     * - the shown game ended or lost its streams, or a top game appears while a fallback game is shown;
     * - not while [focused]: the shown game fell below 10 while another is 30 or more (blowout);
     * - else, not while [focused] and not before [HOLD_MS], a game beats it by 20% and 10 points (20 points while
     *   the challenger is in its first half);
     * - a game in crunch time that is still undecided keeps the card, except (after 1 minute): another undecided
     *   crunch game with an NFC North team takes over from one without; if both or neither have one, a tied or
     *   overtime game in its final minute takes over from one led by 4 or more.
     * Null when no live game has a stream.
     */
    fun choose(folders: List<SportFolders>, now: Long, focused: Boolean, state: State, week: Int = nflWeek(now), finding: Set<String> = emptySet()): Pick? {
        val inGames = folders.flatMap { it.games }.filter { it.game.state == "in" }
        // Games with a stream first; right after start, games still being searched may stand in (HomeFinding).
        val live = inGames.filter { it.streams.isNotEmpty() }.ifEmpty { inGames.filter { HomeFinding.shows(it, finding) } }
        for (f in live) state.record(f.game, now)
        state.forget(folders.flatMap { it.games }.filter { it.game.state == "in" }.map { it.game.id }.toSet())
        if (live.isEmpty()) { state.currentId = null; state.ratings = emptyMap(); return null }
        val all = live.map { it to rate(it.game, now, it.streams.size, week, state.historyOf(it.game.id)) }
        state.ratings = all.associate { it.first.game.id to it.second }
        // Football (NFL, college football) always comes before basketball while any football game is live (user, 2026-11-20).
        val football = all.filter { isFootball(it.first.game.league) }.ifEmpty { all }
        val top = football.filter { topTier(it.first.game) }
        val rated = top.ifEmpty { football }
            .sortedWith(compareByDescending<Pair<GameFolder, Rating>> { it.second.score }
                .thenBy { LiveGames.leaguePriority(it.first.game.league) }.thenBy { it.first.game.startMillis })
        val best = rated.first()
        val cur = rated.firstOrNull { it.first.game.id == state.currentId }

        fun take(p: Pair<GameFolder, Rating>, reason: String): Pick {
            if (p.first.game.id != state.currentId) { state.currentId = p.first.game.id; state.since = now }
            return Pick(p.first, p.second, reason)
        }

        if (cur == null) return take(best, if (state.currentId == null) "first" else "ended")
        if (best === cur) return Pick(cur.first, cur.second, "kept")
        if (focused) return Pick(cur.first, cur.second, "focused")
        val cs = cur.second.score
        if (cs < 10 && best.second.score >= 30) return take(best, "blowout")
        val held = now - state.since
        if (cur.second.crunch && cur.second.doubt >= 0.5) {
            if (held >= TIE_BREAK_HOLD_MS) {
                // two finishes at once: an NFC North game wins; if both or neither have one, a tied/overtime final
                // minute beats a 4+ lead
                val curNorth = nfcNorth(cur.first.game)
                val north = rated.firstOrNull { it !== cur && it.second.crunch && it.second.doubt >= 0.5 && nfcNorth(it.first.game) }
                if (!curNorth && north != null) return take(north, "NFC North")
                if (cur.second.margin >= 4) {
                    val ch = rated.firstOrNull { it !== cur && it.second.finalMinute && (it.second.margin == 0 || it.second.overtime) &&
                        nfcNorth(it.first.game) == curNorth }
                    if (ch != null) return take(ch, "tie-break")
                }
            }
            return Pick(cur.first, cur.second, "protected")
        }
        val gap = if (best.second.firstHalf) 20.0 else 10.0
        if (held >= HOLD_MS && best.second.score >= cs * 1.2 && best.second.score >= cs + gap) return take(best, "beat")
        return Pick(cur.first, cur.second, "kept")
    }
}
