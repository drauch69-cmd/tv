package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class TimingTest {
    @Test fun fullLineInOrder() {
        val marks = mapOf(
            "picture" to 12500L, "player" to 11500L, "checked" to 11200L, "checking" to 9900L,
            "seen" to 9800L, "tapped" to 4000L, "loaded" to 3100L, "browser" to 400L,
        )
        assertEquals(
            "Timing: browser ready 0.4 s (took 0.3 s) · page loaded 3.1 s · tapped 4.0 s · stream seen 9.8 s · " +
                "checking 9.9 s · checked 11.2 s · player 11.5 s · picture 12.5 s · path: full search",
            Timing.format(marks, 300L, false, "full search"),
        )
    }

    @Test fun omitsMarksNeverSet() {
        val line = Timing.format(mapOf("player" to 500L, "picture" to 1500L), null, false, "head start")
        assertEquals("Timing: player 0.5 s · picture 1.5 s · path: head start", line)
    }

    @Test fun sitePlayerEnding() {
        val line = Timing.format(mapOf("browser" to 200L, "loaded" to 2000L), 100L, false, "site player")
        assertEquals("Timing: browser ready 0.2 s (took 0.1 s) · page loaded 2.0 s · path: site player", line)
    }

    @Test fun firstBrowserNote() {
        val line = Timing.format(mapOf("browser" to 1200L), 1100L, true, "full search")
        assertEquals("Timing: browser ready 1.2 s (took 1.1 s, first browser since the app opened) · path: full search", line)
    }

    @Test fun oneDecimalAndRounding() {
        assertEquals("Timing: player 1.2 s · path: x", Timing.format(mapOf("player" to 1234L), null, false, "x"))
        assertEquals("Timing: player 0.0 s · path: x", Timing.format(mapOf("player" to 4L), null, false, "x"))
    }

    @Test fun marksKeepOnlyFirstAndEmitOnce() {
        var now = 1000L
        Timing.clock = { now }
        Timing.start()
        now = 1500L; Timing.mark("loaded")
        now = 2500L; Timing.mark("loaded")
        now = 3000L; Timing.mark("picture")
        Timing.path = "remembered address"
        Timing.emit()
        // after the line is out, later marks and emits change nothing
        Timing.mark("player")
        Timing.emit("site player")
        Timing.clock = { System.currentTimeMillis() }
        val lines = Report.text().lines().filter { it.contains("Timing:") }
        assertEquals(1, lines.size)
        assertEquals(true, lines[0].endsWith("Timing: page loaded 0.5 s · picture 2.0 s · path: remembered address"))
    }
}
