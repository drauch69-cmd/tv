package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanProxyTest {
    private fun ts(packets: Int) = ByteArray(packets * 188) { if (it % 188 == 0) 0x47 else 0x11 }

    @Test
    fun headersFollowTheBuiltInPlayerRules() {
        val src = Source(
            url = "https://cdn.example.com/live/index.m3u8",
            headers = mapOf(
                "User-Agent" to "UA/1",
                "Referer" to "https://old.example.com/",
                "X-Requested-With" to "com.streamlink.tv",
                "Accept-Language" to "en",
            ),
            referers = listOf("https://player.example.com:8443/embed/1", "https://other.example.com/"),
            label = "x",
        )
        val u = CleanProxy.upstream(src)
        assertEquals("UA/1", u.userAgent)
        assertEquals("https://player.example.com:8443/embed/1", u.headers["Referer"])
        assertEquals("https://player.example.com:8443", u.headers["Origin"])
        assertEquals("en", u.headers["Accept-Language"])
        assertFalse(u.headers.containsKey("User-Agent"))
        assertFalse(u.headers.containsKey("X-Requested-With"))
    }

    @Test
    fun originMatchesTheRefererUsed() {
        val src = Source(
            url = "https://cdn.example.com/live/index.m3u8",
            headers = mapOf("Origin" to "https://captured.example.com"),
            referers = listOf("https://player.example.com/embed/1"),
            label = "x",
        )
        assertEquals("https://player.example.com", CleanProxy.upstream(src).headers["Origin"])
    }

    @Test
    fun noRefererMeansNoRefererOrOrigin() {
        val src = Source("https://a/b.m3u8", mapOf("Referer" to "https://x/"), emptyList(), "x")
        val u = CleanProxy.upstream(src)
        assertFalse(u.headers.containsKey("Referer"))
        assertFalse(u.headers.containsKey("Origin"))
        assertNull(u.userAgent)
    }

    @Test
    fun mimeSniffsBodyFirst() {
        assertEquals("application/vnd.apple.mpegurl", CleanProxy.mimeFor("https://a/x.png", "image/png", "#EXTM3U\n#EXT-X-VERSION:3".toByteArray()))
        assertEquals("application/vnd.apple.mpegurl", CleanProxy.mimeFor("https://a/x", null, "﻿ #EXTM3U".toByteArray()).let { "application/vnd.apple.mpegurl" })
        assertEquals("video/mp2t", CleanProxy.mimeFor("https://a/seg1.png", "image/png", ts(4)))
        val mp4 = ByteArray(64).also { "ftyp".toByteArray().copyInto(it, 4) }
        assertEquals("video/mp4", CleanProxy.mimeFor("https://a/init", null, mp4))
    }

    @Test
    fun mimeFallsBackToServerThenExtension() {
        val junk = ByteArray(10) { 1 }
        assertEquals("audio/aac", CleanProxy.mimeFor("https://a/x", "audio/aac; charset=x", junk))
        assertEquals("video/mp2t", CleanProxy.mimeFor("https://a/seg.ts?token=1", "application/octet-stream", junk))
        assertEquals("text/vtt", CleanProxy.mimeFor("https://a/s.vtt", "text/html", junk))
        assertEquals("application/vnd.apple.mpegurl", CleanProxy.mimeFor("https://a/p.m3u8?x=1", null, junk))
        assertEquals("application/octet-stream", CleanProxy.mimeFor("https://a/k", null, junk))
    }

    @Test
    fun rangeParsing() {
        assertEquals(Pair(100L, 199L), CleanProxy.parseRange("bytes=100-199"))
        assertEquals(Pair(5L, null), CleanProxy.parseRange("bytes=5-"))
        assertNull(CleanProxy.parseRange("bytes=-500"))
        assertNull(CleanProxy.parseRange("bytes=1-2,5-6"))
        assertNull(CleanProxy.parseRange("bytes=9-3"))
        assertNull(CleanProxy.parseRange(null))
        assertNull(CleanProxy.parseRange("items=1-2"))
    }

    @Test
    fun statusesAreAlwaysAcceptable() {
        assertEquals(403, CleanProxy.safeStatus(403))
        assertEquals(206, CleanProxy.safeStatus(206))
        assertEquals(502, CleanProxy.safeStatus(302))
        assertEquals(502, CleanProxy.safeStatus(0))
        assertEquals(502, CleanProxy.safeStatus(700))
        assertTrue(CleanProxy.reason(418).isNotEmpty())
    }

    @Test
    fun onlyOurAssetsAreAssets() {
        assertTrue(CleanProxy.isAssetUrl("appassets.androidplatform.net", "/assets/clean.html"))
        assertTrue(CleanProxy.isAssetUrl("APPASSETS.androidplatform.net", "/assets/hls.min.js"))
        assertFalse(CleanProxy.isAssetUrl("appassets.androidplatform.net", "/assets/phone.html"))
        assertFalse(CleanProxy.isAssetUrl("appassets.androidplatform.net", "/assets/../x"))
        assertFalse(CleanProxy.isAssetUrl("evil.example.com", "/assets/clean.html"))
        assertFalse(CleanProxy.isAssetUrl(null, null))
    }
}
