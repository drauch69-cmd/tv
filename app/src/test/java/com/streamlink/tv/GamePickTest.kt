package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GamePickTest {
    private val now = 1_700_000_000_000L
    private val hour = 60 * 60_000L
    private fun side(a: String) = TeamSide(a, a, a, a, a, "0", "1-1")
    private fun stream(n: Int) = GameStream("l$n", "L$n", ListItem("t", "", "https://x/$n", null, "", "", false, null))
    private fun game(id: String, state: String, start: Long) =
        Game(id, "nfl", start, state, "", 0, "", side("H$id"), side("A$id"), "")

    @Test fun bestSkipsOutStreamsButFallsBackToFirst() {
        val s = listOf(stream(1), stream(2), stream(3))
        assertEquals("https://x/2", GamePick.best(s) { it.item.url.endsWith("/1") }?.item?.url)
        assertEquals("https://x/1", GamePick.best(s) { true }?.item?.url)
        assertNull(GamePick.best(emptyList()))
    }

    @Test fun nextUpIsTheSoonestUnstartedGameWithStreams() {
        val games = listOf(
            GameFolder(game("a", "pre", now + 3 * hour), listOf(stream(1))),
            GameFolder(game("b", "pre", now + hour), emptyList()),          // no streams
            GameFolder(game("c", "pre", now + 2 * hour), listOf(stream(2))),
            GameFolder(game("d", "in", now - hour), listOf(stream(3))),     // live: not "next up"
            GameFolder(game("e", "pre", now + 20 * hour), listOf(stream(4))), // too far off
        )
        assertEquals("c", GamePick.nextUp(games, now)?.game?.id)
        assertNull(GamePick.nextUp(games.filter { it.game.id in setOf("b", "d", "e") }, now))
    }
}
