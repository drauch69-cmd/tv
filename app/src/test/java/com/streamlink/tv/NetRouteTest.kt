package com.streamlink.tv

import org.junit.Assert.*
import org.junit.Test

class NetRouteTest {
    private val D = 24 * 60 * 60 * 1000L

    @Test
    fun otherHostGetsOnlyBrowserCrossSiteHeaders() {
        val all = mapOf(
            "Cookie" to "a=b", "Authorization" to "x", "Referer" to "https://p/", "Origin" to "https://p",
            "Accept-Language" to "en", "X-Token" to "t", "Sec-Fetch-Site" to "same-origin", "Sec-Fetch-Mode" to "same-origin",
            "sec-ch-ua" to "\"c\"", "Accept" to "x/y",
        )
        val out = NetHeaders.forOtherHost(all)
        assertFalse(out.keys.any { it.equals("Cookie", true) || it.equals("Authorization", true) || it == "X-Token" || it == "Accept" })
        assertEquals("https://p/", out["Referer"]); assertEquals("https://p", out["Origin"])
        assertEquals("cross-site", out["Sec-Fetch-Site"]); assertEquals("cors", out["Sec-Fetch-Mode"])
        assertTrue(NetHeaders.forOtherHost(mapOf("Cookie" to "a")).isEmpty())
    }

    @Test
    fun playlistVersusPiece() {
        val src = "https://h/a/index.m3u8?t=1"
        assertTrue(NetHeaders.isPlaylist(src, src))
        assertTrue(NetHeaders.isPlaylist("https://h/a/mono.ts.m3u8?x=1", src))
        assertTrue(NetHeaders.isPlaylist("https://h/m.mpd", src))
        assertFalse(NetHeaders.isPlaylist("https://cdn/p19/~tplv-ti", src))
        assertFalse(NetHeaders.isPlaylist("https://h/seg1.ts", src))
    }

    @Test
    fun routeEngines() {
        val r = NetRoute("https://h/x.m3u8", "h") { it == "refuser" }
        r.playlistChrome = true
        assertTrue(r.usesChrome("h")); assertTrue(r.usesChrome("cdn"))
        r.setPieceEngine("cdn", false)
        assertFalse(r.usesChrome("cdn")); assertTrue(r.usesChrome("h"))
        r.setPieceEngine("refuser", false)
        assertTrue(r.usesChrome("refuser"))
        r.setPieceEngine("h", false) // the playlist host is never overridden
        assertTrue(r.usesChrome("h"))
        r.playlistChrome = false
        assertFalse(r.usesChrome("h"))
    }

    @Test
    fun pieceOnlyNeedsPlaylistOkDuringTheFailure() {
        val w = PieceWatch()
        assertNull(w.pieceOnlyHost(1))
        w.playlistOk(100)
        w.pieceFailed("cdn", 1000)
        assertNull(w.pieceOnlyHost(1)) // playlist last ok before the piece began
        w.playlistOk(5000)
        assertEquals("cdn", w.pieceOnlyHost(1))
        assertNull(w.pieceOnlyHost(2))
        w.playlistFailed(6000)
        assertNull(w.pieceOnlyHost(1))
        w.playlistOk(7000)
        assertEquals("cdn", w.pieceOnlyHost(1))
        w.pieceOk("cdn")
        assertNull(w.pieceOnlyHost(1))
    }

    @Test
    fun retryAndRestartForgetFailures() {
        val w = PieceWatch()
        w.playlistOk(10); w.pieceFailed("c", 1); w.playlistOk(20)
        assertEquals("c", w.pieceOnlyHost(1))
        w.markRestarted("c")
        assertNull(w.pieceOnlyHost(1)); assertTrue(w.restarted("c")); assertFalse(w.retried("c"))
        w.pieceFailed("c", 30); w.playlistOk(40)
        w.markRetried("c")
        assertNull(w.pieceOnlyHost(1)); assertTrue(w.retried("c"))
    }

    @Test
    fun progressAndDiagBudget() {
        val w = PieceWatch()
        assertFalse(w.progressing(1000))
        w.pieceBytes(1000, 500)
        assertTrue(w.progressing(1000 + PieceWatch.PROGRESS_MS))
        assertFalse(w.progressing(1001 + PieceWatch.PROGRESS_MS))
        assertEquals(500, w.bytesSoFar())
        repeat(PieceWatch.MAX_DIAG) { assertTrue(w.takeDiag()) }
        assertFalse(w.takeDiag())
    }

    @Test
    fun recoveryOrder() {
        assertEquals(PieceStep.RETRY_OTHER_ENGINE, PieceRecovery.next(false, true))
        assertEquals(PieceStep.SWITCH_SOURCE, PieceRecovery.next(true, true))
        assertEquals(PieceStep.SWITCH_SOURCE, PieceRecovery.next(false, false))
    }

    @Test
    fun refusesStandardMemory() {
        var m: Map<String, ServerMemory.Entry> = ServerMemory.withRefusesStandard(emptyMap(), "h", 10L)
        assertTrue(ServerMemory.refusal(m["h"], 10L + 7 * D - 1))
        assertFalse(ServerMemory.refusal(m["h"], 10L + 7 * D))
        m = ServerMemory.withEngine(m, "h", "chrome", 20L)
        m = ServerMemory.withoutEngine(m, "h", 30L)
        assertTrue(ServerMemory.refusal(m["h"], 40L)) // forgetting the engine keeps the refusal
        assertEquals(m, ServerMemory.fromJson(ServerMemory.toJson(m)))
        assertFalse(ServerMemory.withReferer(m, "x", "https://r", 20L + 8 * D).containsKey("h"))
    }
}
