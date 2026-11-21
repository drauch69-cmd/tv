package com.streamlink.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** One side of a game, as ESPN names it. */
data class TeamSide(
    val displayName: String,  // "New York Giants"
    val shortName: String,    // "Giants"
    val abbr: String,         // "NYG"
    val location: String,     // "New York"
    val nickname: String,     // "Giants"
    val score: String,
    val record: String,
    /** ESPN's team logo address ("https://a.espncdn.com/i/teamlogos/…png"), or "" if ESPN sent none. */
    val logo: String = "",
    /** College poll rank 1-25 (ESPN curatedRank), null when unranked or not given. */
    val rank: Int? = null,
    /** ESPN conference id ("5" = Big Ten), "" if not given. */
    val conferenceId: String = "",
)

/** One NFL, college football, NBA or college basketball game from ESPN's scoreboard. */
data class Game(
    val id: String,
    val league: String,       // "nfl" | "cfb" | "nba" | "cbb"
    val startMillis: Long,
    val state: String,        // "pre" | "in" | "post"
    val shortDetail: String,  // "Final", "Q3 - 8:42", "Halftime", "11/22 - 7:15 PM EST"
    val period: Int,
    val clock: String,
    val home: TeamSide,
    val away: TeamSide,
    val broadcast: String,
    val statusName: String = "", // ESPN status.type.name, e.g. "STATUS_POSTPONED"
    /** Live chance the home team wins (0..1) from ESPN's last play, when ESPN sends it. */
    val homeWinProb: Double? = null,
    /** Pregame line for the home team (negative = home favored), when ESPN sends odds. */
    val homeSpread: Double? = null,
    /** Football: the team with the ball is inside the 20 (ESPN situation.isRedZone). */
    val redZone: Boolean = false,
    /** Every TV network ESPN lists (the [broadcast] text keeps only two). */
    val networks: List<String> = emptyList(),
    /** Where the game is played (ESPN competition venue), "" when not sent: "Chicago", "IL" (US state code), country. */
    val venueCity: String = "",
    val venueState: String = "",
    val venueCountry: String = "",
)

/** Reads ESPN's scoreboard JSON. Never throws. */
object ScoreParse {
    fun parse(json: String, league: String): List<Game> {
        val events = try { JSONObject(json).optJSONArray("events") } catch (_: Exception) { null } ?: return emptyList()
        val out = ArrayList<Game>()
        for (i in 0 until events.length()) {
            try {
                parseEvent(events.optJSONObject(i), league)?.let { out.add(it) }
            } catch (_: Exception) {
                // one malformed event is skipped
            }
        }
        return out
    }

    private fun str(o: JSONObject?, key: String): String = if (o == null || o.isNull(key)) "" else o.optString(key, "")

    private fun parseEvent(ev: JSONObject?, league: String): Game? {
        if (ev == null) return null
        val id = str(ev, "id")
        if (id.isBlank()) return null
        val comp = ev.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val competitors = comp.optJSONArray("competitors") ?: return null
        var home: TeamSide? = null
        var away: TeamSide? = null
        for (i in 0 until competitors.length()) {
            val c = competitors.optJSONObject(i) ?: continue
            val side = side(c) ?: continue
            when (str(c, "homeAway")) {
                "home" -> home = side
                "away" -> away = side
            }
        }
        if (home == null || away == null) return null
        val dateText = str(ev, "date").ifEmpty { str(comp, "date") }
        val start = parseDate(dateText) ?: return null
        val status = ev.optJSONObject("status") ?: comp.optJSONObject("status")
        val type = status?.optJSONObject("type")
        val state = str(type, "state").lowercase().let { if (it == "in" || it == "post") it else "pre" }
        var broadcast = ""
        var networks = emptyList<String>()
        val bs = comp.optJSONArray("broadcasts")
        if (bs != null) {
            val names = LinkedHashSet<String>()
            for (i in 0 until bs.length()) {
                val arr = bs.optJSONObject(i)?.optJSONArray("names") ?: continue
                val n = if (arr.length() > 0 && !arr.isNull(0)) arr.optString(0, "").trim() else ""
                if (n.isNotEmpty()) names.add(n)
            }
            broadcast = names.take(2).joinToString(" / ")
            networks = names.toList()
        }
        val situation = comp.optJSONObject("situation")
        val prob = situation?.optJSONObject("lastPlay")?.optJSONObject("probability")
        val homeWinProb = prob?.let { if (it.has("homeWinPercentage") && !it.isNull("homeWinPercentage")) it.optDouble("homeWinPercentage") else null }
            ?.takeIf { !it.isNaN() && it in 0.0..1.0 }
        val homeSpread = homeSpread(comp.optJSONArray("odds")?.optJSONObject(0), home.abbr)
        val address = comp.optJSONObject("venue")?.optJSONObject("address")
        return Game(
            id = id, league = league, startMillis = start, state = state,
            shortDetail = str(type, "shortDetail"),
            period = status?.optInt("period", 0) ?: 0,
            clock = str(status, "displayClock"),
            home = home, away = away, broadcast = broadcast,
            statusName = str(type, "name"),
            homeWinProb = homeWinProb,
            homeSpread = homeSpread,
            redZone = situation?.optBoolean("isRedZone", false) ?: false,
            networks = networks,
            venueCity = str(address, "city").trim(),
            venueState = str(address, "state").trim(),
            venueCountry = str(address, "country").trim(),
        )
    }

