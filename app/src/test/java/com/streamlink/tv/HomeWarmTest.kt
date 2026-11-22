package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeWarmTest {
    @Test fun onlyRealPlaybackCounts() {
        assertTrue(HomeWarm.playing("player"))
        assertTrue(HomeWarm.playing("clean"))
        assertTrue(HomeWarm.playing("site"))
        assertFalse(HomeWarm.playing("finding"))
        assertFalse(HomeWarm.playing("waiting"))
        assertFalse(HomeWarm.playing("idle"))
        assertFalse(HomeWarm.playing("list"))
        assertFalse(HomeWarm.playing(null))
    }

    @Test fun nothingRightAfterLeavingHome() {
        assertEquals(HomeWarm.Due.NONE, HomeWarm.due("player", 1_000L, 1_000L, 1_000L))
        assertEquals(HomeWarm.Due.NONE, HomeWarm.due("player", 59_999L, 0L, 0L))
    }

    @Test fun scoresEveryMinuteForcedEveryFive() {
        assertEquals(HomeWarm.Due.SCORES, HomeWarm.due("player", 60_000L, 0L, 0L))
        assertEquals(HomeWarm.Due.SCORES_AND_FORCE, HomeWarm.due("player", 300_000L, 240_000L, 0L))
    }

    @Test fun notWhileFindingOrOnOtherScreens() {
        assertEquals(HomeWarm.Due.NONE, HomeWarm.due("finding", 999_999L, 0L, 0L))
        assertEquals(HomeWarm.Due.NONE, HomeWarm.due("list", 999_999L, 0L, 0L))
    }
}
