package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickLogicTest {
    private val now = 1_800_000_000_000L
    private fun item(n: Int, start: Long? = now + n * 60_000L, live: Boolean = false) =
        ListItem("Event $n", "", "https://site.example/e/$n", start, "", "", live, null)
    private val full = (1..10).map { item(it) }

    @Test
    fun sameEventsMatch() {
        assertTrue(QuickLogic.matches(full, full))
        assertTrue(QuickLogic.matches(full, full.reversed()))
        assertTrue(QuickLogic.matches(full, full.drop(1))) // 9 of 10
    }

    @Test
    fun missingEventsTimesOrLiveMarksDoNotMatch() {
        assertFalse(QuickLogic.matches(full, full.drop(2)))
        assertFalse(QuickLogic.matches(full, full.map { it.copy(startMillis = null) })) // times filled in by scripts
        val liveFull = full.map { it.copy(liveHint = true) }
        assertFalse(QuickLogic.matches(liveFull, full)) // live marks drawn by scripts
        assertTrue(QuickLogic.matches(full, liveFull))
        assertFalse(QuickLogic.matches(full, emptyList()))
        assertFalse(QuickLogic.matches(emptyList(), full))
        assertFalse(QuickLogic.matches(full, full + (11..20).map { item(it) })) // lots of extra junk rows
    }

    @Test
    fun modeFollowsTheSavedVerdict() {
        assertEquals(QuickLogic.Mode.TEST, QuickLogic.mode(null, now))
        assertEquals(QuickLogic.Mode.QUICK, QuickLogic.mode(QuickLogic.Verdict(true, now - 60_000L), now))
        assertEquals(QuickLogic.Mode.TEST, QuickLogic.mode(QuickLogic.Verdict(true, now - QuickLogic.QUICK_RECHECK_MS), now))
        assertEquals(QuickLogic.Mode.FULL, QuickLogic.mode(QuickLogic.Verdict(false, now - 60_000L), now))
        assertEquals(QuickLogic.Mode.TEST, QuickLogic.mode(QuickLogic.Verdict(false, now - QuickLogic.BROWSER_RECHECK_MS), now))
    }

    @Test
    fun verdictRoundTrip() {
        val v = QuickLogic.Verdict(true, now)
        assertEquals(v, QuickLogic.decode(QuickLogic.encode(v)))
        assertEquals(QuickLogic.Verdict(false, 5L), QuickLogic.decode("b:5"))
        assertNull(QuickLogic.decode("x:5"))
        assertNull(QuickLogic.decode(null))
        assertNull(QuickLogic.decode("q:abc"))
    }

    @Test
    fun scriptsAndRefreshAreRemoved() {
        val html = """<html><head><meta http-equiv="refresh" content="0;url=/ad"><script src="a.js"></script>
            <SCRIPT type="text/javascript">var x = "</div>";</SCRIPT><link rel="stylesheet" href="s.css"></head>
            <body><a class="card" href="/e/1">Game</a><script>ads()</script></body></html>"""
        val out = QuickLogic.stripScripts(html)
        assertFalse(out.contains("script", ignoreCase = true))
        assertFalse(out.contains("refresh"))
        assertTrue(out.contains("""<a class="card" href="/e/1">Game</a>"""))
        assertTrue(out.contains("s.css"))
    }
}
