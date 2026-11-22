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
    /** NFL RedZone, shown first in Live games while it airs; null otherwise. */
    val redZone: HomeFeed.Entry?,
    /** Tile counts per list id (only for lists that have been read). */
    val counts: Map<String, HomeFeed.Counts>,
    val taggedCounts: Map<String, Int>,
    val alwaysOnCount: Int,
    /** A summary of what is on screen; two models with the same look draw the same thing. */
    val look: String,
    /** Sports whose lists are still waiting for their first read since start (HomeFinding). */
    val finding: Set<String> = emptySet(),
) {
    companion object {
        fun build(
            lists: List<SavedList>, cachedById: Map<String, CachedList>, games: List<Game>, now: Long, zone: ZoneId,
            finding: Set<String> = emptySet(),
        ): HomeModel {
            val order = Ranking.order(lists)
            // Best-ranked list first: same game, same start time -> its event leads in the rows.
            val cached = Ranking.sort(lists, order) { it.id }.mapNotNull { l -> cachedById[l.id]?.let { l to it } }
            val pairs = lists.map { l -> l to (cachedById[l.id]?.result?.items ?: emptyList()) }
            val folders = Sports.ALL.map { lg ->
                try { GameFolders.build(lg, games, pairs, now, zone, order) } catch (_: Exception) { SportFolders(lg, emptyList(), emptyList()) }
            }
            val redZone = HomeFeed.redZone(cached.map { it.first to it.second.result.items }, now, games)
            val counts = LinkedHashMap<String, HomeFeed.Counts>()
            for ((l, c) in cached) counts[l.id] = HomeFeed.counts(c.result.items, now, games)
            val tagged = Sports.ALL.associateWith { lg -> lists.count { it.has(lg) } }
            val alwaysOn = AlwaysOn.collect(pairs, now, order).size

            val sb = StringBuilder()
            sb.append("r:").append(order.joinToString(",")).append('|').append(lists.joinToString(",") { it.rank?.toString() ?: "-" }).append('#')
            // The RedZone card and the tile counts...
            sb.append("rz:").append(redZone?.item?.url ?: "").append('|').append(redZone?.list?.name ?: "").append('#')
            for ((l, _) in cached) sb.append(HomeFeed.countsText(counts.getValue(l.id))).append(';')
            // ...plus what the sports rows show, so a score change redraws them.
            sb.append('#')
            sb.append("f:").append(finding.sorted().joinToString(",")).append('#')
            for (gf in LiveGames.sample(folders, 8, finding = finding)) sb.append(gf.game.id).append('|').append(ScoreText.rowLine(gf.game)).append('|').append(gf.streams.size).append(';')
            sb.append('#')
            for (f in folders) sb.append(SportsText.sportTileLine(tagged.getValue(f.league), f)).append(';')
            sb.append(SportsText.alwaysOnLine(alwaysOn)).append('#')
            return HomeModel(lists, cached, folders, redZone, counts, tagged, alwaysOn, sb.toString(), finding)
        }
    }
}
