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

class CheckVerdictTest {
    @Test fun twoGoneAnswersAreEnough() {
        assertFalse(DeadStream.enough(listOf(404)))
        assertFalse(DeadStream.enough(listOf(404, 0)))
        assertTrue(DeadStream.enough(listOf(404, 410)))
    }
    @Test fun cutOffAfterAGoneAnswerIsOffline() {
        assertEquals("server said 404 (not found)", DeadStream.cutOffNote(listOf(404)))
        assertEquals("server said 404 (not found)", DeadStream.cutOffNote(listOf(0, 404)))
        assertEquals(NoAnswer.TOO_SLOW, DeadStream.cutOffNote(emptyList()))
        assertEquals(NoAnswer.TOO_SLOW, DeadStream.cutOffNote(listOf(0)))
        assertEquals(NoAnswer.TOO_SLOW, DeadStream.cutOffNote(listOf(404, 403)))
    }
    @Test fun refusedSourceIsRecognised() {
        val s = Source("u", emptyMap(), emptyList(), "l", false, verified = false, note = "server said 403; tried: a" + Refusal.REFUSED_MARK)
        assertTrue(Refusal.isRefusedSource(s))
        assertFalse(Refusal.isRefusedSource(s.copy(note = "server said 403; tried: a")))
        assertFalse(Refusal.isRefusedSource(s.copy(verified = true)))
    }
    @Test fun statusOfPrefersRefusal() {
        assertEquals(403, Refusal.statusOf(listOf(404, 403, 0)))
        assertEquals(404, Refusal.statusOf(listOf(0, 404)))
        assertEquals(0, Refusal.statusOf(listOf(0)))
    }
    @Test fun lifetimeSampleOnlyForPassOrRefusal() {
        assertEquals(true, ServerMemory.lifetimeSample(true, 200))
        assertEquals(false, ServerMemory.lifetimeSample(false, 403))
        assertEquals(false, ServerMemory.lifetimeSample(false, 401))
        assertNull(ServerMemory.lifetimeSample(false, 0))
        assertNull(ServerMemory.lifetimeSample(false, 404))
        assertNull(ServerMemory.lifetimeSample(false, 200))
    }

    @Test fun pageAndPlayerReferersGoBeforeTrackerFrames() {
        val refs = listOf(
            "https://bcp.tracker.example/x/pixels", "https://tags.tracker.example/y",
            "https://site.example/stream/abc", "https://player.example/embed/1",
        )
        assertEquals(
            listOf("https://site.example/stream/abc", "https://player.example/embed/1", "https://bcp.tracker.example/x/pixels", "https://tags.tracker.example/y"),
            RefererTrust.trustedFirst(refs, listOf("site.example", "player.example", "")),
        )
        // the page referer and its bare origin now survive the 5-try cap
        val capped = Headers.withOriginForms(RefererTrust.trustedFirst(refs, listOf("site.example")), 5)
        assertEquals(listOf("https://site.example/stream/abc", "https://site.example/"), capped.take(2))
        // nothing trusted: order unchanged
        assertEquals(refs, RefererTrust.trustedFirst(refs, listOf("")))
    }
}