    /** The home team's line: favorite flags first, else the sign of `spread`, else "ABBR -3.5" in `details`. */
    private fun homeSpread(odds: JSONObject?, homeAbbr: String): Double? {
        if (odds == null) return null
        val raw = if (odds.has("spread") && !odds.isNull("spread")) odds.optDouble("spread") else Double.NaN
        val homeFav = odds.optJSONObject("homeTeamOdds")?.let { if (it.has("favorite")) it.optBoolean("favorite") else null }
        val awayFav = odds.optJSONObject("awayTeamOdds")?.let { if (it.has("favorite")) it.optBoolean("favorite") else null }
        if (!raw.isNaN()) {
            val size = kotlin.math.abs(raw)
            return when {
                homeFav == true || awayFav == false -> -size
                awayFav == true || homeFav == false -> size
                else -> raw
            }
        }
        val m = Regex("""^\s*(\S+)\s+-(\d+(?:\.\d+)?)\s*$""").matchEntire(str(odds, "details")) ?: return null
        val n = m.groupValues[2].toDoubleOrNull() ?: return null
        return if (m.groupValues[1].equals(homeAbbr, ignoreCase = true)) -n else n
    }

    private fun side(c: JSONObject): TeamSide? {
        val t = c.optJSONObject("team") ?: return null
        val display = str(t, "displayName")
        if (display.isBlank()) return null
        val record = c.optJSONArray("records")?.optJSONObject(0)?.let { str(it, "summary") } ?: ""
        return TeamSide(
            displayName = display,
            shortName = str(t, "shortDisplayName"),
            abbr = str(t, "abbreviation"),
            location = str(t, "location"),
            nickname = str(t, "name"),
            score = str(c, "score"),
            record = record,
            logo = str(t, "logo").takeIf { it.startsWith("https://") } ?: "",
            rank = c.optJSONObject("curatedRank")?.optInt("current", 0)?.takeIf { it in 1..25 },
            conferenceId = str(t, "conferenceId"),
        )
    }

