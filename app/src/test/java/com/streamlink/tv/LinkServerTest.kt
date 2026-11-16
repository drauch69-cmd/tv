package com.streamlink.tv

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

class LinkServerTest {
    private val links = CopyOnWriteArrayList<String>()
    private val commands = CopyOnWriteArrayList<Pair<String, String?>>()
    private lateinit var server: LinkServer
    private var port = 0

    @Before
    fun setUp() {
        server = LinkServer(
            pages = mapOf("/" to "<html>Send to TV at {{ADDR}}</html>", "/shortcut" to "<p>http://{{ADDR}}/play</p>"),
            onLink = { links.add(it) },
            onCommand = { c, v -> commands.add(c to v) },
            status = { "Waiting" },
            state = { "{\"mode\":\"idle\"}" },
            report = { "report text" },
        )
        port = server.start()
    }

    @After
    fun tearDown() = server.stop()

    private fun request(raw: String): String {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5000
            s.getOutputStream().write(raw.toByteArray())
            s.getOutputStream().flush()
            return s.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun post(path: String, type: String, body: String): String {
        val bytes = body.toByteArray()
        return request("POST $path HTTP/1.1\r\nHost: 192.168.1.50:8080\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n\r\n$body")
    }

    @Test
    fun servesPhonePageWithAddress() {
        val page = request("GET / HTTP/1.1\r\nHost: 192.168.1.50:8080\r\n\r\n")
        assertTrue(page.startsWith("HTTP/1.1 200"))
        assertTrue(page.contains("Send to TV at 192.168.1.50:8080"))
        val sc = request("GET /shortcut HTTP/1.1\r\nHost: 10.0.0.7:8080\r\n\r\n")
        assertTrue(sc.contains("http://10.0.0.7:8080/play"))
    }

    @Test
    fun statusStateReport() {
        assertTrue(request("GET /status HTTP/1.1\r\nHost: x\r\n\r\n").endsWith("Waiting"))
        assertTrue(request("GET /state HTTP/1.1\r\nHost: x\r\n\r\n").contains("{\"mode\":\"idle\"}"))
        assertTrue(request("GET /report HTTP/1.1\r\nHost: x\r\n\r\n").endsWith("report text"))
        assertTrue(request("GET /nope HTTP/1.1\r\nHost: x\r\n\r\n").startsWith("HTTP/1.1 404"))
    }

    @Test
    fun postForm() {
        val r = post("/play", "application/x-www-form-urlencoded", "url=https%3A%2F%2Fexample.com")
        assertTrue(r.contains("Sent to TV"))
        assertEquals("https://example.com", links.last())
    }

    @Test
    fun postJson() {
        val r = post("/play", "application/json", "{\"url\":\"https:\\/\\/example.com\\/live\"}")
        assertTrue(r.contains("Sent to TV"))
        assertEquals("https://example.com/live", links.last())
    }

    @Test
    fun getPlay() {
        val r = request("GET /play?url=https%3A%2F%2Fexample.com%2Fwatch%3Fv%3D1 HTTP/1.1\r\nHost: x\r\n\r\n")
        assertTrue(r.startsWith("HTTP/1.1 200"))
        assertTrue(r.contains("Sent to TV"))
        assertEquals("https://example.com/watch?v=1", links.last())
    }

    @Test
    fun normalizesSharedText() {
        assertEquals("https://x.com/a", LinkServer.normalize("Watch this https://x.com/a"))
        assertEquals("https://example.com", LinkServer.normalize("example.com"))
        assertEquals(null, LinkServer.normalize("not a link"))
        val r = post("/play", "text/plain", "Watch this https://x.com/a")
        assertTrue(r.contains("Sent to TV"))
        assertEquals("https://x.com/a", links.last())
    }

    @Test
    fun multipartForm() {
        val b = "--XYZ\r\nContent-Disposition: form-data; name=\"url\"\r\n\r\nhttps://example.com/m\r\n--XYZ--\r\n"
        post("/play", "multipart/form-data; boundary=XYZ", b)
        assertEquals("https://example.com/m", links.last())
    }

    @Test
    fun commands() {
        assertTrue(post("/cmd", "application/x-www-form-urlencoded", "c=toggle").contains("ok"))
        assertEquals("toggle" to null, commands.last())
        post("/cmd", "application/x-www-form-urlencoded", "c=quality&v=2")
        assertEquals("quality" to "2", commands.last())
        assertTrue(post("/cmd", "application/x-www-form-urlencoded", "").startsWith("HTTP/1.1 400"))
    }
}
