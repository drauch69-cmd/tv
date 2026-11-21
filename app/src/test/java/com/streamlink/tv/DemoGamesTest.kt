package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DemoGamesTest {
    // Midday, so every demo game falls on "today" in the home window.
    private val now = ZonedDateTime.of(2026, 11, 22, 12, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()

    @Test fun demoGamesHaveCitiesAndShowOnHomeWithAStream() {
        val games = DemoGames.games(now)
        assertTrue(games.all { CityPhotoRules.placeKey(it) != null })
        assertTrue(games.count { it.state == "in" } >= 4)
        val list = DemoGames.list()
        val m = HomeModel.build(listOf(list), mapOf(DemoGames.LIST_ID to DemoGames.cached(games, now)), games, now, ZoneId.of("UTC"))
        val withStreams = m.folders.flatMap { it.games }.filter { it.streams.isNotEmpty() }.map { it.game.id }.toSet()
        // Every demo game is matched to its made-up stream, so it shows in the home rows like a real one.
        assertEquals(games.map { it.id }.toSet(), withStreams)
    }
}
