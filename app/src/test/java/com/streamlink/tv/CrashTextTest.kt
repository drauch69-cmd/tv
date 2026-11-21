package com.streamlink.tv

import org.junit.Assert.*
import org.junit.Test

class CrashTextTest {
    private fun st(vararg cls: String) = cls.mapIndexed { i, c -> StackTraceElement(c, "m", "F.kt", i + 1) }.toTypedArray()

    @Test fun recordHasHeaderAndFrames() {
        val e = IllegalStateException("boom\nline2")
        e.stackTrace = st("a.A", "com.streamlink.tv.Foo")
        val r = CrashText.record(1234L, "main", e)
        val lines = r.lines()
        assertEquals("T 1234", lines[0])
        assertEquals("H main", lines[1])
        assertEquals("E java.lang.IllegalStateException: boom line2", lines[2])
        assertEquals("  at a.A.m(F.kt:1)", lines[3])
        assertEquals(1234L, CrashText.recordMillis(r))
    }

    @Test fun appFramesKeptBeyondHead() {
        val classes = (0 until 60).map { if (it == 50) "com.streamlink.tv.Deep" else "x.X$it" }
        val e = RuntimeException("m")
        e.stackTrace = st(*classes.toTypedArray())
        val r = CrashText.record(1L, "t", e)
        assertTrue(r.contains("com.streamlink.tv.Deep.m(F.kt:51)"))
        assertFalse(r.contains("x.X40."))
        assertTrue(r.contains("  … 25 more"))
        assertTrue(r.contains("  … 9 more"))
    }

    @Test fun causeChainIncluded() {
        val c = IllegalArgumentException("inner")
        c.stackTrace = st("b.B")
        val e = RuntimeException("outer", c)
        e.stackTrace = st("a.A")
        val r = CrashText.record(1L, "t", e)
        assertTrue(r.contains("C java.lang.IllegalArgumentException: inner"))
        val out = CrashText.crashLines(r) { "TIME" }
        assertEquals("Crash: java.lang.RuntimeException: outer", out[0])
        assertEquals("Crash thread: t, at TIME", out[1])
        assertEquals("  at a.A.m(F.kt:1)", out[2])
        assertEquals("Caused by: java.lang.IllegalArgumentException: inner", out[3])
        assertEquals("  at b.B.m(F.kt:1)", out[4])
    }

    @Test fun noMessageUsesClassName() {
        val r = CrashText.record(1L, "t", NullPointerException())
        assertTrue(r.contains("E java.lang.NullPointerException\n"))
    }

    @Test fun reasonAndImportanceNames() {
        assertEquals("crash", CrashText.reasonName(4))
        assertEquals("native crash", CrashText.reasonName(5))
        assertEquals("ANR (app not responding)", CrashText.reasonName(6))
        assertEquals("low memory", CrashText.reasonName(3))
        assertEquals("unknown (99)", CrashText.reasonName(99))
        assertEquals("in the foreground", CrashText.importanceName(100))
        assertEquals("cached in the background", CrashText.importanceName(400))
    }

    @Test fun exitLine() {
        assertEquals("Last exit: low memory at 2026-11-18 10:00:00, was cached in the background (swap)",
            CrashText.exitLine(3, 400, "2026-11-18 10:00:00", "swap", 0))
        assertEquals("Last exit: killed by a signal at T, was in the foreground, status 9",
            CrashText.exitLine(2, 100, "T", null, 9))
    }

    @Test fun reportOnce() {
        assertTrue(CrashText.shouldReport(10, 5))
        assertFalse(CrashText.shouldReport(5, 5))
    }

    @Test fun anrMainThread() {
        val trace = "----- pid 1 -----\n\"Signal Catcher\" daemon prio=5\n  at x.Y.z(Y.java:1)\n\n\"main\" prio=5 tid=1 Blocked\n  | group=\"main\"\n  at a.B.c(B.java:2)\n  - waiting to lock <0x1>\n  at d.E.f(E.java:3)\n\n\"other\" prio=5\n  at no.No.no(N.java:1)\n"
        assertEquals(listOf("  at a.B.c(B.java:2)", "  - waiting to lock <0x1>", "  at d.E.f(E.java:3)"), CrashText.mainThreadStack(trace))
        assertEquals(1, CrashText.mainThreadStack(trace, 1).size)
        assertTrue(CrashText.mainThreadStack("nothing").isEmpty())
    }

    @Test fun memoryText() {
        assertTrue(CrashText.memoryText(40).startsWith("App moved to the background"))
        assertTrue(CrashText.memoryText(15).contains("low while the app is open"))
        assertTrue(CrashText.memoryText(80).contains("very low"))
        assertTrue(CrashText.memoryText(7).contains("level 7"))
    }

    @Test fun memoryLine() {
        assertEquals(
            "Memory (playing): app uses 310 MB (Java 40 of 256 MB); box has 420 of 1950 MB free; Android says memory is low",
            MemText.line("playing", 310, 40, 256, 420, 1950, true),
        )
        assertFalse(MemText.line("x", 1, 1, 1, 1, 1, false).contains("Android says"))
    }

    @Test fun memoryThrottle() {
        val m = HashMap<Int, Long>()
        assertFalse(CrashText.shouldLogMemory(m, 5, 0))
        assertFalse(CrashText.shouldLogMemory(m, 20, 0))
        assertTrue(CrashText.shouldLogMemory(m, 10, 1000))
        assertFalse(CrashText.shouldLogMemory(m, 10, 30_000))
        assertTrue(CrashText.shouldLogMemory(m, 15, 30_000))
        assertTrue(CrashText.shouldLogMemory(m, 10, 61_001))
    }
}
