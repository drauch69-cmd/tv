package com.streamlink.tv

/**
 * Lets ESPN decide the status of a list item that matches a game. Pure (games are passed in), unit tested.
 * An item with no matching game gets exactly the start-time status of [ListStatus.byTime].
 */
object ScoreStatus {
    /** [item] carries the effective start (ESPN's kickoff when matched); [badge] is a finished label ("Final", "Postponed", "Canceled") or null. */
    class Resolved(val item: ListItem, val status: String, val badge: String?)

    /** "Postponed" / "Canceled" when ESPN says the game will not be played as scheduled, else null. */
    fun cancelLabel(g: Game): String? {
        when (g.statusName.uppercase()) {
            "STATUS_POSTPONED" -> return "Postponed"
            "STATUS_CANCELED", "STATUS_CANCELLED" -> return "Canceled"
        }
        if (g.state != "in") {
            val d = g.shortDetail.lowercase()
            if (d.contains("postponed")) return "Postponed"
            if (d.contains("canceled") || d.contains("cancelled")) return "Canceled"
        }
        return null
    }

    /** The game this item is about, if any (cached per games list). */
    fun gameFor(item: ListItem, games: List<Game>): Game? =
        if (games.isEmpty()) null
        else try { ScoreMatch.find(ListText.displayTitle(item), item.startMillis, games) } catch (_: Exception) { null }

    fun resolve(item: ListItem, now: Long, games: List<Game> = ScoreFeed.latest()): Resolved {
        val g = gameFor(item, games) ?: return Resolved(item, ListStatus.byTime(item, now), null)
        val moved = if (item.startMillis == g.startMillis) item else item.copy(startMillis = g.startMillis)
        cancelLabel(g)?.let { return Resolved(moved, ListStatus.FINISHED, it) }
        return when (g.state) {
            "in" -> Resolved(moved, ListStatus.LIVE, null)
            "post" -> Resolved(moved, ListStatus.FINISHED, "Final")
            else -> {
                // Before kickoff ESPN knows better than the site's "live" marker: kickoff time decides (Soon/Later).
                Resolved(moved, ListStatus.byTime(moved.copy(liveHint = false), now), null)
            }
        }
    }
}
