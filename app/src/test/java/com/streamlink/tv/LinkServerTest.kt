package com.streamlink.tv

import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

class LinkServerTest {
    private fun request(port: Int, raw: String): String {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5000
            s.getOutputStream().write(raw.toByteArray())
            s.getOutputStream().flush()
            return s.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    @Test
    fun servesPhonePage() {
        var got: String? = null
        val server = LinkServer({ got = it }, { "Waiting" })
        val port = server.start()
        try {
            val page = request(port, "GET / HTTP/1.1\r\nHost: localhost\r\nUser-Agent: test\r\n\r\n")
            println(page.take(200))
            assertTrue(page.startsWith("HTTP/1.1 200"))
            assertTrue(page.contains("Send to TV"))

            val play = request(port, "POST /play HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: 29\r\n\r\nurl=https%3A%2F%2Fexample.com")
            assertTrue(play.contains("Sent to TV"))
            assertTrue(got == "https://example.com")
        } finally {
            server.stop()
        }
    }
}
