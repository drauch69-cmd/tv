package com.streamlink.tv

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.ByteArrayOutputStream
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
)

/** One NFL or college football game from ESPN's scoreboard. */
data class Game(
    val id: String,
    val league: String,       // "nfl" | "cfb"
    val startMillis: Long,
    val state: String,        // "pre" | "in" | "post"
    val shortDetail: String,  // "Final", "Q3 - 8:42", "Halftime", "11/22 - 7:15 PM EST"
    val period: Int,
    val clock: String,
    val home: TeamSide,
    val away: TeamSide,
    val broadcast: String,
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
        val bs = comp.optJSONArray("broadcasts")
        if (bs != null) {
            val names = LinkedHashSet<String>()
            for (i in 0 until bs.length()) {
                val arr = bs.optJSONObject(i)?.optJSONArray("names") ?: continue
                val n = if (arr.length() > 0 && !arr.isNull(0)) arr.optString(0, "").trim() else ""
                if (n.isNotEmpty()) names.add(n)
            }
            broadcast = names.take(2).joinToString(" / ")
        }
        return Game(
            id = id, league = league, startMillis = start, state = state,
            shortDetail = str(type, "shortDetail"),
            period = status?.optInt("period", 0) ?: 0,
            clock = str(status, "displayClock"),
            home = home, away = away, broadcast = broadcast,
        )
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

    /** Lowercase, no accents, everything but letters and digits becomes one space. */
    fun norm(s: String): String {
        val d = Normalizer.normalize(s, Normalizer.Form.NFD)
        val sb = StringBuilder(d.length)
        for (ch in d) {
            if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
            if (ch.isLetterOrDigit()) sb.append(ch.lowercaseChar()) else sb.append(' ')
        }
        return sb.toString().trim().replace(Regex(" +"), " ")
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

    private fun names(t: TeamSide, league: String, ambiguousLocations: Set<String>): Set<String> {
        val loc = norm(t.location)
        val nick = norm(t.nickname)
        val out = LinkedHashSet<String>()
        out.add(norm(t.displayName))
        if (loc.isNotEmpty() && nick.isNotEmpty()) out.add("$loc $nick")
        if (nick.isNotEmpty()) out.add(nick)
        out.add(norm(t.shortName))
        val abbr = norm(t.abbr)
        if (abbr.length >= 2 && !abbr.contains(' ')) out.add(abbr)
        if (loc.isNotEmpty() && (league != "nfl" || loc !in ambiguousLocations)) out.add(loc)
        out.removeAll { it.length < 2 }
        return out
    }

    fun find(title: String, itemStartMillis: Long?, games: List<Game>): Game? {
        val t = norm(title)
        if (t.isEmpty() || games.isEmpty()) return null
        val pool = if (itemStartMillis != null) games.filter { Math.abs(it.startMillis - itemStartMillis) <= DAY_WINDOW_MS } else games
        if (pool.isEmpty()) return null
        val padded = " $t "

        // NFL locations shared by two teams in the list ("New York", "Los Angeles") are not used.
        val teamsByLocation = HashMap<String, MutableSet<String>>()
        for (g in pool) if (g.league == "nfl") for (s in listOf(g.home, g.away)) {
            val loc = norm(s.location)
            if (loc.isNotEmpty()) teamsByLocation.getOrPut(loc) { HashSet() }.add(norm(s.abbr).ifEmpty { norm(s.displayName) })
        }
        val ambiguous = teamsByLocation.filter { it.value.size >= 2 }.keys

        val perTeam = HashMap<TeamSide, Set<String>>()
        fun namesOf(s: TeamSide, league: String) = perTeam.getOrPut(s) { names(s, league, ambiguous) }

        // Every place any known team name appears; a match inside a longer one is not a match
        // ("Michigan" inside "Michigan State").
        val all = ArrayList<Occ>()
        for (g in pool) for (s in listOf(g.home, g.away)) for (n in namesOf(s, g.league)) all.addAll(occurrences(padded, n))
        fun shadowed(o: Occ) = all.any { it.len > o.len && it.start <= o.start && it.end >= o.end }

        fun spans(s: TeamSide, league: String): List<Occ> =
            namesOf(s, league).flatMap { occurrences(padded, it) }.filter { !shadowed(it) }

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

    private fun isHalf(g: Game) = g.shortDetail.contains("half", ignoreCase = true)

    /** "Q3 8:42" built from period and clock (ESPN's own text reads "8:42 - 3rd"); its text for breaks and delays. */
    private fun liveStatus(g: Game, sep: String): String {
        val d = g.shortDetail
        return when {
            isHalf(g) -> if (sep == " ") "Half" else "Halftime"
            d.contains("end", ignoreCase = true) || d.contains("delay", ignoreCase = true) -> d
            g.period in 1..4 -> "Q${g.period}$sep${g.clock}".trimEnd(' ', '·')
            g.period > 4 -> if (g.league == "nfl" && g.clock.isNotBlank()) "OT$sep${g.clock}" else d.ifBlank { "OT" }
            d.isNotBlank() -> d
            else -> "Live"
        }
    }

    private val KICKOFF = DateTimeFormatter.ofPattern("EEE h:mm a")

    private fun finalStatus(g: Game): String = if (g.shortDetail.startsWith("Final", ignoreCase = true)) g.shortDetail else "Final"

    /** One short line for a list row; null before the game starts. */
    fun rowLine(g: Game): String? {
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
        if (g.state == "in" || g.state == "post") {
            out.add("${name(g.away)} ${pts(g.away)} – ${name(g.home)} ${pts(g.home)}")
            out.add(if (g.state == "in") liveStatus(g, " · ") else finalStatus(g))
            val extra = listOf(g.away.record, g.home.record, g.broadcast).filter { it.isNotBlank() }
            if (extra.isNotEmpty()) out.add(extra.joinToString(" · "))
        } else {
            fun side(s: TeamSide) = if (s.record.isBlank()) name(s) else "${name(s)} (${s.record})"
            out.add("${side(g.away)} at ${side(g.home)}")
            out.add("Kickoff " + KICKOFF.format(java.time.Instant.ofEpochMilli(g.startMillis).atZone(zone)))
            if (g.broadcast.isNotBlank()) out.add(g.broadcast)
        }
        return out
    }
}

/** Cached live scores for both leagues, refreshed in the background. */
object ScoreFeed {
    private const val LIVE_MS = 30_000L
    private const val IDLE_MS = 5 * 60_000L
    private const val BACKOFF_MS = 2 * 60_000L
    private val LEAGUES = listOf("nfl", "cfb")
    private val NY = ZoneId.of("America/New_York")
    private val DAY = DateTimeFormatter.ofPattern("yyyyMMdd")

    private val lock = Any()
    private var byLeague: Map<String, List<Game>> = emptyMap()
    private val lastOk = HashMap<String, Long>()
    private val lastFail = HashMap<String, Long>()
    private var running = false

    fun latest(): List<Game> = synchronized(lock) { LEAGUES.flatMap { byLeague[it] ?: emptyList() } }

    private fun isDue(league: String, now: Long): Boolean {
        val ok = lastOk[league]
        val fail = lastFail[league]
        if (fail != null && (ok == null || fail > ok)) return now - fail >= BACKOFF_MS
        if (ok == null) return true
        val live = byLeague[league]?.any { it.state == "in" } == true
        return now - ok >= if (live) LIVE_MS else IDLE_MS
    }

    /** Call from the UI thread. Fetches in the background when due; onUpdate runs on the main thread if the games changed. */
    fun refreshIfDue(onUpdate: () -> Unit) {
        val now = System.currentTimeMillis()
        val due = synchronized(lock) {
            if (running) return
            val d = LEAGUES.filter { isDue(it, now) }
            if (d.isEmpty()) return
            running = true
            d
        }
        val t = Thread {
            val before = latest()
            try {
                for (league in due) fetch(league)
            } finally {
                synchronized(lock) { running = false }
            }
            val after = latest()
            if (after != before) {
                val n = after.count { it.league == "nfl" }
                val c = after.count { it.league == "cfb" }
                Report.logOnce("scores-count-$n-$c", "Scores: $n NFL and $c college games today")
                Handler(Looper.getMainLooper()).post(onUpdate)
            }
        }
        t.isDaemon = true
        t.name = "scores"
        t.start()
    }

    private fun fetch(league: String) {
        try {
            val now = ZonedDateTime.now(NY)
            val days = arrayListOf(now)
            if (now.hour < 5) days.add(now.minusDays(1))
            val merged = LinkedHashMap<String, Game>()
            for ((i, day) in days.withIndex()) {
                val games = try {
                    ScoreParse.parse(download(url(league, DAY.format(day))), league)
                } catch (e: Exception) {
                    if (i == 0) throw e else emptyList() // yesterday is a bonus
                }
                for (g in games) merged.putIfAbsent(g.id, g)
            }
            synchronized(lock) {
                byLeague = byLeague + (league to merged.values.toList())
                lastOk[league] = System.currentTimeMillis()
                lastFail.remove(league)
            }
        } catch (e: Exception) {
            synchronized(lock) { lastFail[league] = System.currentTimeMillis() }
            val why = e.javaClass.simpleName
            Report.logOnce("scores-fail-$league-$why", "Scores: couldn't reach ESPN ($why${e.message?.let { ": " + it.take(60) } ?: ""})")
        }
    }

    private fun url(league: String, date: String) =
        if (league == "nfl") "https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard?dates=$date"
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
