package com.streamlink.tv

// STUB — replaced by list-reader at merge. Same API as /home/claude/team/shared/LIST_API.md.

/** One event read off an index page. */
data class ListItem(
    val title: String,
    val subtitle: String,
    val url: String,
    val startMillis: Long?,
    val timeText: String,
    val section: String,
    val liveHint: Boolean,
    val imageUrl: String?,
)

data class ListResult(
    val pageUrl: String,
    val pageTitle: String,
    val items: List<ListItem>,
    val method: String,
    val error: String?,
)

/** Live/upcoming status from an item and the current time. Pure. */
object ListStatus {
    const val LIVE = "live"; const val SOON = "soon"; const val UPCOMING = "upcoming"
    const val FINISHED = "finished"; const val UNKNOWN = "unknown"
    private const val FOUR_HOURS = 4 * 60 * 60 * 1000L
    private const val HALF_HOUR = 30 * 60 * 1000L

    fun of(item: ListItem, nowMillis: Long): String {
        val s = item.startMillis
        if (item.liveHint) return LIVE
        if (s == null) return UNKNOWN
        return when {
            s <= nowMillis && nowMillis - s < FOUR_HOURS -> LIVE
            s <= nowMillis -> FINISHED
            s - nowMillis <= HALF_HOUR -> SOON
            else -> UPCOMING
        }
    }

    fun sorted(items: List<ListItem>, nowMillis: Long): List<ListItem> {
        fun rank(i: ListItem) = when (of(i, nowMillis)) { LIVE -> 0; SOON -> 1; UPCOMING -> 2; UNKNOWN -> 3; else -> 4 }
        return items.sortedWith(compareBy<ListItem>({ rank(it) }, { it.startMillis ?: Long.MAX_VALUE }))
    }
}
