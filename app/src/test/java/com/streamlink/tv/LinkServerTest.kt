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
    private val added = CopyOnWriteArrayList<Pair<String, String>>()
    private val addedSport = CopyOnWriteArrayList<Set<String>>()
    private val sported = CopyOnWriteArrayList<Pair<String, Set<String>>>()
    private val removed = CopyOnWriteArrayList<String>()
    private val refreshed = CopyOnWriteArrayList<String>()
    private val played = CopyOnWriteArrayList<Triple<String, String, Long?>>()
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
            lists = { "{\"lists\":[{\"id\":\"a1\"}]}" },
            list = { id -> if (id == "a1") "{\"id\":\"a1\",\"items\":[]}" else null },
            onListAdd = { url, name, sport -> added.add(url to name); addedSport.add(sport); if (url.contains("full")) "Too many lists" else null },
            onListRemove = { id -> removed.add(id); id == "a1" },
            onListSport = { id, sport -> sported.add(id to sport); id == "a1" },
            onListRefresh = { id -> refreshed.add(id); id == "a1" },
            onListPlay = { id, url, start -> played.add(Triple(id, url, start)); id == "a1" },
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
    fun getPlayIsRefused() {
        val r = request("GET /play?url=https%3A%2F%2Fexample.com HTTP/1.1\r\nHost: x\r\n\r\n")
        assertTrue(r.startsWith("HTTP/1.1 405"))
        assertTrue(links.isEmpty())
    }

    @Test
    fun otherWebsitesAreRefused() {
        val body = "url=https%3A%2F%2Fevil.example"
        val r = request("POST /play HTTP/1.1\r\nHost: 192.168.1.50:8080\r\nOrigin: https://evil.example\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: ${body.length}\r\n\r\n$body")
        assertTrue(r.startsWith("HTTP/1.1 403"))
        val r2 = request("POST /cmd HTTP/1.1\r\nHost: 192.168.1.50:8080\r\nSec-Fetch-Site: cross-site\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: 8\r\n\r\nc=toggle")
        assertTrue(r2.startsWith("HTTP/1.1 403"))
        assertTrue(links.isEmpty())
        // Our own phone page is fine.
        val ok = request("POST /play HTTP/1.1\r\nHost: 192.168.1.50:8080\r\nOrigin: http://192.168.1.50:8080\r\n" +
            "Sec-Fetch-Site: same-origin\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: ${body.length}\r\n\r\n$body")
        assertTrue(ok.contains("Sent to TV"))
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

    private val form = "application/x-www-form-urlencoded"

    @Test
    fun listsAndListAreReadable() {
        assertTrue(request("GET /lists HTTP/1.1\r\nHost: x\r\n\r\n").contains("\"a1\""))
        val one = request("GET /list?id=a1 HTTP/1.1\r\nHost: x\r\n\r\n")
        assertTrue(one.startsWith("HTTP/1.1 200") && one.contains("application/json"))
        assertTrue(request("GET /list?id=zz HTTP/1.1\r\nHost: x\r\n\r\n").startsWith("HTTP/1.1 404"))
        assertTrue(request("GET /list HTTP/1.1\r\nHost: x\r\n\r\n").startsWith("HTTP/1.1 404"))
    }

    @Test
    fun addListNormalizesAndReportsProblems() {
        val ok = post("/lists/add", form, "url=https%3A%2F%2Fevents.example%2Findex&name=My%20list")
        assertTrue(ok.startsWith("HTTP/1.1 200"))
        assertEquals("https://events.example/index" to "My list", added.last())
        assertTrue(post("/lists/add", form, "url=events.example&name=").startsWith("HTTP/1.1 200"))
        assertEquals("https://events.example" to "", added.last())
        assertTrue(post("/lists/add", form, "url=not%20a%20link").startsWith("HTTP/1.1 400"))
        assertTrue(post("/lists/add", form, "url=https%3A%2F%2Ffull.example").startsWith("HTTP/1.1 400"))
        assertEquals(3, added.size)
    }

    @Test
    fun addListPassesSportAndSportCanBeChanged() {
        post("/lists/add", form, "url=https%3A%2F%2Fevents.example&name=&sport=nfl")
        assertEquals(setOf("nfl"), addedSport.last())
        post("/lists/add", form, "url=https%3A%2F%2Fevents.example&name=")
        assertEquals(emptySet<String>(), addedSport.last())
        post("/lists/add", form, "url=https%3A%2F%2Fevents.example&name=&sport=nfl%2Ccfb")
        assertEquals(setOf("nfl", "cfb"), addedSport.last())
        post("/lists/add", form, "url=https%3A%2F%2Fevents.example&name=&sport=nfl%2Cbogus")
        assertEquals(setOf("nfl"), addedSport.last())
        assertTrue(post("/lists/sport", form, "id=a1&sport=cfb").startsWith("HTTP/1.1 200"))
        assertTrue(post("/lists/sport", form, "id=a1&sport=").startsWith("HTTP/1.1 200"))
        assertTrue(post("/lists/sport", form, "id=zz&sport=nfl").startsWith("HTTP/1.1 404"))
        assertTrue(post("/lists/sport", form, "sport=nfl").startsWith("HTTP/1.1 404"))
        assertEquals(listOf("a1" to setOf("cfb"), "a1" to emptySet(), "zz" to setOf("nfl")), sported.toList())
    }

    @Test
    fun removeRefreshAndPlay() {
        assertTrue(post("/lists/remove", form, "id=a1").startsWith("HTTP/1.1 200"))
        assertTrue(post("/lists/remove", form, "id=zz").startsWith("HTTP/1.1 404"))
        assertTrue(post("/list/refresh", form, "id=a1").startsWith("HTTP/1.1 200"))
        assertTrue(post("/list/refresh", form, "id=zz").startsWith("HTTP/1.1 404"))
        assertEquals(listOf("a1", "zz"), refreshed.toList())
        val r = post("/list/play", form, "id=a1&url=https%3A%2F%2Fevents.example%2Fe%2F1&start=1234567890123")
        assertTrue(r.contains("Sent to TV"))
        assertEquals(Triple("a1", "https://events.example/e/1", 1234567890123L), played.last())
        post("/list/play", form, "id=a1&url=https%3A%2F%2Fevents.example%2Fe%2F2")
        assertEquals(Triple("a1", "https://events.example/e/2", null), played.last())
        assertTrue(post("/list/play", form, "id=a1&url=nope%20nope").startsWith("HTTP/1.1 400"))
        assertTrue(post("/list/play", form, "url=https%3A%2F%2Fevents.example").startsWith("HTTP/1.1 400"))
    }

    @Test
    fun listChangesAreRefusedFromGetAndFromOtherWebsites() {
        val routes = listOf("/lists/add" to "url=https%3A%2F%2Fevil.example", "/lists/remove" to "id=a1", "/lists/sport" to "id=a1&sport=nfl",
            "/list/refresh" to "id=a1", "/list/play" to "id=a1&url=https%3A%2F%2Fevil.example")
        for ((path, body) in routes) {
            // GET (even with the data in the query) is refused.
            assertTrue(path, request("GET $path?$body HTTP/1.1\r\nHost: 192.168.1.50:8080\r\n\r\n").startsWith("HTTP/1.1 405"))
            // Another website's Origin or Sec-Fetch-Site is refused.
            val withOrigin = request("POST $path HTTP/1.1\r\nHost: 192.168.1.50:8080\r\nOrigin: https://evil.example\r\n" +
                "Content-Type: $form\r\nContent-Length: ${body.length}\r\n\r\n$body")
            assertTrue(path, withOrigin.startsWith("HTTP/1.1 403"))
            val cross = request("POST $path HTTP/1.1\r\nHost: 192.168.1.50:8080\r\nSec-Fetch-Site: cross-site\r\n" +
                "Content-Type: $form\r\nContent-Length: ${body.length}\r\n\r\n$body")
            assertTrue(path, cross.startsWith("HTTP/1.1 403"))
        }
        assertTrue(added.isEmpty() && sported.isEmpty() && removed.isEmpty() && refreshed.isEmpty() && played.isEmpty())
        // Our own page is fine.
        val ok = request("POST /list/refresh HTTP/1.1\r\nHost: 192.168.1.50:8080\r\nOrigin: http://192.168.1.50:8080\r\n" +
            "Sec-Fetch-Site: same-origin\r\nContent-Type: $form\r\nContent-Length: 5\r\n\r\nid=a1")
        assertTrue(ok.startsWith("HTTP/1.1 200"))
    }

}
