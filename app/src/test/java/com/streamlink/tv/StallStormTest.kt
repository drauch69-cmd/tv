package com.streamlink.tv

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StallStormTest {
    @After fun clean() = AutoSwitch.resetAllForTest()

    @Test fun fourStallsInAMinuteIsAStorm() {
        val s = StallStorm()
        assertFalse(s.note(0)); assertFalse(s.note(10_000)); assertFalse(s.note(20_000))
        assertTrue(s.note(30_000))
        assertFalse(s.note(31_000)) // counting starts again
    }

    @Test fun spreadOutStallsAreNot() {
        val s = StallStorm()
        assertFalse(s.note(0)); assertFalse(s.note(30_000)); assertFalse(s.note(65_000)); assertFalse(s.note(100_000))
    }

    @Test fun aBadSourceStaysSkippedForTheGame() {
        val u = "https://yld.example/hls/game"
        AutoSwitch.markBad(u, 0)
        assertTrue(AutoSwitch.isDead(u, 2 * 60 * 60_000L))       // still skipped 2 h later (dead alone lasts 5 min)
        assertFalse(AutoSwitch.isDead(u, AutoSwitch.BAD_MS + 1))
    }
}
