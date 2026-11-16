package com.streamlink.tv

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * A tiny web server on the TV. Your phone opens its page, sends links, and uses it as a remote.
 * Only runs while the app is open. Plain JVM code (no Android APIs) so it can be unit tested.
 */
class LinkServer(
    private val pages: Map<String, String>,
    private val onLink: (String) -> Unit,
    private val onCommand: (String, String?) -> Unit = { _, _ -> },
    private val status: () -> String = { "" },
    private val state: () -> String = { "{}" },
    private val report: () -> String = { "" },
) {
    private var socket: ServerSocket? = null
    @Volatile private var running = false

    /** Starts the server and returns the port it's listening on. */
    fun start(): Int {
        for (p in listOf(8080, 8081, 8888, 0)) {
            try {
                socket = ServerSocket(p)
                break
            } catch (_: IOException) {
            }
        }
        val s = socket ?: throw IOException("No free port")
        running = true
        thread(isDaemon = true, name = "link-server") {
            while (running) {
                val client = try { s.accept() } catch (_: IOException) { break }
                thread(isDaemon = true) { handle(client) }
            }
        }
        return s.localPort
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: IOException) {}
    }

    private fun handle(client: Socket) {
        client.use { c ->
            try {
                c.soTimeout = 10_000
                val input = BufferedInputStream(c.getInputStream())
                // If the phone tries a secure (https) connection first, refuse it right away so
                // the browser falls back to plain http instead of waiting and timing out.
                input.mark(1)
                val first = input.read()
                if (first < 0 || first == 0x16) return
                input.reset()

                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val target = parts[1]
                val path = target.substringBefore('?')
                val query = target.substringAfter('?', "")

                var contentLength = 0
                var contentType = ""
                var host = ""
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val name = line.substringBefore(':').trim().lowercase()
                    val value = line.substringAfter(':', "").trim()
                    when (name) {
                        "content-length" -> contentLength = value.toIntOrNull() ?: 0
                        "content-type" -> contentType = value.lowercase()
                        "host" -> host = value
                    }
                }
                val body = if (contentLength in 1..65_536) {
                    val buf = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(buf, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    String(buf, 0, read, Charsets.UTF_8)
                } else ""

                route(c, method, path, query, host, contentType, body)
            } catch (_: Exception) {
            }
        }
    }

    private fun route(c: Socket, method: String, path: String, query: String, host: String, contentType: String, body: String) {
        val html = "text/html; charset=utf-8"
        val text = "text/plain; charset=utf-8"
        when {
            method == "GET" && pages.containsKey(path) ->
                respond(c, 200, html, pages.getValue(path).replace("{{ADDR}}", host))

            method == "GET" && path == "/status" -> respond(c, 200, text, status())
            method == "GET" && path == "/state" -> respond(c, 200, "application/json; charset=utf-8", state())
            method == "GET" && path == "/report" -> respond(c, 200, text, report())

            path == "/play" && (method == "GET" || method == "POST") -> {
                val raw = if (method == "GET") formValue(query, "url") else extractUrl(body, contentType)
                val url = normalize(raw)
                if (url == null) {
                    respond(c, 400, if (method == "GET") html else text,
                        if (method == "GET") page("That doesn't look like a web link.") else "That doesn't look like a web link.")
                } else {
                    onLink(url)
                    if (method == "GET") respond(c, 200, html, page("Sent to TV ✓"))
                    else respond(c, 200, text, "Sent to TV")
                }
            }

            path == "/cmd" && (method == "POST" || method == "GET") -> {
                val cmd = formValue(body, "c") ?: formValue(query, "c")
                val v = formValue(body, "v") ?: formValue(query, "v")
                if (cmd.isNullOrBlank()) {
                    respond(c, 400, text, "Missing command")
                } else {
                    onCommand(cmd, v)
                    respond(c, 200, text, "ok")
                }
            }

            else -> respond(c, 404, text, "Not found")
        }
    }

    private fun page(message: String) =
        "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<title>StreamLink</title></head><body style=\"margin:0;padding:32px;font-family:system-ui,sans-serif;" +
            "background:#0d1220;color:#e8edf8;font-size:20px\">" + escapeHtml(message) +
            "<p><a href=\"/\" style=\"color:#4f8cff\">Open the remote</a></p></body></html>"

    private fun escapeHtml(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    companion object {
        private val URL_IN_TEXT = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
        private val JSON_URL = Regex("\"url\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

        /** Pulls the link out of a POST body: JSON, multipart form, urlencoded form, or plain text. */
        fun extractUrl(body: String, contentType: String): String? {
            val ct = contentType.lowercase()
            if (ct.contains("json") || body.trimStart().startsWith("{")) {
                JSON_URL.find(body)?.let { m ->
                    return m.groupValues[1]
                        .replace("\\/", "/")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                }
            }
            if (ct.contains("multipart")) {
                val idx = body.indexOf("name=\"url\"")
                if (idx >= 0) {
                    val rest = body.substring(idx)
                    val sep = Regex("\\r?\\n\\r?\\n").find(rest)
                    if (sep != null) {
                        val value = rest.substring(sep.range.last + 1)
                        return value.substringBefore("\r\n").substringBefore("\n").trim()
                    }
                }
            }
            formValue(body, "url")?.let { return it }
            return body.trim().ifEmpty { null }
        }

        fun formValue(body: String, key: String): String? {
            if (body.isEmpty()) return null
            return body.split('&').firstNotNullOfOrNull { pair ->
                try {
                    val k = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
                    if (k == key) URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") else null
                } catch (_: Exception) {
                    null
                }
            }
        }

        /** Turns whatever the phone sent into a web link, or null if it isn't one. */
        fun normalize(raw: String?): String? {
            val text = raw?.trim() ?: return null
            if (text.isEmpty()) return null
            URL_IN_TEXT.find(text)?.let { return it.value.trimEnd(')', ']', '>', '"', '\'', ',', '.') }
            if (text.contains(' ')) return null
            val u = "https://$text"
            return if (text.contains('.')) u else null
        }
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("UTF-8")
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
            if (out.size() > 8192) break
        }
        return out.toString("UTF-8")
    }

    private fun respond(c: Socket, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; else -> "Not Found" }
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        val out = c.getOutputStream()
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
        // Close gently: signal we're done, then drain anything the phone still sends, so the
        // connection isn't reset before the phone has read the page.
        try {
            c.shutdownOutput()
            c.soTimeout = 1000
            val sink = ByteArray(1024)
            val input = c.getInputStream()
            while (input.read(sink) >= 0) { }
        } catch (_: Exception) {
        }
    }
}
