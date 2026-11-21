package com.streamlink.tv

/**
 * Choosing a game plays its best stream straight away (user, 2026-11-20); the game's full stream list stays one
 * long-press away. Pure, so it runs on the JVM.
 */
object GamePick {
    /**
     * The stream to play: the first one (streams are already best-ranked list first) that is not [isOut]
     * (marked Offline, or dead from a recent failure); the first one anyway when all are out; null when there are none.
     */
    fun best(streams: List<GameStream>, isOut: (GameStream) -> Boolean = { false }): GameStream? =
        streams.firstOrNull { !isOut(it) } ?: streams.firstOrNull()

    /**
     * The sport screen's side card when no live game has a stream: the next game today that has streams and has not
     * started, within [withinMs] of [now]; null when there is none.
     */
    fun nextUp(games: List<GameFolder>, now: Long, withinMs: Long = 12 * 60 * 60_000L): GameFolder? =
        games.filter { it.game.state == "pre" && it.streams.isNotEmpty() && it.game.startMillis in now..(now + withinMs) }
            .minByOrNull { it.game.startMillis }
}
