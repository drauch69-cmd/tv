package com.streamlink.tv

import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSyncTest {
    @Test fun shiftHoldsThePictureBackForLateSound() {
        assertEquals(9_850_000L, AudioSync.shift(10_000_000L, 150))
        assertEquals(10_100_000L, AudioSync.shift(10_000_000L, -100))
        assertEquals(AudioSink.CURRENT_POSITION_NOT_SET, AudioSync.shift(AudioSink.CURRENT_POSITION_NOT_SET, 150))
    }

    @Test fun clampAndSteps() {
        assertEquals(1000, AudioSync.clamp(1300))
        assertEquals(-1000, AudioSync.clamp(-5000))
        assertEquals(150, AudioSync.clamp(170))
    }

    @Test fun describe() {
        assertEquals("no adjustment", AudioSync.describe(0))
        assertEquals("picture waits 150 ms for the sound", AudioSync.describe(150))
        assertEquals("sound waits 100 ms for the picture", AudioSync.describe(-100))
    }
}
