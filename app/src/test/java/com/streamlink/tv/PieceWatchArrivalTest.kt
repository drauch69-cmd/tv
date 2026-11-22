package com.streamlink.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PieceWatchArrivalTest {
    @Test fun progressingWithACustomWindow() {
        val w = PieceWatch()
        assertFalse(w.progressing(1_000))           // nothing yet
        w.pieceBytes(10_000, 500)
        assertTrue(w.progressing(17_000))            // default 8 s window
        assertFalse(w.progressing(17_000, 4_000))    // but not within 4 s
        assertFalse(w.progressing(19_000))
    }

    @Test fun playlistAnsweringNeedsARecentSuccessAndNoLaterFailure() {
        val w = PieceWatch()
        assertFalse(w.playlistAnswering(5_000))
        w.playlistOk(5_000)
        assertTrue(w.playlistAnswering(15_000))
        assertFalse(w.playlistAnswering(25_000))     // too long ago
        w.playlistOk(26_000)
        w.playlistFailed(27_000)
        assertFalse(w.playlistAnswering(28_000))
    }
}
