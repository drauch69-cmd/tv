package com.streamlink.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreReturnTest {
    private val now = 10_000_000_000L

    @Test
    fun oldScoresOnReturnShowTheLoadingScreen() {
        assertTrue(ScoreFeed.staleOnReturn(now - 4L * 24 * 60 * 60_000, now, hasLists = true)) // days away
        assertTrue(ScoreFeed.staleOnReturn(now - ScoreFeed.MAX_AGE_MS - 1, now, hasLists = true))
        assertTrue(ScoreFeed.staleOnReturn(null, now, hasLists = true)) // never fetched
    }

    @Test
    fun freshScoresOrNoListsDoNot() {
        assertFalse(ScoreFeed.staleOnReturn(now - 60_000, now, hasLists = true)) // back from a watch
        assertFalse(ScoreFeed.staleOnReturn(now - ScoreFeed.MAX_AGE_MS, now, hasLists = true))
        assertFalse(ScoreFeed.staleOnReturn(null, now, hasLists = false)) // first run: no loading screen
    }
}
