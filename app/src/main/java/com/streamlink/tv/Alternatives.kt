package com.streamlink.tv

import android.content.Context

/**
 * Other sites' streams for the game being watched, from the sports folders: the Source panel offers them as
 * "open page" entries. The matching is pure (JVM-testable); [forPage] only loads the cached data.
 */
object Alternatives {
    const val MAX = 6

    // TODO lead: replace with SavedLists.preferredId(ctx) when the preferred-list branch is merged.
    private fun preferredListId(ctx: Context): String? = null

    /**
     * The other streams of the game whose folder holds the playing page, best first: the preferred list's streams,
     * then the folder's own order. Excludes the playing page itself (by [GameFolders.urlKey] of [pageKeys]),
     * finished items ([isFinished]) and repeats; at most [max]. Empty when no folder holds the page.
     */
    fun pick(
        folders: List<SportFolders>, pageKeys: Collection<String?>, preferredId: String?,
        max: Int = MAX, isFinished: (ListItem) -> Boolean = { false },
    ): List<GameStream> {
        val keys = pageKeys.filterNotNull().filter { it.isNotBlank() }.map { GameFolders.urlKey(it) }.toSet()
        if (keys.isEmpty()) return emptyList()
        val folder = folders.asSequence().flatMap { it.games.asSequence() }
            .firstOrNull { g -> g.streams.any { GameFolders.urlKey(it.item.url) in keys } } ?: return emptyList()
        val seen = HashSet<String>(keys)
        val others = folder.streams.filter { s ->
            val k = GameFolders.urlKey(s.item.url)
            (s.item.url.startsWith("http://") || s.item.url.startsWith("https://")) && !isFinished(s.item) && seen.add(k)
        }
        val ordered = if (preferredId == null) others else others.filter { it.listId == preferredId } + others.filter { it.listId != preferredId }
        return ordered.take(max)
    }

    /** "open page" entries for [streams]: another site, so no referer is sent; labelled "<list name> · other site". */
    fun toSources(streams: List<GameStream>): List<Source> {
        val labels = LinkOrder.uniqueLabels(streams.map { (it.listName.trim().ifEmpty { Report.host(it.item.url) }) + " · other site" })
        return streams.mapIndexed { i, s ->
            Source(s.item.url, emptyMap(), emptyList(), labels[i], isDefaultLink = false, verified = false,
                note = "opens as its own page", playerUrl = s.item.url, openPage = true)
        }
    }

    /** [extra] entries appended to [existing], skipping addresses already listed (by [GameFolders.urlKey]); [existing] keeps its order and indexes. */
    fun append(existing: List<Source>, extra: List<Source>): List<Source> {
        val seen = existing.map { GameFolders.urlKey(it.url) }.toHashSet()
        return existing + extra.filter { seen.add(GameFolders.urlKey(it.url)) }
    }

    /** Reads caches: call off the main thread. Any failure gives an empty list. */
    fun forPage(ctx: Context, pageUrl: String, listItemUrl: String?): List<Source> = try {
        val now = System.currentTimeMillis()
        val games = ScoreFeed.latest()
        toSources(pick(SportsData.both(ctx, now), listOf(listItemUrl, pageUrl), preferredListId(ctx),
            isFinished = { ListStatus.of(it, now, games) == ListStatus.FINISHED }))
    } catch (_: Exception) {
        emptyList()
    }
}
