package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamUrlsTest {
    private fun classify(url: String) = StreamUrls.classify(url)

    @Test
    fun jwPlayerPingCarriesEmbeddedPlaylist() {
        val url = "https://prd.jwpltx.com/v1/abc123/ping.gif?e=1&mu=https%3A%2F%2Fhost%2Fpath%2Findex.m3u8&t=2"
        assertEquals(false to listOf("https://host/path/index.m3u8"), classify(url))
    }

    @Test
    fun plainPlaylistIsStream() {
        assertEquals(true to emptyList<String>(), classify("https://host/path/index.m3u8"))
        assertEquals(true to emptyList<String>(), classify("https://host/path/index.m3u8?token=abc"))
    }

    @Test
    fun m3u8InPathIsStream() {
        assertEquals(true to emptyList<String>(), classify("https://host/m3u8/live/chunk?x=1"))
    }

    @Test
    fun proxyLinkWithPlaylistNameIsStream() {
        assertEquals(true to emptyList<String>(), classify("https://host/play?file=chan.m3u8"))
        assertEquals(true to emptyList<String>(), classify("https://host/x?f=x.m3u8"))
    }

    @Test
    fun imageWithPlaylistNameInQueryIsNotStream() {
        assertEquals(false to emptyList<String>(), classify("https://host/thumb.png?src=x.m3u8"))
        assertEquals(false to emptyList<String>(), classify("https://host/m3u8/thumb.jpg"))
    }

    @Test
    fun scriptAndStyleAreNotStreams() {
        assertEquals(false to emptyList<String>(), classify("https://host/player.js?v=index.m3u8"))
        assertEquals(false to emptyList<String>(), classify("https://host/style.css?v=index.m3u8"))
    }

    @Test
    fun doubleEncodedEmbeddedPlaylistIsDecodedTwice() {
        val url = "https://track.example.com/p.gif?u=https%253A%252F%252Fhost%252Fa%252Fmaster.m3u8"
        assertEquals(false to listOf("https://host/a/master.m3u8"), classify(url))
    }

    @Test
    fun mpdPathIsStream() {
        assertEquals(true to emptyList<String>(), classify("https://cdn.example.com/live/manifest.mpd"))
        assertEquals(true to emptyList<String>(), classify("https://cdn.example.com/live/manifest.mpd?token=1"))
    }

    @Test
    fun embeddedMpdIsReturned() {
        val url = "https://ping.example.com/beacon.gif?mu=https%3A%2F%2Fcdn.example.com%2Fmanifest.mpd%3Fk%3Dv"
        assertEquals(false to listOf("https://cdn.example.com/manifest.mpd?k=v"), classify(url))
    }

    @Test
    fun nonHttpIsNeverStream() {
        assertEquals(false to emptyList<String>(), classify("file:///sdcard/index.m3u8"))
        assertEquals(false to emptyList<String>(), classify("blob:https://host/index.m3u8"))
        assertEquals(false to emptyList<String>(), classify("ftp://host/index.m3u8"))
    }

    @Test
    fun embeddedNonHttpIsIgnored() {
        val url = "https://ping.example.com/beacon.gif?mu=ftp%3A%2F%2Fhost%2Findex.m3u8"
        assertEquals(false to emptyList<String>(), classify(url))
    }

    @Test
    fun embeddedImageIsIgnored() {
        val url = "https://ping.example.com/beacon.gif?mu=https%3A%2F%2Fhost%2Fthumb.png%3Fv%3Dx.m3u8"
        assertEquals(false to emptyList<String>(), classify(url))
    }

    @Test
    fun literalPlusInEmbeddedUrlIsKept() {
        val url = "https://ping.example.com/beacon.gif?mu=https%3A%2F%2Fhost%2Fa%2Findex.m3u8%3Ftok%3Dab+cd"
        assertEquals(false to listOf("https://host/a/index.m3u8?tok=ab+cd"), classify(url))
    }
}
