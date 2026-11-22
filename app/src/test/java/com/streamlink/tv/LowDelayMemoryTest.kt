package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowDelayMemoryTest {
    private val now = 1_800_000_000_000L

    @Test fun markedHostWantsSmoothUntilItExpires() {
        val m = LowDelayMemory.marked(emptyMap(), "hereisman.net", now)
        assertTrue(LowDelayMemory.wants(m, "hereisman.net", now + 60_000))
        assertFalse(LowDelayMemory.wants(m, "hereisman.net", now + LowDelayMemory.KEEP_MS))
        assertFalse(LowDelayMemory.wants(m, "other.net", now))
        assertFalse(LowDelayMemory.wants(m, null, now))
    }

    @Test fun parseFormatRoundTripAndSkipsJunk() {
        val m = LowDelayMemory.parse("a.net=5;bad;=3;b.net=x;c.net=7")
        assertEquals(mapOf("a.net" to 5L, "c.net" to 7L), m)
        assertEquals(m, LowDelayMemory.parse(LowDelayMemory.format(m)))
        assertEquals(emptyMap<String, Long>(), LowDelayMemory.parse(null))
    }

    @Test fun keepsNewestAndDropsExpired() {
        var m: Map<String, Long> = mapOf("old.net" to now - LowDelayMemory.KEEP_MS - 1)
        for (i in 0 until LowDelayMemory.MAX_HOSTS + 5) m = LowDelayMemory.marked(m, "h$i.net", now + i)
        assertEquals(LowDelayMemory.MAX_HOSTS, m.size)
        assertFalse("old.net" in m)
        assertTrue("h${LowDelayMemory.MAX_HOSTS + 4}.net" in m)
    }

    @Test fun mainPlaylistIsAskedAgainOnlyForAnotherServerAndAtMostTwice() {
        assertTrue(BrokenTls.refetchMain("pl.playlist4.space", "chatgpt.hereisman.net", 0))
        assertTrue(BrokenTls.refetchMain("pl.playlist4.space", "chatgpt.hereisman.net", 1))
        assertFalse(BrokenTls.refetchMain("pl.playlist4.space", "chatgpt.hereisman.net", 2))
        assertFalse(BrokenTls.refetchMain("chatgpt.hereisman.net", "chatgpt.hereisman.net", 0))
        assertFalse(BrokenTls.refetchMain(null, "chatgpt.hereisman.net", 0))
    }

    @Test fun keyedPerStreamNotPerServer() {
        val a = LowDelayMemory.streamKey("https://chatgpt.hereisman.net/playlist/111/load-playlist?t=1")
        val b = LowDelayMemory.streamKey("https://chatgpt.hereisman.net/playlist/222/load-playlist?t=1")
        assertTrue(a != null && b != null && a != b)
        assertEquals(a, LowDelayMemory.streamKey("https://chatgpt.hereisman.net/playlist/111/load-playlist?t=2"))
        val m = LowDelayMemory.marked(emptyMap(), a!!, now)
        assertTrue(LowDelayMemory.wants(m, a, now))
        assertFalse(LowDelayMemory.wants(m, b, now))
        assertFalse(a.contains(';') || a.contains('='))
        assertEquals(null, LowDelayMemory.streamKey(null))
    }
}
