package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastStartTest {
    private val H = 60 * 60 * 1000L
    private val D = 24 * H

    private fun src(url: String, open: Boolean = false, verified: Boolean = true) =
        Source(url, mapOf("User-Agent" to "ua"), listOf("https://p.example.com/", ""), "label", verified = verified, openPage = open, mime = "m")

    // ---- page address ----

    @Test
    fun normalizeLowersHostTrimsSlashAndClickFragment() {
        assertEquals("https://example.com/Live/Game", LastAddress.normalize("  HTTPS://Example.COM/Live/Game/ "))
        assertEquals("https://example.com/a?x=1", LastAddress.normalize("https://example.com/a/?x=1".replace("/?", "?")))
        assertEquals("https://example.com", LastAddress.normalize("https://example.com/"))
        assertEquals("https://example.com/list", LastAddress.normalize("https://Example.com/list#sl-click=%7B%22t%22%3A%22x%22%7D"))
        assertNull(LastAddress.normalize("not a url"))
        assertNull(LastAddress.normalize("ftp://x.com/a"))
        assertNull(LastAddress.normalize(null))
    }

    @Test
    fun keysToSaveSkipsClickOnlyAndVideoFiles() {
        val s = src("https://cdn.example.com/a.m3u8")
        val click = "https://e.com/list#sl-click=%7B%22t%22%3A%22Game%22%2C%22i%22%3A1%7D"
        assertTrue(LastAddress.keysToSave(click, "https://e.com/list", s).isEmpty())
        assertTrue(LastAddress.keysToSave("https://cdn.example.com/v.mp4", "https://cdn.example.com/v.mp4", s).isEmpty())
        assertTrue(LastAddress.keysToSave("https://cdn.example.com/a.m3u8", "https://cdn.example.com/a.m3u8", s).isEmpty())
        assertTrue(LastAddress.keysToSave("https://e.com/p", "https://e.com/p", src("x", open = true)).isEmpty())
        assertTrue(LastAddress.keysToSave("https://e.com/p", "https://e.com/p", src("x", verified = false)).isEmpty())
    }

    @Test
    fun keysToSaveUsesRequestedAndFinalPage() {
        val s = src("https://cdn.example.com/a.m3u8")
        assertEquals(listOf("https://e.com/p"), LastAddress.keysToSave("https://E.com/p/", "https://e.com/p", s))
        assertEquals(listOf("https://e.com/p", "https://f.com/q"), LastAddress.keysToSave("https://e.com/p", "https://f.com/q", s))
        assertEquals(listOf("https://f.com/q"), LastAddress.keysToSave("", "https://f.com/q", s))
    }

    // ---- stored set ----

    @Test
    fun playedSourceGoesFirstAndIsVerified() {
        val a = src("https://a/1.m3u8"); val b = src("https://b/2.m3u8"); val c = src("https://c/3.m3u8", verified = false)
        val out = LastAddress.orderForStore(listOf(a, b, c), c)
        assertEquals(listOf("https://c/3.m3u8", "https://a/1.m3u8", "https://b/2.m3u8"), out.map { it.url })
        assertTrue(out[0].verified)
    }

    @Test
    fun storedSetKeepsOpenPageEntriesAndCapsAtEight() {
        val playables = (1..6).map { src("https://h/$it.m3u8") }
        val opens = (1..4).map { src("https://page/$it", open = true) }
        val out = LastAddress.orderForStore(playables + opens, playables[2])
        assertEquals(8, out.size)
        assertEquals("https://h/3.m3u8", out[0].url)
        assertEquals(4, out.count { it.openPage })
        assertEquals(4, out.count { !it.openPage })
    }

    @Test
    fun storedSetHasNoDuplicates() {
        val a = src("https://a/1.m3u8")
        assertEquals(1, LastAddress.orderForStore(listOf(a, a, a), a).size)
    }

    @Test
    fun entryJsonRoundTrips() {
        val e = LastAddress.Entry("https://e.com/p", LastAddress.orderForStore(listOf(src("https://a/1.m3u8"), src("https://p/2", open = true)), src("https://a/1.m3u8")), 1234L)
        val back = LastAddress.fromJson(LastAddress.toJson(mapOf("k" to e)))
        assertEquals(e, back["k"])
        assertTrue(LastAddress.fromJson("garbage{").isEmpty())
        assertTrue(LastAddress.fromJson(null).isEmpty())
    }

    // ---- LRU and expiry ----

    @Test
    fun lastAddressKeepsAtMost50NewestPages() {
        var map: Map<String, LastAddress.Entry> = emptyMap()
        for (i in 1..60) map = LastAddress.withEntry(map, "k$i", LastAddress.Entry("p", listOf(src("u")), 1000L + i), 1000L + i)
        assertEquals(50, map.size)
        assertFalse("k10" in map)
        assertTrue("k60" in map)
        // saving an old key again makes it the newest
        map = LastAddress.withEntry(map, "k11", LastAddress.Entry("p", listOf(src("u")), 2000L), 2000L)
        assertEquals("k11", map.keys.last())
    }

    @Test
    fun lastAddressExpiresAfter24Hours() {
        val e = LastAddress.Entry("p", listOf(src("u")), 0L)
        assertTrue(LastAddress.fresh(e, 23 * H))
        assertFalse(LastAddress.fresh(e, D))
        assertFalse(LastAddress.fresh(null, 0))
        val map = LastAddress.withEntry(mapOf("old" to e), "new", LastAddress.Entry("p", listOf(src("u")), D + 5), D + 5)
        assertFalse("old" in map)
    }

    @Test
    fun serverMemoryKeepsAtMost100HostsAndExpiresAfter7Days() {
        var map: Map<String, ServerMemory.Entry> = emptyMap()
        for (i in 1..120) map = ServerMemory.withReferer(map, "h$i", "https://r/$i", 1000L + i)
        assertEquals(100, map.size)
        assertFalse("h1" in map)
        assertTrue("h120" in map)
        val e = ServerMemory.Entry("https://r/", 0L, "chrome", 0L)
        assertEquals("https://r/", ServerMemory.referer(e, 7 * D - 1))
        assertNull(ServerMemory.referer(e, 7 * D))
        assertEquals("chrome", ServerMemory.engine(e, 7 * D - 1))
        assertNull(ServerMemory.engine(e, 7 * D))
        assertTrue(ServerMemory.withReferer(mapOf("old" to e), "x", "https://r", 8 * D).keys.none { it == "old" })
    }

    @Test
    fun serverKeyStripsWww() {
        assertEquals("cdn.example.com", ServerMemory.key(" WWW.CDN.Example.com "))
        assertNull(ServerMemory.key(""))
        assertNull(ServerMemory.key(null))
    }

    @Test
    fun forgetEngineKeepsReferer() {
        var map: Map<String, ServerMemory.Entry> = ServerMemory.withReferer(emptyMap(), "h", "https://r/", 10L)
        map = ServerMemory.withEngine(map, "h", "standard", 20L)
        map = ServerMemory.withoutEngine(map, "h", 30L)
        assertNull(ServerMemory.engine(map["h"], 40L))
        assertEquals("https://r/", ServerMemory.referer(map["h"], 40L))
        map = ServerMemory.withoutEngine(ServerMemory.withEngine(emptyMap(), "g", "chrome", 10L), "g", 20L)
        assertFalse("g" in map)
    }

    @Test
    fun serverJsonRoundTrips() {
        val map = ServerMemory.withEngine(ServerMemory.withReferer(emptyMap(), "h", "https://r/", 10L), "h", "chrome", 20L)
        assertEquals(map, ServerMemory.fromJson(ServerMemory.toJson(map)))
        assertTrue(ServerMemory.fromJson("nope").isEmpty())
    }

    // ---- referer order ----

    @Test
    fun rememberedRefererGoesFirstEvenIfNotListed() {
        val list = listOf("https://a/", "https://b/", "")
        assertEquals(listOf("https://z/", "https://a/", "https://b/", ""), ServerMemory.refererOrder(list, "https://z/"))
    }

    @Test
    fun rememberedRefererIsNotRepeated() {
        val list = listOf("https://a/", "https://b/", "https://c/")
        assertEquals(listOf("https://b/", "https://a/", "https://c/", ""), ServerMemory.refererOrder(list, "https://b/"))
    }

    @Test
    fun refererOrderWithoutMemoryIsAsBefore() {
        val list = (1..7).map { "https://r$it/" } + ""
        assertEquals(list.take(5) + "", ServerMemory.refererOrder(list, null))
        assertEquals(list.take(5) + "", ServerMemory.refererOrder(list, ""))
        assertEquals(listOf(""), ServerMemory.refererOrder(emptyList(), null))
    }

    @Test
    fun refererOrderCapsRealOnesAtFive() {
        val list = (1..7).map { "https://r$it/" }
        val out = ServerMemory.refererOrder(list, "https://z/")
        assertEquals(6, out.size) // 5 real + none
        assertEquals("https://z/", out[0])
        assertEquals("", out.last())
    }

    // ---- engine ----

    @Test
    fun engineDecisions() {
        val standard = ServerMemory.engineStart("standard", true)
        assertFalse(standard.useChromeNet); assertFalse(standard.chromeForced); assertNotNull(standard.note)
        val chrome = ServerMemory.engineStart("chrome", true)
        assertTrue(chrome.useChromeNet); assertTrue(chrome.chromeForced); assertNotNull(chrome.note)
        val chromeGone = ServerMemory.engineStart("chrome", false)
        assertTrue(chromeGone.useChromeNet); assertFalse(chromeGone.chromeForced); assertNull(chromeGone.note)
        for (m in listOf(null, "", "other")) {
            val d = ServerMemory.engineStart(m, true)
            assertTrue(d.useChromeNet); assertFalse(d.chromeForced); assertNull(d.note)
        }
    }

    @Test
    fun restoreKeepsPlayedFirstAndTheRestAsStored() {
        val stored = LastAddress.orderForStore(listOf(src("https://a/1.m3u8"), src("https://p/2", open = true)), src("https://a/1.m3u8"))
        val e = LastAddress.Entry("https://e.com/p", stored, 1L)
        val set = LastAddress.restore(e, src("https://a/1.m3u8", verified = false))
        assertTrue(set.sources[0].verified)
        assertEquals(listOf("https://a/1.m3u8", "https://p/2"), set.sources.map { it.url })
        assertTrue(set.sources[1].openPage)
        assertEquals("https://e.com/p", set.pageUrl)
    }
}
