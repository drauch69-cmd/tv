package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatWatchTest {
    private val a = "https://host-a.example/abc6/application5"

    @Test
    fun firesOnSecondRequestAtSixSeconds() {
        val w = RepeatWatch()
        assertFalse(w.seen(a, 0))
        assertTrue(w.seen(a, 6000))
    }

    @Test
    fun notTooSoon() {
        val w = RepeatWatch()
        assertFalse(w.seen(a, 0))
        assertFalse(w.seen(a, 500))
        assertTrue(w.seen(a, 6000))
    }

    @Test
    fun notAfterTwentyFiveSeconds() {
        val w = RepeatWatch()
        assertFalse(w.seen(a, 0))
        assertFalse(w.seen(a, 25000))
    }

    @Test
    fun firesOnlyOnce() {
        val w = RepeatWatch()
        w.seen(a, 0)
        assertTrue(w.seen(a, 6000))
        assertFalse(w.seen(a, 12000))
        assertFalse(w.seen(a, 18000))
    }

    @Test
    fun ignoresNonPlaylistAddresses() {
        val w = RepeatWatch()
        for (u in listOf(
            "https://h/p/logo.png", "https://h/p/app.js", "https://h/p/site.css", "https://h/p/index.m3u8",
            "https://h/p/seg1.ts", "https://h/", "https://h", "ftp://h/p/x",
        )) {
            assertFalse(u, w.seen(u, 0))
            assertFalse(u, w.seen(u, 6000))
        }
    }

    @Test
    fun differentQueriesAreDifferentAddresses() {
        val w = RepeatWatch()
        assertFalse(w.seen("$a?t=1", 0))
        assertFalse(w.seen("$a?t=2", 6000))
        assertTrue(w.seen("$a?t=1", 7000))
    }

    @Test
    fun cappedAtThreeHundredAddresses() {
        val w = RepeatWatch()
        for (i in 0 until 301) w.seen("https://h/p/x$i", 0)
        // the oldest was dropped, so its repeat counts as a first sighting
        assertFalse(w.seen("https://h/p/x0", 6000))
        assertTrue(w.seen("https://h/p/x300", 6000))
    }

    @Test
    fun resetForgetsEverything() {
        val w = RepeatWatch()
        w.seen(a, 0)
        w.reset()
        assertFalse(w.seen(a, 6000))
        assertEquals(true, w.seen(a, 12000))
    }
}
