package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportHistoryTest {
    @Test fun keepsTheNewestPlaysOnly() {
        var h = ""
        for (i in 1..5) h = ReportHistory.append(h, "play $i", "t$i", maxPlays = 3)
        assertEquals(3, ReportHistory.count(h))
        assertFalse(h.contains("play 2"))
        assertTrue(h.contains("play 3") && h.contains("play 5"))
        assertTrue(h.indexOf("play 3") < h.indexOf("play 5"))
    }

    @Test fun blankPlaysAreSkippedAndSizeIsCapped() {
        assertEquals("", ReportHistory.append("", "  \n", "t"))
        var h = ""
        for (i in 1..4) h = ReportHistory.append(h, "x".repeat(100) + i, "t$i", maxBytes = 300)
        assertTrue(h.length <= 300)
        assertTrue(h.contains("x4"))
    }

    @Test fun downloadHasHeaderHistoryAndCurrent() {
        val h = ReportHistory.append("", "old play", "t1")
        val d = ReportHistory.download(h, "now playing", "t2")
        assertTrue(d.startsWith("StreamLink logs, downloaded t2: 1 saved play"))
        assertTrue(d.indexOf("old play") < d.indexOf("now playing"))
    }
}
