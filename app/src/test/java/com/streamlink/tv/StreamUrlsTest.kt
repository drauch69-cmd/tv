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

    @Test
    fun sameStreamIgnoresQueryAndFragment() {
        val a = "https://edge.example.top/premium45/index.m3u8"
        assertEquals(true, StreamUrls.sameStream(a, "$a?t=123.abc&_=99"))
        assertEquals(true, StreamUrls.sameStream("$a?t=1", "$a?t=2#x"))
        assertEquals(true, StreamUrls.sameStream("HTTPS://Edge.Example.top/premium45/index.m3u8", a))
    }

    @Test
    fun sameStreamNeedsSameSchemeHostAndPath() {
        val a = "https://edge.example.top/premium45/index.m3u8"
        assertEquals(false, StreamUrls.sameStream(a, "https://edge.example.top/premium46/index.m3u8"))
        assertEquals(false, StreamUrls.sameStream(a, "https://other.example.top/premium45/index.m3u8"))
        assertEquals(false, StreamUrls.sameStream(a, "http://edge.example.top/premium45/index.m3u8"))
        assertEquals(false, StreamUrls.sameStream(a, "not a url"))
        assertEquals(false, StreamUrls.sameStream("x", "x"))
    }

    @Test
    fun hasQueryOnlyWhenSomethingFollowsTheQuestionMark() {
        assertEquals(true, StreamUrls.hasQuery("https://h/p.m3u8?t=1"))
        assertEquals(false, StreamUrls.hasQuery("https://h/p.m3u8"))
        assertEquals(false, StreamUrls.hasQuery("https://h/p.m3u8?"))
        assertEquals(false, StreamUrls.hasQuery("https://h/p.m3u8#a?b"))
    }

    private fun possible(url: String) = StreamUrls.isPossiblePlaylist(url)

    @Test
    fun disguisedPlaylistAddressesArePossible() {
        assertEquals(true, possible("https://cdn.example.net/playlist/60325/load-playlist"))
        assertEquals(true, possible("https://cdn.example.net/hls/master.txt?t=abc"))
        assertEquals(true, possible("https://cdn.example.net/live/index.txt"))
        assertEquals(true, possible("https://cdn.example.net/live/playlist.php?id=3"))
        assertEquals(true, possible("https://cdn.example.net/api/getManifest"))
        assertEquals(true, possible("https://cdn.example.net/x/loadPlaylist"))
        assertEquals(true, possible("https://cdn.example.net/CHUNKLIST"))
        assertEquals(true, possible("https://cdn.example.net/live/index"))
        assertEquals(true, possible("https://cdn.example.net/list2?_reload=1&_=2"))
        assertEquals(true, possible("https://cdn.example.net/hls/index1.txt"))
        assertEquals(true, possible("https://cdn.example.net/list"))
    }

    @Test
    fun listWordsMustBeWholeWords() {
        assertEquals(false, possible("https://cdn.example.net/listing/3"))
        assertEquals(false, possible("https://cdn.example.net/wishlist"))
        assertEquals(false, possible("https://cdn.example.net/lists/7"))
        assertEquals(false, possible("https://cdn.example.net/list2/cover.png"))
    }

    @Test
    fun realStreamsAndOtherFilesAreNotPossible() {
        assertEquals(false, possible("https://cdn.example.net/a/playlist.m3u8")) // already a stream
        assertEquals(false, possible("https://cdn.example.net/a/manifest.mpd"))
        assertEquals(false, possible("https://cdn.example.net/playlist/cover.png"))
        assertEquals(false, possible("https://cdn.example.net/playlist/app.js"))
        assertEquals(false, possible("https://cdn.example.net/playlist/site.css"))
        assertEquals(false, possible("https://cdn.example.net/master/seg1.ts"))
        assertEquals(false, possible("https://cdn.example.net/index/seg1.m4s"))
        assertEquals(false, possible("https://cdn.example.net/playlist/clip.mp4"))
        assertEquals(false, possible("https://cdn.example.net/fonts/index.woff2"))
        assertEquals(false, possible("https://cdn.example.net/playlist/data.json"))
        assertEquals(false, possible("https://cdn.example.net/playlist/index.html"))
    }

    @Test
    fun wordsMustMatchWholeWords() {
        assertEquals(false, possible("https://site.example.net/playlists/user/12"))
        assertEquals(false, possible("https://site.example.net/reindex"))
        assertEquals(false, possible("https://site.example.net/indexed/items"))
        assertEquals(false, possible("https://site.example.net/mastery"))
        assertEquals(false, possible("https://site.example.net/wp-content/themes/x/index.php"))
        assertEquals(false, possible("https://site.example.net/"))
        assertEquals(false, possible("https://site.example.net"))
        assertEquals(false, possible("https://site.example.net/watch/12345"))
        assertEquals(false, possible("ftp://site.example.net/playlist"))
    }

    @Test
    fun wordsInTheQueryDoNotCount() {
        assertEquals(false, possible("https://site.example.net/api/data?kind=playlist"))
    }
}
