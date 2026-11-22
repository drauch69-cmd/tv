package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveWindowTest {
    @Test fun smoothOnFourShortPiecesMovesInsideTheList() {
        // netanyahu: 4 pieces of 3 s; smooth (20 s) was clamped to the first piece (12 s behind).
        assertEquals(7_500L, LiveWindow.correctedOffsetMs(12_000, 3_000, 12_000, 20_000))
    }

    @Test fun smoothOnThreeFourSecondPieces() {
        // chatgpt relay: 3 pieces of 4 s -> 12 s list, too short for both margins: the middle (6 s behind).
        assertEquals(6_000L, LiveWindow.correctedOffsetMs(12_000, 4_000, 12_000, 20_000))
    }

    @Test fun longListKeepsTwentySeconds() {
        // 10 pieces of 6 s: 20 s behind is safe, nothing to do.
        assertNull(LiveWindow.correctedOffsetMs(60_000, 6_000, 20_000, 20_000))
    }

    @Test fun lowDelayDefaultIsLeftAlone() {
        // 4 pieces of 5 s: Media3's default 3 pieces back (15 s) leaves half a piece; moves to 12.5 s.
        assertEquals(12_500L, LiveWindow.correctedOffsetMs(20_000, 5_000, 15_000, 15_000))
        // ...but 6 pieces of 5 s with 15 s behind is fine.
        assertNull(LiveWindow.correctedOffsetMs(30_000, 5_000, 15_000, 15_000))
    }

    @Test fun nonsenseIsIgnored() {
        assertNull(LiveWindow.correctedOffsetMs(0, 3_000, 10_000, 20_000))
        assertNull(LiveWindow.correctedOffsetMs(12_000, 0, 10_000, 20_000))
    }

    @Test fun rememberedShortListSetsTheDelayUpFront() {
        LiveWindow.clearMemory()
        val url = "https://a.example/hls/mono.ts.m3u8?t=1"
        assertNull(LiveWindow.offsetFor(url, true))
        LiveWindow.remember(url, 16_000, 5_000)
        assertEquals(8_000L, LiveWindow.offsetFor(url, true))   // 16 - 7.5 = 8.5 < 10 (2 pieces): the middle
        assertEquals(8_000L, LiveWindow.offsetFor(url, false))
        LiveWindow.remember(url, 60_000, 6_000)
        assertEquals(20_000L, LiveWindow.offsetFor(url, true))  // long list: smooth keeps 20 s
        assertEquals(18_000L, LiveWindow.offsetFor(url, false)) // low delay: 3 pieces
        LiveWindow.clearMemory()
    }
}
