package com.streamlink.tv

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

// Sports folders: sorts every stream of every sport-tagged saved list into the game it belongs to.
// Pure Kotlin (no Android APIs). Titles and subtitles are untrusted page text; only ESPN team names
// (through ScoreMatch) decide where an item goes, never site wording.

/** One stream (an event of a saved list) and the list it came from. */
data class GameStream(val listId: String, val listName: String, val item: ListItem)

/** A game and every stream found for it, from all tagged lists of its sport (list order, then page order). */
data class GameFolder(val game: Game, val streams: List<GameStream>)

/**
 * One sport's folders. [games] holds every game in the window (even with no streams, for an "All games"
 * toggle), in ESPN order; [other] holds the tagged lists' items that matched no game.
 */
data class SportFolders(val league: String, val games: List<GameFolder>, val other: List<GameStream>)

object GameFolders {
    /** The key two items are the same stream by: the address with one trailing slash removed. */
    fun urlKey(url: String): String = url.trim().removeSuffix("/")

    /** Start of today (in [zone]) until the end of today + 2 days (exclusive end). */
    fun window(now: Long, zone: ZoneId): Pair<Long, Long> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return today.atStartOfDay(zone).toInstant().toEpochMilli() to today.plusDays(3).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /**
     * Sorts the items of the lists tagged [league] into games.
     * - Only lists with sport == [league] and games with league == [league] count.
     * - Games in the window (today to the end of today + 2, in [zone]) plus any game still live (state "in").
     * - An item is matched with [ScoreMatch.find] on its title, else on title + subtitle. Matched: that game's
     *   streams; unmatched: [SportFolders.other].
     * - Always-on channels are never placed anywhere; finished items are placed only when they match a game
     *   (never in other).
     * - The same address (see [urlKey]) is placed once: first list in saved order, first item in page order.
     */
    fun build(league: String, games: List<Game>, lists: List<Pair<SavedList, List<ListItem>>>, now: Long, zone: ZoneId): SportFolders {
        val (from, to) = window(now, zone)
        val inWindow = games.filter { it.league == league && (it.state == "in" || it.startMillis in from until to) }
        val perGame = LinkedHashMap<String, MutableList<GameStream>>()
        for (g in inWindow) perGame[g.id] = ArrayList()
        val other = ArrayList<GameStream>()
        val seen = HashSet<String>()
        for ((list, items) in lists) {
            if (list.sport != league) continue
            for (item in items) {
                val start = item.startMillis
                if (start != null && ListStatus.isAlwaysOn(start, now)) continue
                val finished = ListStatus.of(item, now) == ListStatus.FINISHED
                val game = ScoreMatch.find(item.title, start, inWindow)
                    ?: if (item.subtitle.isNotBlank()) ScoreMatch.find(item.title + " " + item.subtitle, start, inWindow) else null
                if (game == null && finished) continue
                if (!seen.add(urlKey(item.url))) continue
                val stream = GameStream(list.id, list.name, item)
                if (game != null) perGame.getValue(game.id).add(stream) else other.add(stream)
            }
        }
        return SportFolders(league, inWindow.map { GameFolder(it, perGame.getValue(it.id)) }, other)
    }

    /** One quiet report line, e.g. "Sports: NFL 4 games (3 with streams), 11 streams, 2 other". */
    fun summary(f: SportFolders): String {
        val withStreams = f.games.count { it.streams.isNotEmpty() }
        val streams = f.games.sumOf { it.streams.size }
        val label = Sports.label(f.league).ifEmpty { f.league }
        return "Sports: $label ${f.games.size} ${if (f.games.size == 1) "game" else "games"} ($withStreams with streams), " +
            "$streams ${if (streams == 1) "stream" else "streams"}, ${f.other.size} other"
    }
}

/**
 * The sport screen's groups. "Other" is not a section: the screen shows [SportFolders.other] on its own, below
 * the sections.
 */
object GameSections {
    data class Section(val key: String, val label: String, val games: List<GameFolder>)

    /**
     * Sections in order: "live" (Live now), "today" (Today), "tomorrow" (Tomorrow), "day3" (weekday name of
     * today + 2), "final" (Final, most recent first). Others sort by start time, then away team name. Empty
     * sections are left out. With [withStreamsOnly], games without any stream are hidden (live ones too).
     */
    fun group(f: SportFolders, now: Long, zone: ZoneId, withStreamsOnly: Boolean): List<Section> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val shown = if (withStreamsOnly) f.games.filter { it.streams.isNotEmpty() } else f.games
        fun day(g: Game): LocalDate = Instant.ofEpochMilli(g.startMillis).atZone(zone).toLocalDate()
        val byStart = compareBy<GameFolder>({ it.game.startMillis }, { it.game.away.displayName })
        val live = shown.filter { it.game.state == "in" }
        val post = shown.filter { it.game.state == "post" }
        val pre = shown.filter { it.game.state != "in" && it.game.state != "post" }
        val dayName: DayOfWeek = today.plusDays(2).dayOfWeek
        val out = listOf(
            Section("live", "Live now", live.sortedWith(byStart)),
            Section("today", "Today", pre.filter { !day(it.game).isAfter(today) }.sortedWith(byStart)),
            Section("tomorrow", "Tomorrow", pre.filter { day(it.game) == today.plusDays(1) }.sortedWith(byStart)),
            Section("day3", dayName.getDisplayName(TextStyle.FULL, Locale.ENGLISH), pre.filter { day(it.game).isAfter(today.plusDays(1)) }.sortedWith(byStart)),
            Section("final", "Final", post.sortedWith(compareByDescending<GameFolder> { it.game.startMillis }.thenBy { it.game.away.displayName })),
        )
        return out.filter { it.games.isNotEmpty() }
    }
}

/** The 24/7 tile: channels that are always on. */
object AlwaysOn {
    /** Always-on items (started more than 24 h ago) of ALL saved lists, tagged or not; same address once; saved-list order. */
    fun collect(lists: List<Pair<SavedList, List<ListItem>>>, now: Long): List<GameStream> {
        val seen = HashSet<String>()
        val out = ArrayList<GameStream>()
        for ((list, items) in lists) for (item in items) {
            val start = item.startMillis ?: continue
            if (!ListStatus.isAlwaysOn(start, now)) continue
            if (seen.add(GameFolders.urlKey(item.url))) out.add(GameStream(list.id, list.name, item))
        }
        return out
    }
}

/** Live games for the home screen. */
object LiveGames {
    /** Live games that have at least one stream: earliest start first, NFL, then college, then NBA only as a tiebreak. */
    fun sample(folders: List<SportFolders>, max: Int = 8): List<GameFolder> =
        folders.flatMap { it.games }
            .filter { it.game.state == "in" && it.streams.isNotEmpty() }
            .sortedWith(compareBy<GameFolder>({ it.game.startMillis }, { when (it.game.league) { "nfl" -> 0; "cfb" -> 1; else -> 2 } }))
            .take(max)

    /**
     * Addresses of the streams placed in any live game, both as written and as [GameFolders.urlKey], so the
     * home "Live now" row can skip them.
     */
    fun matchedUrls(folders: List<SportFolders>): Set<String> {
        val out = HashSet<String>()
        for (f in folders) for (g in f.games) if (g.game.state == "in") for (s in g.streams) {
            out.add(s.item.url); out.add(GameFolders.urlKey(s.item.url))
        }
        return out
    }
}
