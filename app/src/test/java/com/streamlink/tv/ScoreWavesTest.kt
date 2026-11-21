package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class ScoreWavesTest {
    @Test
    fun collegeBasketballIsFetchedLast() {
        assertEquals(listOf(listOf("nfl", "cfb", "nba"), listOf("cbb")), ScoreFeed.todayWaves(listOf("nfl", "cfb", "nba", "cbb")))
        assertEquals(listOf(listOf("cbb")), ScoreFeed.todayWaves(listOf("cbb")))
        assertEquals(listOf(listOf("nba")), ScoreFeed.todayWaves(listOf("nba")))
        assertEquals(emptyList<List<String>>(), ScoreFeed.todayWaves(emptyList()))
    }
}
