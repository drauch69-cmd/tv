package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteMemoryTest {
    private val now = 1_800_000_000_000L

    @Test fun shortensAfterTwoMissesInARow() {
        var e: SiteMemory.Entry? = null
        e = SiteMemory.afterResult(e, found = false, now = now)
        assertFalse(SiteMemory.shorten(e, now))
        e = SiteMemory.afterResult(e, found = false, now = now + 1000)
        assertTrue(SiteMemory.shorten(e, now + 2000))
    }

    @Test fun everyFourthMissGetsAFullSearch() {
        assertTrue(SiteMemory.shorten(SiteMemory.Entry(3, now), now))
        assertFalse(SiteMemory.shorten(SiteMemory.Entry(4, now), now))
        assertTrue(SiteMemory.shorten(SiteMemory.Entry(5, now), now))
        assertTrue(SiteMemory.shorten(SiteMemory.Entry(25, now), now))
        assertFalse(SiteMemory.shorten(SiteMemory.Entry(28, now), now))
    }

    @Test fun oneSuccessClears() {
        val e = SiteMemory.Entry(5, now)
        assertNull(SiteMemory.afterResult(e, found = true, now = now))
    }

    @Test fun expires() {
        val e = SiteMemory.Entry(3, now)
        assertFalse(SiteMemory.shorten(e, now + SiteMemory.EXPIRE_MS + 1))
        assertEquals(1, SiteMemory.afterResult(e, false, now + SiteMemory.EXPIRE_MS + 1)!!.fails)
    }

    @Test fun keys() {
        assertEquals("example.com", SiteMemory.key(" WWW.Example.com"))
        assertNull(SiteMemory.key(""))
    }
}

class SiteMemoryOutcomeTest {
    @Test fun missOnlyWhenNothingWasSeen() {
        assertEquals(false, SiteMemory.outcome(false, false, found = false, candidateSeen = false, offline = false))
        assertNull(SiteMemory.outcome(false, false, found = false, candidateSeen = true, offline = false))
        assertNull(SiteMemory.outcome(false, false, found = false, candidateSeen = false, offline = true))
    }
    @Test fun foundClearsButNotFromLightOrReconnect() {
        assertEquals(true, SiteMemory.outcome(false, false, found = true, candidateSeen = true, offline = false))
        assertNull(SiteMemory.outcome(true, false, found = false, candidateSeen = false, offline = false))
        assertNull(SiteMemory.outcome(false, true, found = false, candidateSeen = false, offline = false))
        assertNull(SiteMemory.outcome(false, true, found = true, candidateSeen = true, offline = false))
    }
}
