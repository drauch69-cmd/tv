package com.streamlink.tv

import org.junit.Assert.*
import org.junit.Test

class RefusalTest {
    @Test
    fun refusalIsNotATimeoutOrGone() {
        assertTrue(Refusal.isRefusal(401)); assertTrue(Refusal.isRefusal(403)); assertTrue(Refusal.isRefusal(451))
        assertFalse(Refusal.isRefusal(404)); assertFalse(Refusal.isRefusal(410)); assertFalse(Refusal.isRefusal(500))
        assertFalse(Refusal.isRefusal(0)); assertFalse(Refusal.isRefusal(null))
    }

    @Test
    fun allRefusedNeedsAnAnswerEveryTime() {
        assertTrue(Refusal.allRefused(listOf(403, 403, 403)))
        assertTrue(Refusal.allRefused(listOf(401, 403)))
        assertFalse(Refusal.allRefused(listOf(403, 0, 403))) // one try timed out: not a clear refusal
        assertFalse(Refusal.allRefused(listOf(403, 200)))
        assertFalse(Refusal.allRefused(listOf(404, 404)))
        assertFalse(Refusal.allRefused(emptyList()))
    }

    @Test
    fun noMoreRefererCyclingWhenTheHostIsKnownRefused() {
        assertTrue(Refusal.cycleReferers(hasMoreReferers = true, pieceHostKnownRefused = false))
        assertFalse(Refusal.cycleReferers(hasMoreReferers = true, pieceHostKnownRefused = true))
        assertFalse(Refusal.cycleReferers(hasMoreReferers = false, pieceHostKnownRefused = false))
    }

    @Test
    fun afterRefusalOrder() {
        assertEquals(Refusal.Step.NEXT_SOURCE, Refusal.afterRefusal(true, true, true, 0))
        assertEquals(Refusal.Step.AUTO_SWITCH, Refusal.afterRefusal(false, true, true, 0))
        assertEquals(Refusal.Step.RELOAD_ONCE, Refusal.afterRefusal(false, false, false, 0))
        // corroborated by the finder: no reload at all
        assertEquals(Refusal.Step.SITE_PLAYER, Refusal.afterRefusal(false, false, true, 0))
        // the one reload was spent
        assertEquals(Refusal.Step.SITE_PLAYER, Refusal.afterRefusal(false, false, false, 1))
    }

    @Test
    fun cleanPlayerSkippedForRefusedPieces() {
        assertFalse(Refusal.cleanPlayerCanHelp(true))
        assertTrue(Refusal.cleanPlayerCanHelp(false))
    }

    @Test
    fun sameRefusedStreamIgnoresTheQuery() {
        assertTrue(Refusal.sameRefusedStream("https://h/a/p?t=1", listOf("https://h/a/p?t=2")))
        assertFalse(Refusal.sameRefusedStream("https://h/a/p?t=1", listOf("https://h/a/p?t=2", "https://h/other")))
        assertFalse(Refusal.sameRefusedStream(null, listOf("https://h/a/p")))
        assertFalse(Refusal.sameRefusedStream("https://h/a/p", emptyList()))
    }

    @Test
    fun refusedHostsExpire() {
        RefusedHosts.clear()
        RefusedHosts.record("WWW.Cdn.Example.com", 1000L)
        assertTrue(RefusedHosts.isRefused("cdn.example.com", 2000L))
        assertFalse(RefusedHosts.isRefused("other.example.com", 2000L))
        assertFalse(RefusedHosts.isRefused("cdn.example.com", 1000L + RefusedHosts.TTL_MS + 1))
        assertFalse(RefusedHosts.isRefused(null, 2000L))
        RefusedHosts.clear()
    }

    @Test
    fun pieceWatchSeparatesRefusalFromTimeout() {
        val w = PieceWatch()
        w.pieceFailed("cdn", 10L, 0)
        assertNull(w.pieceRefusedHost()); assertFalse(w.pieceAnswered("cdn"))
        w.pieceFailed("cdn", 11L, 403)
        assertEquals("cdn", w.pieceRefusedHost()); assertTrue(w.pieceRefused("cdn")); assertTrue(w.pieceAnswered("cdn"))
        val v = PieceWatch()
        v.pieceFailed("cdn", 10L, 404)
        assertNull(v.pieceRefusedHost()); assertTrue(v.pieceAnswered("cdn"))
        val ok = PieceWatch()
        ok.pieceOk("cdn"); ok.pieceFailed("cdn", 10L, 403)
        assertNull(ok.pieceRefusedHost()) // some pieces came through: not a refusal of the host
    }

    @Test
    fun refererMemoryOnlyForPageAndPlayerFrame() {
        val trusted = listOf("crackstreams.mx", "yieldz.gs")
        assertTrue(RefererTrust.eligible("https://crackstreams.mx/stream/x", trusted))
        assertTrue(RefererTrust.eligible("https://www.yieldz.gs/", trusted))
        assertTrue(RefererTrust.eligible("https://embed.crackstreams.mx/p", trusted))
        assertFalse(RefererTrust.eligible("https://bcp.crwdcntrl.net/5/pixels", trusted))
        assertFalse(RefererTrust.eligible("https://st.chatango.com/it.html", trusted))
        assertFalse(RefererTrust.eligible("not a url", trusted))
        assertFalse(RefererTrust.eligible("https://a.com/", listOf("", " ")))
        assertEquals(
            listOf("https://yieldz.gs/", "https://crackstreams.mx/x"),
            RefererTrust.eligibleReferers(listOf("https://bcp.crwdcntrl.net/p", "https://yieldz.gs/", "https://crackstreams.mx/x", ""), trusted),
        )
    }
}
