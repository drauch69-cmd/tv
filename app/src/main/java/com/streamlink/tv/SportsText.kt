package com.streamlink.tv

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure text for the sports screens (no Android APIs; unit tested). Team names come from ESPN only. */
object SportsText {
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

    private fun fullName(s: TeamSide) = s.shortName.ifBlank { s.displayName }.ifBlank { s.abbr }

    /** "Bills at Chiefs". */
    fun matchup(g: Game): String = "${fullName(g.away)} at ${fullName(g.home)}"

    /** "1 stream" / "4 streams" / "No streams". */
    fun streamsText(n: Int): String = when {
        n <= 0 -> "No streams"
        n == 1 -> "1 stream"
        else -> "$n streams"
    }

    /** Kickoff clock time in [zone], e.g. "7:15 PM". */
    fun kickoff(startMillis: Long, zone: ZoneId): String = TIME.format(Instant.ofEpochMilli(startMillis).atZone(zone))

    /** Second line of a game row: the score line when live or final, else the kickoff time; then the channel. */
    fun gameMeta(g: Game, zone: ZoneId): String {
        val main = ScoreText.rowLine(g) ?: kickoff(g.startMillis, zone)
        return if (g.broadcast.isBlank()) main else "$main  ·  ${g.broadcast.trim()}"
    }

    /** Home tile line for a sport: "2 live · 6 with streams" / "No streams yet" / a hint when no list is tagged. */
    fun sportTileLine(taggedLists: Int, f: SportFolders?): String {
        if (taggedLists <= 0) return "No list tagged yet"
        val games = f?.games ?: emptyList()
        val withStreams = games.count { it.streams.isNotEmpty() }
        if (withStreams == 0) return "No streams yet"
        val live = games.count { it.game.state == "in" && it.streams.isNotEmpty() }
        return if (live > 0) "$live live · $withStreams with streams" else "$withStreams with streams"
    }

    /** Home 24/7 tile line. */
    fun alwaysOnLine(n: Int): String = when {
        n <= 0 -> "No channels yet"
        n == 1 -> "1 channel"
        else -> "$n channels"
    }

    /** What the sport screen says when it has no game rows to show. */
    fun emptyMessage(league: String, taggedLists: Int, f: SportFolders, withStreamsOnly: Boolean): String {
        val label = Sports.label(league).ifEmpty { league }
        return when {
            taggedLists <= 0 -> "Tag a stream list as $label on the phone page (Lists tab) to see games here."
            f.games.isEmpty() -> "No $label games today or in the next 2 days."
            withStreamsOnly && f.games.none { it.streams.isNotEmpty() } -> "No games with streams yet. Turn on All games to see the schedule."
            else -> "No games to show."
        }
    }

    /** Text of the "All games" chip. */
    fun allGamesChip(on: Boolean): String = "All games: " + if (on) "On" else "Off"

    const val NO_STREAMS_FOR_GAME = "No streams found for this game yet. They often appear shortly before kickoff."
    const val NO_ALWAYS_ON = "No 24/7 channels in your lists."
}
