package com.streamlink.tv

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

class LinkServerPhotoTest {
    private val uploads = CopyOnWriteArrayList<Pair<String, ByteArray>>()
    private val focus = CopyOnWriteArrayList<Triple<String, Float, Float>>()
    private lateinit var server: LinkServer
    private var port = 0

    @Before
    fun setUp() {
        server = LinkServer(
            pages = emptyMap(),
            onLink = {},
            photos = { "{\"photos\":[{\"league\":\"nfl\",\"has\":true}]}" },
            photo = { lg -> if (lg == "nfl") byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2) else null },
            onPhotoUpload = { lg, b -> uploads.add(lg to b); if (lg == "nfl") null else "Unknown sport" },
            onPhotoFocus = { lg, x, y -> focus.add(Triple(lg, x, y)); lg == "nfl" },
            onPhotoRemove = { lg -> lg == "nfl" },
        )
        port = server.start()
    }

    @After
    fun tearDown() = server.stop()

    private fun send(head: String, body: ByteArray = ByteArray(0)): ByteArray =
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5000
            s.getOutputStream().write(head.toByteArray())
            s.getOutputStream().write(body)
            s.getOutputStream().flush()
            s.getInputStream().readBytes()
        }

    private fun post(path: String, type: String, body: ByteArray, origin: String = "") =
        String(send("POST $path HTTP/1.1\r\nHost: 192.168.1.50:8080\r\n$origin" +
            "Content-Type: $type\r\nContent-Length: ${body.size}\r\n\r\n", body))

    @Test fun uploadKeepsTheExactBytes() {
        // Bytes that are not valid UTF-8 must arrive untouched.
        val pic = ByteArray(200_000) { (it * 31).toByte() }
        val r = post("/photo/upload?league=nfl", "image/jpeg", pic)
        assertTrue(r, r.startsWith("HTTP/1.1 200"))
        assertEquals("nfl", uploads.single().first)
        assertArrayEquals(pic, uploads.single().second)
        assertTrue(post("/photo/upload?league=xyz", "image/jpeg", pic).startsWith("HTTP/1.1 400"))
    }

    @Test fun uploadFromAnotherWebsiteIsRefused() {
        val r = post("/photo/upload?league=nfl", "image/jpeg", ByteArray(100), "Origin: https://evil.example\r\n")
        assertTrue(r.startsWith("HTTP/1.1 403"))
        assertTrue(uploads.isEmpty())
    }

    @Test fun focusAndPhotoReads() {
        assertTrue(post("/photo/focus", "application/x-www-form-urlencoded", "league=nfl&x=0.7&y=0.2".toByteArray()).startsWith("HTTP/1.1 200"))
        assertEquals(Triple("nfl", 0.7f, 0.2f), focus.single())
        assertTrue(post("/photo/focus", "application/x-www-form-urlencoded", "league=nfl&x=a&y=0.2".toByteArray()).startsWith("HTTP/1.1 400"))
        val got = send("GET /photo?league=nfl HTTP/1.1\r\nHost: x\r\n\r\n")
        val text = String(got, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("HTTP/1.1 200") && text.contains("image/jpeg"))
        assertTrue(String(send("GET /photo?league=nba HTTP/1.1\r\nHost: x\r\n\r\n")).startsWith("HTTP/1.1 404"))
        assertTrue(String(send("GET /photos HTTP/1.1\r\nHost: x\r\n\r\n")).contains("\"league\":\"nfl\""))
        assertTrue(post("/photo/remove", "application/x-www-form-urlencoded", "league=nfl".toByteArray()).startsWith("HTTP/1.1 200"))
    }
}
