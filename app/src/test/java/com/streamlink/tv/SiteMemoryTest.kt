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