    private val DATE = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2})(:\d{2}(?:\.\d+)?)?(Z|[+-]\d{2}:\d{2})?$""")

    /** "2026-11-13T01:15Z" or with seconds; no zone means UTC. */
    fun parseDate(s: String): Long? {
        val m = DATE.matchEntire(s.trim()) ?: return null
        val sec = m.groupValues[2].ifEmpty { ":00" }
        val zone = m.groupValues[3].ifEmpty { "Z" }
        return try {
            OffsetDateTime.parse(m.groupValues[1] + sec + zone).toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }
}

/** Finds the game a list title is about. Titles are untrusted text; only ESPN team names are used. */
object ScoreMatch {
    private const val DAY_WINDOW_MS = 18 * 60 * 60 * 1000L
    private const val START_WINDOW_MS = 90 * 60 * 1000L
    private val SHARED_CITY_LEAGUES = listOf("nfl", "nba")

    /** Lowercase, no accents, everything but letters and digits becomes one space. */
    private val SPACES = Regex(" +")

    fun norm(s: String): String {
        val d = Normalizer.normalize(s, Normalizer.Form.NFD)
        val sb = StringBuilder(d.length)
        for (ch in d) {
            if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
            if (ch.isLetterOrDigit()) sb.append(ch.lowercaseChar()) else sb.append(' ')
        }
        return sb.toString().trim().replace(SPACES, " ")
    }

    private class Occ(val start: Int, val end: Int) // end exclusive, in the padded title
    private val Occ.len get() = end - start

    private fun occurrences(padded: String, name: String): List<Occ> {
        if (name.length < 2) return emptyList()
        val needle = " $name "
        val out = ArrayList<Occ>()
        var i = padded.indexOf(needle)
        while (i >= 0) {
            out.add(Occ(i + 1, i + 1 + name.length))
            i = padded.indexOf(needle, i + 1)
        }
        return out
    }

    private fun names(t: TeamSide, ambiguousLocations: Set<String>): Set<String> {
        val loc = norm(t.location)
        val nick = norm(t.nickname)
        val out = LinkedHashSet<String>()
        out.add(norm(t.displayName))
        if (loc.isNotEmpty() && nick.isNotEmpty()) out.add("$loc $nick")
        if (nick.isNotEmpty()) out.add(nick)
        out.add(norm(t.shortName))
        val abbr = norm(t.abbr)
        if (abbr.length >= 2 && !abbr.contains(' ')) out.add(abbr)
        if (loc.isNotEmpty() && loc !in ambiguousLocations) out.add(loc)
        out.removeAll { it.length < 2 }
        return out
    }

    // Matching runs many times per render, from several threads (screens and the home calculation). Per games list
    // instance a prepared entry is kept (normalized names per team, answers per title); a small LRU of them means two
    // alternating lists never evict each other the way a single slot did.
    private const val CACHE_MAX = 2000
    private const val PREPARED_MAX = 6

    private class IdKey(val list: List<Game>) {
        override fun hashCode() = System.identityHashCode(list)
        override fun equals(other: Any?) = other is IdKey && other.list === list
    }

    /** One game with its location and identity strings normalized once. */
    private class GP(val g: Game, val homeLoc: String, val awayLoc: String, val homeId: String, val awayId: String)

    private class Prepared(games: List<Game>) {
        val entries: List<GP> = games.map {
            GP(it, norm(it.home.location), norm(it.away.location),
                norm(it.home.abbr).ifEmpty { norm(it.home.displayName) }, norm(it.away.abbr).ifEmpty { norm(it.away.displayName) })
        }
        val byId: Map<String, Game> = games.associateBy { it.id }
        /** What matching depends on (ids, leagues, start times, team names), never scores or clocks. */
        val shape: Long = shapeOf(games)
        private val namesMemo = HashMap<Pair<TeamSide, Set<String>>, Set<String>>()
        @Synchronized fun namesOf(s: TeamSide, ambiguous: Set<String>): Set<String> =
            namesMemo.getOrPut(s to ambiguous) { names(s, ambiguous) }
    }

    /** 64-bit FNV-1a over everything a match depends on. Same teams/times/ids = same shape, whatever the scores. */
    internal fun shapeOf(games: List<Game>): Long {
        var h = -0x340d631b7bdddcdbL
        fun add(x: String) { for (c in x) { h = h xor c.code.toLong(); h *= 0x100000001b3L }; h = h xor 0x1fL; h *= 0x100000001b3L }
        for (g in games) {
            add(g.id); add(g.league); add(g.startMillis.toString())
            for (t in listOf(g.home, g.away)) { add(t.displayName); add(t.shortName); add(t.abbr); add(t.location); add(t.nickname) }
        }
        return h
    }

    /**
     * Answers (title|start -> game id, "" = no game) per games shape, kept across score updates: a new scores list
     * with the same games reuses every earlier match (2026-11-20; a home redo was 3-7 s on the Onn when each score
     * update matched every list event again).
     */
    private class Answers {
        private val map = HashMap<String, String>()
        @Synchronized fun get(key: String): String? = map[key]
        @Synchronized fun put(key: String, id: String) { if (map.size >= CACHE_MAX) map.clear(); map[key] = id }
    }
    private val answersByShape = object : LinkedHashMap<Long, Answers>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Answers>?) = size > PREPARED_MAX * 2
    }
    private fun answersFor(shape: Long): Answers = synchronized(preparedLock) { answersByShape.getOrPut(shape) { Answers() } }

    private val preparedLock = Any()
    private val prepared = object : LinkedHashMap<IdKey, Prepared>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<IdKey, Prepared>?) = size > PREPARED_MAX
    }

    private fun preparedFor(games: List<Game>): Prepared {
        val key = IdKey(games)
        synchronized(preparedLock) { prepared[key]?.let { return it } }
        val p = Prepared(games) // built outside the lock; a racing duplicate is harmless
        synchronized(preparedLock) { return prepared.getOrPut(key) { p } }
    }

    fun find(title: String, itemStartMillis: Long?, games: List<Game>): Game? {
        val t = norm(title)
        if (t.isEmpty() || games.isEmpty()) return null
        val key = "$t|${itemStartMillis ?: ""}"
        val p = preparedFor(games)
        val answers = answersFor(p.shape)
        answers.get(key)?.let { id -> return if (id.isEmpty()) null else p.byId[id] }
        val found = compute(t, itemStartMillis, p)
        answers.put(key, found?.id ?: "")
        return found
    }

    private fun compute(t: String, itemStartMillis: Long?, prep: Prepared): Game? {
        val pool = if (itemStartMillis != null) prep.entries.filter { Math.abs(it.g.startMillis - itemStartMillis) <= DAY_WINDOW_MS } else prep.entries
        if (pool.isEmpty()) return null
        val padded = " $t "

        // Locations shared by two teams of the same pro league in the list ("New York", "Los Angeles") are not
        // used as names. Worked out per league, so an NFL city never blocks an NBA name or the other way round.
        val ambiguousByLeague = HashMap<String, Set<String>>()
        for (lg in SHARED_CITY_LEAGUES) {
            val teamsByLocation = HashMap<String, MutableSet<String>>()
            for (e in pool) if (e.g.league == lg) {
                if (e.homeLoc.isNotEmpty()) teamsByLocation.getOrPut(e.homeLoc) { HashSet() }.add(e.homeId)
                if (e.awayLoc.isNotEmpty()) teamsByLocation.getOrPut(e.awayLoc) { HashSet() }.add(e.awayId)
            }
            ambiguousByLeague[lg] = teamsByLocation.filter { it.value.size >= 2 }.keys
        }
        fun namesOf(s: TeamSide, league: String) = prep.namesOf(s, ambiguousByLeague[league] ?: emptySet())

        // Every place any known team name appears; a match inside a longer one is not a match
        // ("Michigan" inside "Michigan State").
        val all = ArrayList<Occ>()
        for (e in pool) for (s in listOf(e.g.home, e.g.away)) for (n in namesOf(s, e.g.league)) all.addAll(occurrences(padded, n))
        fun shadowed(o: Occ) = all.any { it.len > o.len && it.start <= o.start && it.end >= o.end }

        fun spans(s: TeamSide, league: String): List<Occ> =
            namesOf(s, league).flatMap { occurrences(padded, it) }.filter { !shadowed(it) }

        val candidates = pool.map { it.g }.filter { g ->
            val h = spans(g.home, g.league)
            val a = spans(g.away, g.league)
            h.any { x -> a.any { y -> x.end <= y.start || y.end <= x.start } }
        }
        if (candidates.size == 1) return candidates[0]
        if (candidates.isEmpty() || itemStartMillis == null) return null
        return candidates.filter { Math.abs(it.startMillis - itemStartMillis) <= START_WINDOW_MS }.singleOrNull()
    }

    /** The original, uncached matcher (kept so tests can check the prepared one gives the same answers). */
    internal fun referenceFind(title: String, itemStartMillis: Long?, games: List<Game>): Game? {
        val t = norm(title)
        if (t.isEmpty() || games.isEmpty()) return null
        val pool = if (itemStartMillis != null) games.filter { Math.abs(it.startMillis - itemStartMillis) <= DAY_WINDOW_MS } else games
        if (pool.isEmpty()) return null
        val padded = " $t "
        val ambiguousByLeague = HashMap<String, Set<String>>()
        for (lg in SHARED_CITY_LEAGUES) {
            val teamsByLocation = HashMap<String, MutableSet<String>>()
            for (g in pool) if (g.league == lg) for (s in listOf(g.home, g.away)) {
                val loc = norm(s.location)
                if (loc.isNotEmpty()) teamsByLocation.getOrPut(loc) { HashSet() }.add(norm(s.abbr).ifEmpty { norm(s.displayName) })
            }
            ambiguousByLeague[lg] = teamsByLocation.filter { it.value.size >= 2 }.keys
        }
        fun namesOf(s: TeamSide, league: String) = names(s, ambiguousByLeague[league] ?: emptySet())
        val all = ArrayList<Occ>()
        for (g in pool) for (s in listOf(g.home, g.away)) for (n in namesOf(s, g.league)) all.addAll(occurrences(padded, n))
        fun shadowed(o: Occ) = all.any { it.len > o.len && it.start <= o.start && it.end >= o.end }
        fun spans(s: TeamSide, league: String): List<Occ> = namesOf(s, league).flatMap { occurrences(padded, it) }.filter { !shadowed(it) }
        val candidates = pool.filter { g ->
            val h = spans(g.home, g.league)
            val a = spans(g.away, g.league)
            h.any { x -> a.any { y -> x.end <= y.start || y.end <= x.start } }
        }
        if (candidates.size == 1) return candidates[0]
        if (candidates.isEmpty() || itemStartMillis == null) return null
        return candidates.filter { Math.abs(it.startMillis - itemStartMillis) <= START_WINDOW_MS }.singleOrNull()
    }
}

/** Score text for the list rows and the details card. */
object ScoreText {
    private fun tag(s: TeamSide) = s.abbr.ifBlank { s.shortName }.ifBlank { s.displayName }
    private fun name(s: TeamSide) = s.shortName.ifBlank { s.displayName }
    private fun pts(s: TeamSide) = s.score.ifBlank { "0" }

    // College basketball plays in halves, so ESPN's live text reads "12:34 - 2nd Half": only "Halftime" is the break there.
    private fun isHalf(g: Game) = g.shortDetail.contains(if (g.league == "cbb") "halftime" else "half", ignoreCase = true)

    /** "Q3 8:42" built from period and clock (ESPN's own text reads "8:42 - 3rd"); its text for breaks and delays. */
    private fun liveStatus(g: Game, sep: String): String {
        val d = g.shortDetail
        return when {
            isHalf(g) -> if (sep == " ") "Half" else "Halftime"
            d.contains("end", ignoreCase = true) || d.contains("delay", ignoreCase = true) -> d
            // College basketball: two halves (H1, H2), then OT, 2OT...
            g.league == "cbb" && g.period in 1..2 -> "H${g.period}$sep${g.clock}".trimEnd(' ', '·')
            g.league == "cbb" && g.period > 2 -> {
                val ot = g.period - 2
                "${if (ot == 1) "OT" else "${ot}OT"}$sep${g.clock}".trimEnd(' ', '·')
            }
            g.period in 1..4 -> "Q${g.period}$sep${g.clock}".trimEnd(' ', '·')
            g.league == "nba" && g.period > 4 -> {
                // NBA: 4 quarters, then OT, 2OT, 3OT...
                val ot = g.period - 4
                val label = if (ot == 1) "OT" else "${ot}OT"
                "$label$sep${g.clock}".trimEnd(' ', '·')
            }
            g.period > 4 -> if (g.league == "nfl" && g.clock.isNotBlank()) "OT$sep${g.clock}" else d.ifBlank { "OT" }
            d.isNotBlank() -> d
            else -> "Live"
        }
    }

    private val KICKOFF = DateTimeFormatter.ofPattern("EEE h:mm a")

    private fun finalStatus(g: Game): String = if (g.shortDetail.startsWith("Final", ignoreCase = true)) g.shortDetail else "Final"

    /** A team's points for big score displays ("0" when ESPN sent none). */
    fun points(s: TeamSide): String = pts(s)

    /** Status under a big score: "Q3 · 8:42", "Halftime", "Final", "Postponed"; null before the game starts. */
    fun status(g: Game): String? {
        ScoreStatus.cancelLabel(g)?.let { return it }
        return when (g.state) {
            "in" -> liveStatus(g, " · ")
            "post" -> finalStatus(g)
            else -> null
        }
    }

    /** One short line for a list row; null before the game starts. */
    fun rowLine(g: Game): String? {
        ScoreStatus.cancelLabel(g)?.let { return it }
        val score = "${tag(g.away)} ${pts(g.away)} – ${tag(g.home)} ${pts(g.home)}"
        return when (g.state) {
            "in" -> "$score · ${liveStatus(g, " ")}"
            "post" -> "${finalStatus(g)} · $score"
            else -> null
        }
    }

    /** Lines for the details card; the first one is the headline. Kickoff is shown in [zone] (the TV's own). */
    fun cardLines(g: Game, zone: ZoneId = ZoneId.systemDefault()): List<String> {
        val out = ArrayList<String>()
        val cancelled = ScoreStatus.cancelLabel(g)
        if (cancelled != null) {
            fun side(s: TeamSide) = if (s.record.isBlank()) name(s) else "${name(s)} (${s.record})"
            out.add("${side(g.away)} at ${side(g.home)}")
            out.add(cancelled)
        } else if (g.state == "in" || g.state == "post") {
            out.add("${name(g.away)} ${pts(g.away)} – ${name(g.home)} ${pts(g.home)}")
            out.add(if (g.state == "in") liveStatus(g, " · ") else finalStatus(g))
            val extra = listOf(g.away.record, g.home.record, g.broadcast).filter { it.isNotBlank() }
            if (extra.isNotEmpty()) out.add(extra.joinToString(" · "))
        } else {
            fun side(s: TeamSide) = if (s.record.isBlank()) name(s) else "${name(s)} (${s.record})"
            out.add("${side(g.away)} at ${side(g.home)}")
            out.add((if (g.league == "nba" || g.league == "cbb") "Tip-off " else "Kickoff ") + KICKOFF.format(java.time.Instant.ofEpochMilli(g.startMillis).atZone(zone)))
            if (g.broadcast.isNotBlank()) out.add(g.broadcast)
        }
        return out
    }
}

/**
 * Cached scores and schedule for all leagues, refreshed in the background: today (and yesterday before 5 am,
 * New York time) on the live timing, plus the next two days at most every 30 minutes, cached separately.
 * Today's leagues are fetched at once and each draws as it lands; the next two days follow. Timings go to the report.
 */
object ScoreFeed {
    private const val LIVE_MS = 30_000L
    private const val IDLE_MS = 5 * 60_000L
    private const val BACKOFF_MS = 2 * 60_000L
    private const val FUTURE_MS = 30 * 60_000L
    private val LEAGUES = listOf("nfl", "cfb", "nba", "cbb")
    private val NY = ZoneId.of("America/New_York")
    private val DAY = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** Games older than this (no successful fetch since) are ignored, so an old "in" never keeps an item live. */
    const val MAX_AGE_MS = 15 * 60_000L

    /** Pure: is a fetch made at [lastOk] still good at [now]? */
    fun isFresh(lastOk: Long?, now: Long): Boolean = lastOk != null && now - lastOk <= MAX_AGE_MS

    /** Leagues fetched after the others, so their big answers don't slow the first draw (college basketball, 2026-11-20). */
    val LATE_LEAGUES = setOf("cbb")

    /** Pure: the order today's due leagues are fetched in: the quick ones together first, then the late ones. */
    fun todayWaves(due: List<String>): List<List<String>> =
        listOf(due.filter { it !in LATE_LEAGUES }, due.filter { it in LATE_LEAGUES }).filter { it.isNotEmpty() }

    private val lock = Any()
    private var todayByLeague: Map<String, List<Game>> = emptyMap()
    private var futureByLeague: Map<String, List<Game>> = emptyMap()
    private var version = 0
    private var cachedKey = ""
    private var cachedList: List<Game> = emptyList()
    private val lastOk = HashMap<String, Long>()
    private val lastFail = HashMap<String, Long>()
    private val futureOk = HashMap<String, Long>()
    private val futureFail = HashMap<String, Long>()
    private var running = false

    // ---- Saved scores (user, 2026-11-20): the last answers are kept on disk so a fresh start can draw at once ----
    /** Where the last answers are saved; null until [init]. */
    @Volatile private var dir: File? = null
    private val restoredOnce = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Saved coming-days answers are used up to this old. Today's follow [MAX_AGE_MS] like live ones. */
    private const val FUTURE_KEEP_MS = 3 * 60 * 60_000L

    fun init(ctx: Context) { dir = File(ctx.filesDir, "scores").apply { mkdirs() } }

    private fun save(name: String, at: Long, pages: List<String>) {
        val d = dir ?: return
        try {
            val tmp = File(d, "$name.tmp")
            tmp.writeText(JSONObject().put("at", at).put("pages", JSONArray(pages)).toString())
            if (!tmp.renameTo(File(d, "$name.json"))) tmp.delete()
        } catch (_: Exception) {}
    }

    private fun load(name: String): Pair<Long, List<String>>? = try {
        val f = File(dir ?: return null, "$name.json")
        if (!f.isFile) null else JSONObject(f.readText()).let { o ->
            val a = o.getJSONArray("pages")
            o.getLong("at") to (0 until a.length()).map { a.getString(it) }
        }
    } catch (_: Exception) { null }

    /**
     * Once per app start (off the main thread): puts the saved answers back if they are recent enough (today's: up to
     * [MAX_AGE_MS], so they count as fresh; coming days: [FUTURE_KEEP_MS]). They are re-fetched as usual right after.
     * True when anything was put back.
     */
    fun restoreOnce(now: Long = System.currentTimeMillis()): Boolean {
        if (!restoredOnce.compareAndSet(false, true)) return false
        var any = false
        for (lg in LEAGUES) {
            load("today-$lg")?.takeIf { now - it.first in 0..MAX_AGE_MS }?.let { (at, pages) ->
                val games = parsePages(pages, lg) ?: return@let
                synchronized(lock) {
                    if (lastOk[lg] == null) { todayByLeague = todayByLeague + (lg to games); lastOk[lg] = at; version++; any = true }
                }
            }
            load("future-$lg")?.takeIf { now - it.first in 0..FUTURE_KEEP_MS }?.let { (at, pages) ->
                val games = parsePages(pages, lg) ?: return@let
                synchronized(lock) {
                    if (futureOk[lg] == null) { futureByLeague = futureByLeague + (lg to games); futureOk[lg] = at; version++; any = true }
                }
            }
        }
        if (any) Report.log("Scores: showing saved scores from the last run while new ones load")
        return any
    }

    private fun parsePages(pages: List<String>, league: String): List<Game>? = try {
        val m = LinkedHashMap<String, Game>()
        for (p in pages) for (g in ScoreParse.parse(p, league)) m.putIfAbsent(g.id, g)
        m.values.toList()
    } catch (_: Exception) { null }

    /**
     * All known games: today's (and yesterday's before 5 am) plus the next two days, merged by game id. Today's games
     * of a league are left out when its last good fetch is older than [MAX_AGE_MS], so an old "in" never keeps an item
     * live. The same List instance is returned until the data (or which leagues are fresh) changes (ScoreMatch caches
     * per instance).
     */
    fun latest(now: Long = System.currentTimeMillis()): List<Game> = synchronized(lock) {
        val fresh = LEAGUES.filter { isFresh(lastOk[it], now) }
        val key = "$version:${fresh.joinToString(",")}"
        if (key != cachedKey) {
            cachedList = LEAGUES.flatMap { merged(it, it in fresh) }
            cachedKey = key
        }
        cachedList
    }

    /** [latest] restricted to [leagues] (empty = all). The same instance until the data changes, so ScoreMatch keeps its cache. */
    fun forLeagues(leagues: Set<String>): List<Game> = GameViews.forLeagues(latest(), leagues)

    private fun merged(league: String, todayFresh: Boolean): List<Game> {
        val m = LinkedHashMap<String, Game>()
        if (todayFresh) for (g in todayByLeague[league] ?: emptyList()) m.putIfAbsent(g.id, g)
        for (g in futureByLeague[league] ?: emptyList()) if (g.state == "pre" || todayFresh) m.putIfAbsent(g.id, g)
        return m.values.toList()
    }

    private fun isDue(league: String, now: Long): Boolean {
        val ok = lastOk[league]
        val fail = lastFail[league]
        if (fail != null && (ok == null || fail > ok)) return now - fail >= BACKOFF_MS
        if (ok == null) return true
        val live = todayByLeague[league]?.any { it.state == "in" } == true
        return now - ok >= if (live) LIVE_MS else IDLE_MS
    }

    private fun isFutureDue(league: String, now: Long): Boolean {
        val ok = futureOk[league]
        val fail = futureFail[league]
        if (fail != null && (ok == null || fail > ok)) return now - fail >= BACKOFF_MS
        if (ok == null) return true
        return now - ok >= FUTURE_MS
    }

    /** Call from the UI thread. Fetches in the background when due; onUpdate runs on the main thread if the games changed. */
    fun refreshIfDue(onUpdate: () -> Unit) {
        val now = System.currentTimeMillis()
        val due = synchronized(lock) {
            if (running) return
            val d = LEAGUES.filter { isDue(it, now) || isFutureDue(it, now) }.map { Triple(it, isDue(it, now), isFutureDue(it, now)) }
            if (d.isEmpty()) return
            running = true
            d
        }
        val t = Thread {
            val first = synchronized(lock) { firstRun.also { firstRun = false } }
            var shown = latest()
            val showLock = Any()
            // Draw as soon as any league's answer changes what's on screen.
            fun showIfChanged() {
                val now2 = latest()
                val changed = synchronized(showLock) { (now2 != shown).also { if (it) shown = now2 } }
                if (changed) Handler(Looper.getMainLooper()).post(onUpdate)
            }
            val before = shown
            try {
                // 1) Today: the quick leagues at once (each draws when it lands), then college basketball.
                val todayDue = due.filter { it.second }.map { it.first }
                for (wave in todayWaves(todayDue)) {
                    val t0 = System.currentTimeMillis()
                    val took = runParallel(wave) { lg -> fetchToday(lg).also { showIfChanged() } }
                    recordTiming("today", wave, took, System.currentTimeMillis() - t0, first)
                }
                // 2) Then the next two days.
                val futureDue = due.filter { it.third }.map { it.first }
                if (futureDue.isNotEmpty()) {
                    val t0 = System.currentTimeMillis()
                    val took = runParallel(futureDue) { lg -> fetchFuture(lg).also { showIfChanged() } }
                    recordTiming("next 2 days", futureDue, took, System.currentTimeMillis() - t0, first)
                }
            } finally {
                synchronized(lock) { running = false }
            }
            val after = latest()
            if (after != before) {
                val n = after.count { it.league == "nfl" }
                val c = after.count { it.league == "cfb" }
                val b = after.count { it.league == "nba" }
                val cb = after.count { it.league == "cbb" }
                Report.logOnce("scores-count-$n-$c-$b-$cb", "Scores: $n NFL, $c college football, $b NBA and $cb college basketball games over 3 days")
            }
        }
        t.isDaemon = true
        t.name = "scores"
        t.start()
    }

    private var firstRun = true

    /** Runs [work] for each league on its own thread, waits for all; returns league -> (ms taken, ok). */
    private fun runParallel(leagues: List<String>, work: (String) -> Boolean): Map<String, Pair<Long, Boolean>> {
        val out = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Boolean>>()
        val threads = leagues.map { lg ->
            Thread {
                val s = System.currentTimeMillis()
                val ok = try { work(lg) } catch (_: Exception) { false }
                out[lg] = (System.currentTimeMillis() - s) to ok
            }.apply { isDaemon = true; name = "scores-$lg"; start() }
        }
        for (th in threads) th.join()
        return out
    }

    private val LABEL = mapOf("nfl" to "NFL", "cfb" to "college", "nba" to "NBA", "cbb" to "college bb")
    private val CLOCK = DateTimeFormatter.ofPattern("h:mm:ss a")
    private val timings = ArrayDeque<String>()

    /** Pure: one timing line, e.g. "today: NFL 0.8s, college 1.4s, NBA 0.6s; all done in 1.4s". */
    internal fun timingLine(part: String, leagues: List<String>, took: Map<String, Pair<Long, Boolean>>, totalMs: Long, first: Boolean): String {
        fun sec(ms: Long) = String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
        val each = leagues.joinToString(", ") { lg ->
            val (ms, ok) = took[lg] ?: (0L to false)
            "${LABEL[lg] ?: lg} ${sec(ms)}" + if (ok) "" else " (failed)"
        }
        return "$part: $each; all done in ${sec(totalMs)}" + if (first) " (first load since app start)" else ""
    }

    private fun recordTiming(part: String, leagues: List<String>, took: Map<String, Pair<Long, Boolean>>, totalMs: Long, first: Boolean) {
        val line = "${ZonedDateTime.now().format(CLOCK)}  ${timingLine(part, leagues, took, totalMs, first)}"
        android.util.Log.i("StreamLink", "Scores timing $line")
        synchronized(timings) {
            timings.addLast(line)
            while (timings.size > 8) timings.removeFirst()
        }
    }

    /** The last few score-fetch timings (oldest first), for the "What happened?" report. Survives a new play. */
    fun timingLines(): List<String> = synchronized(timings) { timings.toList() }

    private fun fetchToday(league: String): Boolean {
        return try {
            val now = ZonedDateTime.now(NY)
            val days = arrayListOf(now)
            if (now.hour < 5) days.add(now.minusDays(1))
            val merged = LinkedHashMap<String, Game>()
            val pages = ArrayList<String>()
            for ((i, day) in days.withIndex()) {
                val games = try {
                    val raw = download(url(league, DAY.format(day)))
                    ScoreParse.parse(raw, league).also { pages.add(raw) }
                } catch (e: Exception) {
                    if (i == 0) throw e else emptyList() // yesterday is a bonus
                }
                for (g in games) merged.putIfAbsent(g.id, g)
            }
            val at = System.currentTimeMillis()
            synchronized(lock) {
                val list = merged.values.toList()
                if (todayByLeague[league] != list) { todayByLeague = todayByLeague + (league to list); version++ }
                lastOk[league] = at
                lastFail.remove(league)
            }
            save("today-$league", at, pages)
            true
        } catch (e: Exception) {
            synchronized(lock) { lastFail[league] = System.currentTimeMillis() }
            val why = e.javaClass.simpleName
            Report.logOnce("scores-fail-$league-$why", "Scores: couldn't reach ESPN ($why${e.message?.let { ": " + it.take(60) } ?: ""})")
            false
        }
    }

    /** The next two days. A failure here keeps what was known and never touches today's data. */
    private fun fetchFuture(league: String): Boolean {
        return try {
            val now = ZonedDateTime.now(NY)
            val merged = LinkedHashMap<String, Game>()
            val pages = ArrayList<String>()
            for (k in 1L..2L) {
                val raw = download(url(league, DAY.format(now.plusDays(k))))
                for (g in ScoreParse.parse(raw, league)) merged.putIfAbsent(g.id, g)
                pages.add(raw)
            }
            val at = System.currentTimeMillis()
            synchronized(lock) {
                val list = merged.values.toList()
                if (futureByLeague[league] != list) { futureByLeague = futureByLeague + (league to list); version++ }
                futureOk[league] = at
                futureFail.remove(league)
            }
            save("future-$league", at, pages)
            true
        } catch (e: Exception) {
            synchronized(lock) { futureFail[league] = System.currentTimeMillis() }
            val why = e.javaClass.simpleName
            Report.logOnce("scores-fail-future-$league-$why", "Scores: couldn't get the coming days from ESPN ($why${e.message?.let { ": " + it.take(60) } ?: ""})")
            false
        }
    }

    private fun url(league: String, date: String) =
        if (league == "nfl") "https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard?dates=$date"
        else if (league == "nba") "https://site.api.espn.com/apis/site/v2/sports/basketball/nba/scoreboard?dates=$date"
        // all Division I games (groups=50); a busy day has 150+
        else if (league == "cbb") "https://site.api.espn.com/apis/site/v2/sports/basketball/mens-college-basketball/scoreboard?dates=$date&groups=50&limit=500"
        else "https://site.api.espn.com/apis/site/v2/sports/football/college-football/scoreboard?dates=$date&groups=80&limit=300"

    private fun download(address: String): String {
        val c = URL(address).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "StreamLink-scores")
            val code = c.responseCode
            if (code != 200) throw IOException("HTTP $code")
            val buf = ByteArrayOutputStream()
            c.inputStream.use { ins ->
                val tmp = ByteArray(16 * 1024)
                while (true) {
                    val n = ins.read(tmp)
                    if (n < 0) break
                    buf.write(tmp, 0, n)
                    if (buf.size() > 8 * 1024 * 1024) throw IOException("answer too large")
                }
            }
            val body = buf.toString("UTF-8")
            if (!body.contains("\"events\"")) throw IOException("unexpected answer")
            return body
        } finally {
            c.disconnect()
        }
    }
}
