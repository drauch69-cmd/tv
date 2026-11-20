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
     * - Only lists whose tags include [league] count. [games] may hold every league; this filters itself.
     * - Games in the window (today to the end of today + 2, in [zone]) plus any game still live (state "in").
     * - An item is matched with [ScoreMatch.find] on its title, else on title + subtitle, against the games of ALL
     *   the list's tagged leagues together (one best game wins, so an item never lands in two sports). If the
     *   winner belongs to [league]: that game's streams; if it belongs to another league: skipped here (it is
     *   placed when that league is built); no match: [SportFolders.other] (so an unmatched item of a list tagged
     *   with two sports shows in both sports' Other).
     * - Unmatched items whose title is not two-sided (channels, shows; see [ChannelFilter.isMatchup]) are dropped, so
     *   Other holds only game-like titles (and they are not marked as seen, so another list's copy is still judged).
     * - Always-on channels are never placed anywhere; finished items are placed only when they match a game
     *   (never in other).
     * - The same address (see [urlKey]) is placed once: first list in saved order, first item in page order.
     * - [listOrder]: list ids best first ([Ranking.order]); lists are handled in that order, so ranked lists' streams
     *   come first in every game and in other (and win a duplicate address). Null: saved order.
     */
    fun build(
        league: String, games: List<Game>, lists: List<Pair<SavedList, List<ListItem>>>, now: Long, zone: ZoneId,
        listOrder: List<String>? = null,
    ): SportFolders {
        val (from, to) = window(now, zone)
        val windowAll = GameViews.window(games, from, to)
        val inWindow = windowAll.filter { it.league == league }
        val perGame = LinkedHashMap<String, MutableList<GameStream>>()
        for (g in inWindow) perGame[g.id] = ArrayList()
        val other = ArrayList<GameStream>()
        val seen = HashSet<String>()
        for ((list, items) in Ranking.sort(lists, listOrder) { it.first.id }) {
            if (!list.has(league)) continue
            val pool = GameViews.forLeagues(windowAll, list.sports)
            for (item in items) {
                val start = item.startMillis
                if (start != null && ListStatus.isAlwaysOn(start, now)) continue
                val finished = ListStatus.of(item, now) == ListStatus.FINISHED
                val game = ScoreMatch.find(item.title, start, pool)
                    ?: if (item.subtitle.isNotBlank()) ScoreMatch.find(item.title + " " + item.subtitle, start, pool) else null
                if (game != null && game.league != league) continue // belongs to another of the list's sports
                if (game == null && (finished || !ChannelFilter.isMatchup(item.title))) continue
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
    /** Always-on items (started more than 24 h ago) of ALL saved lists, tagged or not; same address once; lists in [listOrder] (best first; null = saved order). */
    fun collect(lists: List<Pair<SavedList, List<ListItem>>>, now: Long, listOrder: List<String>? = null): List<GameStream> {
        val seen = HashSet<String>()
        val out = ArrayList<GameStream>()
        for ((list, items) in Ranking.sort(lists, listOrder) { it.first.id }) for (item in items) {
            val start = item.startMillis ?: continue
            if (!ListStatus.isAlwaysOn(start, now)) continue
            if (seen.add(GameFolders.urlKey(item.url))) out.add(GameStream(list.id, list.name, item))
        }
        return out
    }
}

/** Live games for the home screen. */
object LiveGames {
    /**
     * Live games that have at least one stream, by sport (user, 2026-11-19): NFL, college football, NBA, then college
     * basketball; within a sport, earliest start first.
     */
    fun sample(folders: List<SportFolders>, max: Int = 8): List<GameFolder> =
        folders.flatMap { it.games }
            .filter { it.game.state == "in" && it.streams.isNotEmpty() }
            .sortedWith(compareBy<GameFolder>({ leaguePriority(it.game.league) }, { it.game.startMillis }))
            .take(max)

    /** Sport order for live games: NFL, college football, NBA, college basketball, anything else. */
    fun leaguePriority(league: String): Int = when (league) { "nfl" -> 0; "cfb" -> 1; "nba" -> 2; "cbb" -> 3; else -> 4 }

    /** NFL games live right now (any streams or not). RedZone only airs when several are on at once. */
    fun nflLiveCount(games: List<Game>): Int = games.count { it.league == "nfl" && it.state == "in" }

    /**
     * Addresses of the streams placed in any live game, both as written and as [GameFolders.urlKey], so the
     * phone and tests can tell placed streams apart.
     */
    fun matchedUrls(folders: List<SportFolders>): Set<String> {
        val out = HashSet<String>()
        for (f in folders) for (g in f.games) if (g.game.state == "in") for (s in g.streams) {
            out.add(s.item.url); out.add(GameFolders.urlKey(s.item.url))
        }
        return out
    }
}

/**
 * Stable filtered views of a games list. A new List instance for every call would make ScoreMatch's per-list cache
 * useless, so the same source instance with the same filter gives the same result instance. A few sources are kept
 * (LRU), so screens and the home calculation working on different lists do not evict each other. Thread-safe.
 */
object GameViews {
    private class IdKey(val list: List<Game>) {
        override fun hashCode() = System.identityHashCode(list)
        override fun equals(other: Any?) = other is IdKey && other.list === list
    }
    private const val MAX_SOURCES = 6
    private val lock = Any()
    private val views = object : LinkedHashMap<IdKey, HashMap<String, List<Game>>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<IdKey, HashMap<String, List<Game>>>?) = size > MAX_SOURCES
    }

    private fun memo(src: List<Game>, key: String, make: () -> List<Game>): List<Game> {
        synchronized(lock) { views[IdKey(src)]?.get(key)?.let { return it } }
        val v = make()
        synchronized(lock) { return views.getOrPut(IdKey(src)) { HashMap() }.getOrPut(key) { v } }
    }

    /** Games of [leagues] (empty set = all, the source itself), in source order. */
    fun forLeagues(src: List<Game>, leagues: Set<String>): List<Game> {
        if (leagues.isEmpty()) return src
        return memo(src, "l:" + leagues.sorted().joinToString(",")) { src.filter { it.league in leagues } }
    }

    /** Games live now or starting in [from] until [to] (exclusive). */
    fun window(src: List<Game>, from: Long, to: Long): List<Game> =
        memo(src, "w:$from:$to") { src.filter { it.state == "in" || it.startMillis in from until to } }
}
