package com.streamlink.tv

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class SampleRecorderTest {
    @Before @After
    fun clean() = SampleRecorder.reset()

    private fun entries(zip: ByteArray): Map<String, ByteArray> {
        val m = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                m[e.name] = z.readBytes()
            }
        }
        return m
    }

    private fun media(tag: Int) = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:$tag\n#EXTINF:4,\nseg$tag.ts\n".toByteArray()

    @Test
    fun sitePlayerStreamsAreListed() {
        SampleRecorder.sitePlayerStream("https://cdn.example/live.m3u8?t=1")
        SampleRecorder.sitePlayerStream("https://cdn.example/live.m3u8?t=1") // duplicate ignored
        val e = entries(SampleRecorder.zip("r", "1.0")!!)
        val urls = String(e["urls.txt"]!!)
        assertTrue(urls.contains("site-player-stream-1: https://cdn.example/live.m3u8?t=1"))
        assertTrue(!urls.contains("site-player-stream-2"))
        assertTrue(String(e["info.txt"]!!).contains("site's own player loaded 1 playlist"))
    }

    @Test
    fun nothingRecordedGivesNull() {
        assertNull(SampleRecorder.zip("r", "1.0"))
    }

    @Test
    fun zipHasAllEntries() {
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps://x/y.m3u8?t=SECRET\n".toByteArray()
        SampleRecorder.playlist("https://x/master.m3u8?t=SECRET", master, master.size, false, "application/vnd.apple.mpegurl")
        for (i in 1..4) SampleRecorder.playlist("https://x/media.m3u8", media(i), media(i).size, false, "text/plain")
        val c1 = SampleRecorder.beginChunk("https://x/c1.ts")!!
        val raw1 = ByteArray(1000) { it.toByte() }
        c1.received(raw1, 0, 600); c1.received(raw1, 600, 400); c1.delivered(900); c1.decision = "unwrapped: 100 junk bytes removed"
        val c2 = SampleRecorder.beginChunk("https://x/c2.ts")!!
        c2.received(ByteArray(10), 0, 10)
        assertNull(SampleRecorder.beginChunk("https://x/c3.ts"))

        val e = entries(SampleRecorder.zip("the report", "9.9")!!)
        assertEquals(
            setOf("README.txt", "report.txt", "master.m3u8", "media-1.m3u8", "media-2.m3u8", "media-3.m3u8",
                "chunk-1.bin", "chunk-2.bin", "urls.txt", "info.txt"),
            e.keys,
        )
        assertEquals("the report", String(e.getValue("report.txt")))
        assertArrayEquals(master, e.getValue("master.m3u8"))
        // Only the last 3 media versions are kept, oldest first.
        assertArrayEquals(media(2), e.getValue("media-1.m3u8"))
        assertArrayEquals(media(4), e.getValue("media-3.m3u8"))
        assertArrayEquals(raw1, e.getValue("chunk-1.bin"))
        val urls = String(e.getValue("urls.txt"))
        assertTrue(urls.contains("https://x/master.m3u8?t=SECRET"))
        assertTrue(urls.contains("https://x/c2.ts"))
        val info = String(e.getValue("info.txt"))
        assertTrue(info.contains("9.9"))
        assertTrue(info.contains("unwrapped: 100 junk bytes removed"))
        assertTrue(info.contains("bytes given to the player after unwrapping: 900"))
    }

    @Test
    fun unchangedMediaPlaylistIsNotDuplicated() {
        repeat(5) { SampleRecorder.playlist("https://x/m.m3u8", media(1), media(1).size, false, "t") }
        val e = entries(SampleRecorder.zip("r", "1")!!)
        assertTrue(e.containsKey("media-1.m3u8"))
        assertTrue(!e.containsKey("media-2.m3u8"))
    }

    @Test
    fun sizesAreCapped() {
        val head = "#EXTM3U\n#EXTINF:4,\n".toByteArray()
        val big = head + ByteArray(SampleRecorder.MAX_PLAYLIST + 5000) { 'a'.code.toByte() }
        SampleRecorder.playlist("https://x/m.m3u8", big, big.size, false, "t")
        val c = SampleRecorder.beginChunk("https://x/c.ts")!!
        val piece = ByteArray(1024 * 1024)
        repeat(8) { c.received(piece, 0, piece.size) }
        val e = entries(SampleRecorder.zip("r", "1")!!)
        assertEquals(SampleRecorder.MAX_PLAYLIST, e.getValue("media-1.m3u8").size)
        assertEquals(SampleRecorder.MAX_CHUNK, e.getValue("chunk-1.bin").size)
        assertEquals(8L * 1024 * 1024, c.rawSize)
        assertTrue(String(e.getValue("info.txt")).contains("cut at the 6 MB limit"))
        assertTrue(String(e.getValue("info.txt")).contains("TRUNCATED"))
    }

    @Test
    fun resetClears() {
        SampleRecorder.playlist("https://x/m.m3u8", media(1), media(1).size, false, "t")
        assertTrue(SampleRecorder.hasData())
        SampleRecorder.reset()
        assertTrue(!SampleRecorder.hasData())
        assertNull(SampleRecorder.zip("r", "1"))
    }

    @Test
    fun reportStartResets() {
        SampleRecorder.playlist("https://x/m.m3u8", media(1), media(1).size, false, "t")
        Report.start("https://x/page")
        assertTrue(!SampleRecorder.hasData())
    }

    @Test
    fun fileNameLooksRight() {
        assertTrue(Regex("streamlink-sample-\\d{8}-\\d{6}\\.zip").matches(SampleRecorder.fileName()))
    }

    private fun serve(sample: () -> ByteArray?): Pair<LinkServer, Int> {
        val s = LinkServer(pages = mapOf("/" to "x"), onLink = {}, sample = sample)
        return s to s.start()
    }

    @Test
    fun sampleRouteServesZip() {
        val payload = byteArrayOf(0x50, 0x4B, 3, 4, 0, -1, 127, -128)
        val (s, port) = serve { payload }
        try {
            val c = URL("http://127.0.0.1:$port/sample").openConnection() as HttpURLConnection
            assertEquals(200, c.responseCode)
            assertEquals("application/zip", c.contentType)
            val cd = c.getHeaderField("Content-Disposition")
            assertTrue(cd, Regex("attachment; filename=streamlink-sample-\\d{8}-\\d{6}\\.zip").matches(cd))
            assertEquals(payload.size, c.contentLength)
            assertArrayEquals(payload, c.inputStream.readBytes())
        } finally { s.stop() }
    }

    @Test
    fun sampleRouteIs404WhenNothingRecorded() {
        val (s, port) = serve { null }
        try {
            val c = URL("http://127.0.0.1:$port/sample").openConnection() as HttpURLConnection
            assertEquals(404, c.responseCode)
            assertEquals("No stream has been loaded yet", c.errorStream.readBytes().toString(Charsets.UTF_8))
        } finally { s.stop() }
    }

    @Test
    fun defaultSampleIs404() {
        val s = LinkServer(pages = emptyMap(), onLink = {})
        val port = s.start()
        try {
            assertEquals(404, (URL("http://127.0.0.1:$port/sample").openConnection() as HttpURLConnection).responseCode)
        } finally { s.stop() }
    }

    @Test
    fun realZipFromRecorderThroughServer() {
        SampleRecorder.playlist("https://x/m.m3u8", media(1), media(1).size, false, "t")
        val (s, port) = serve { SampleRecorder.zip("rep", "1") }
        try {
            val bytes = (URL("http://127.0.0.1:$port/sample").openConnection() as HttpURLConnection).inputStream.readBytes()
            assertNotNull(entries(bytes)["media-1.m3u8"])
        } finally { s.stop() }
    }
}
