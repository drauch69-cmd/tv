package com.streamlink.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BehindLiveTest {
    @Test fun secondFallWithinTwoMinutesMovesOn() {
        val s = PlaybackSigns()
        assertFalse(s.behindLiveRepeated(0))
        assertTrue(s.behindLiveRepeated(12_000))
        assertFalse(s.behindLiveRepeated(13_000)) // counted afresh after moving on
    }

    @Test fun fallsFarApartDoNotCount() {
        val s = PlaybackSigns()
        assertFalse(s.behindLiveRepeated(0))
        assertFalse(s.behindLiveRepeated(PlaybackSigns.BEHIND_WINDOW_MS + 1))
        s.resetBehind()
        assertFalse(s.behindLiveRepeated(PlaybackSigns.BEHIND_WINDOW_MS + 2))
    }
}
