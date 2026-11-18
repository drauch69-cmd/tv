package com.streamlink.tv

import java.time.ZoneId

/**
 * Everything the home screen draws that depends on the saved lists and the scores, worked out in one go from
 * immutable inputs (so it can run on a background thread). Pure (no Android), unit tested.
 */
class HomeModel(
    val lists: List<SavedList>,
    /** Lists that have been read, with what was read. */
    val cached: List<Pair<SavedList, CachedList>>,
    val folders: List<SportFolders>,
    /** Streams already shown as live game cards (the "Live now" row skips them). */
    val matched: Set<String>,
    val liveRows: List<HomeFeed.Entry>,
    /** Tile counts per list id (only for lists that have been read). */
    val counts: Map<String, HomeFeed.Counts>,
    val taggedCounts: Map<String, Int>,
    val alwaysOnCount: Int,
    /** A summary of what is on screen; two models with the same look draw the same thing. */
    val look: String,
) {
    companion object {
        fun build(
            lists: List<SavedList>, cachedById: Map<String, CachedList>, games: List<Game>, now: Long, zone: ZoneId,
        ): HomeModel {
            val cached = lists.mapNotNull { l -> cachedById[l.id]?.let { l to it } }
            val pairs = lists.map { l -> l to (cachedById[l.id]?.result?.items ?: emptyList()) }
            val folders = Sports.ALL.map { lg ->
                try { GameFolders.build(lg, games, pairs, now, zone) } catch (_: Exception) { SportFolders(lg, emptyList(), emptyList()) }
            }
            val matched = LiveGames.matchedUrls(folders)
            fun shown(i: ListItem) = i.url !in matched && GameFolders.urlKey(i.url) !in matched
            val liveRows = HomeFeed.liveRow(cached.map { it.first to it.second.result.items.filter { i -> shown(i) } }, now, games = games)
            val counts = LinkedHashMap<String, HomeFeed.Counts>()
            for ((l, c) in cached) counts[l.id] = HomeFeed.counts(c.result.items, now, games)
            val tagged = Sports.ALL.associateWith { lg -> lists.count { it.has(lg) } }
            val alwaysOn = AlwaysOn.collect(pairs, now).size

            val sb = StringBuilder()
            // Same as the old "home look": the unfiltered live row and the tile counts...
            for (e in HomeFeed.liveRow(cached.map { it.first to it.second.result.items }, now, games = games))
                sb.append(e.item.url).append('|').append(e.status).append('|').append(e.item.startMillis).append(';')
            for ((l, _) in cached) sb.append(HomeFeed.countsText(counts.getValue(l.id))).append(';')
            // ...plus what the sports rows show, so a score change redraws them.
            sb.append('#')
            for (gf in LiveGames.sample(folders, 8)) sb.append(gf.game.id).append('|').append(ScoreText.rowLine(gf.game)).append('|').append(gf.streams.size).append(';')
            sb.append('#')
            for (f in folders) sb.append(SportsText.sportTileLine(tagged.getValue(f.league), f)).append(';')
            sb.append(SportsText.alwaysOnLine(alwaysOn)).append('#')
            for (e in liveRows) sb.append(e.item.url).append('|').append(e.status).append(';')
            return HomeModel(lists, cached, folders, matched, liveRows, counts, tagged, alwaysOn, sb.toString())
        }
    }
}
