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
    /** GET /reports: every saved play's report plus the current one (the running log), as a download. */
    private val allReports: () -> String = { "" },
    /** POST /reports/clear: empties the running log. */
    private val onReportsClear: () -> Unit = {},
    /** The saved-sample zip for GET /sample, or null if no stream has been loaded yet. */
    private val sample: () -> ByteArray? = { null },
    /** GET /lists: JSON of the saved stream lists. */
    private val lists: () -> String = { "{\"lists\":[]}" },
    /** GET /list?id=: JSON of one list's cached events, or null if there is no such list. */
    private val list: (String) -> String? = { null },
    /** POST /lists/add (url, name, sport; sport is a comma-separated list like "nfl,cfb", may be empty; unknown values ignored): null on success, otherwise the reason it was refused. */
    private val onListAdd: (String, String, Set<String>) -> String? = { _, _, _ -> "Stream lists are not available" },
    /** POST /lists/remove (id): false if there is no such list. */
    private val onListRemove: (String) -> Boolean = { false },
    /** POST /lists/sport (id, sport: comma-separated "nfl", "cfb", "nba", "cbb" or empty for none; unknown values ignored): false if there is no such list. */
    private val onListSport: (String, Set<String>) -> Boolean = { _, _ -> false },
    /** POST /lists/rank (id, rank = 1, 2, … or empty for unranked): ranks that list, others move down; false if there is no such list. */
    private val onListRank: (String, Int?) -> Boolean = { _, _ -> false },
    /** POST /lists/rename (id, name): false if there is no such list or the name is empty. */
    private val onListRename: (String, String) -> Boolean = { _, _ -> false },
    /** POST /lists/url (id, url): points a list at a new address; null on success, otherwise the reason it was refused. */
    private val onListUrl: (String, String) -> String? = { _, _ -> "Stream lists are not available" },
    /** POST /list/refresh (id): asks the TV to read the list again; false if there is no such list. */
    private val onListRefresh: (String) -> Boolean = { false },
    /** POST /list/play (id, url, start): plays an event of a list; start is its start time (epoch ms) if known. */
    private val onListPlay: (String, String, Long?) -> Boolean = { _, _, _ -> false },
    /** GET /photos: JSON of the sport photos (which sports have one, focus points). */
    private val photos: () -> String = { "{\"photos\":[]}" },
    /** GET /photo?league=: one sport's photo bytes, or null if it has none. */
    private val photo: (String) -> ByteArray? = { null },
    /** POST /photo/upload?league= (body: the JPEG/PNG bytes): null on success, otherwise why it was refused. */
    private val onPhotoUpload: (String, ByteArray) -> String? = { _, _ -> "Photos are not available" },
    /** POST /photo/focus (league, x, y as 0..1 fractions): false if that sport has no photo. */
    private val onPhotoFocus: (String, Float, Float) -> Boolean = { _, _, _ -> false },
    /** POST /photo/fade (league, w, d as 0..1 fractions): false if that sport has no photo. */
    private val onPhotoFade: (String, Float, Float) -> Boolean = { _, _, _ -> false },
    /** POST /photo/remove (league): false if there was nothing to remove. */
    private val onPhotoRemove: (String) -> Boolean = { false },
    /** GET /cityphotos: JSON of the city photos (see CityPhotos.json). */
    private val cityPhotos: () -> String = { "{\"cities\":[]}" },
    /** GET /cityphoto?key=: one city's current photo bytes, or null. */
    private val cityPhoto: (String) -> ByteArray? = { null },
    /** POST /cityphoto/next (key): swaps that city to its next search result; false if there is none. */
    private val onCityNext: (String) -> Boolean = { false },
    /** POST /cityphoto/focus (key, x, y as 0..1 fractions): false if that city has no photo. */
    private val onCityFocus: (String, Float, Float) -> Boolean = { _, _, _ -> false },
    /** GET /demo: whether demo games are on. */
    private val demo: () -> Boolean = { false },
    /** POST /demo/set (on=1|0): turns demo games on or off. */
    private val onDemo: (Boolean) -> Unit = { },
    /** GET /backup: a zip of all settings and data, or null if it couldn't be made. */
    private val backup: () -> ByteArray? = { null },
    /** POST /backup/restore (body: the zip): null on success (the app then restarts), otherwise why it was refused. */
    private val onRestore: (ByteArray) -> String? = { "Restore is not available" },
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
                val client = try {
                    s.accept()
                } catch (_: IOException) {
                    // Closed by stop(): done. Anything else (e.g. "too many open files", a hiccup when the Wi-Fi
                    // changes) is transient: keep listening, or the phone page stays dead until the app restarts.
                    if (!running || s.isClosed) break
                    try { Thread.sleep(250) } catch (_: InterruptedException) { break }
                    continue
                }
                try {
                    thread(isDaemon = true) { handle(client) }
                } catch (_: Throwable) { // could not start a thread (out of memory): drop this one request, keep serving
                    try { client.close() } catch (_: IOException) {}
                }
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
                var origin = ""
                var fetchSite = ""
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val name = line.substringBefore(':').trim().lowercase()
                    val value = line.substringAfter(':', "").trim()
                    when (name) {
                        "content-length" -> contentLength = value.toIntOrNull() ?: 0
                        "content-type" -> contentType = value.lowercase()
                        "host" -> host = value
                        "origin" -> origin = value
                        "sec-fetch-site" -> fetchSite = value.lowercase()
                    }
                }
                val limit = when (path) {
                    "/photo/upload" -> SportPhotoRules.MAX_BYTES
                    "/backup/restore" -> SettingsBackup.MAX_BYTES
                    else -> 65_536
                }
                val raw = if (contentLength in 1..limit) {
                    val buf = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(buf, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read == contentLength) buf else buf.copyOf(read)
                } else ByteArray(0)
                if (path == "/photo/upload" && contentLength > limit) {
                    respond(c, 400, "text/plain; charset=utf-8", "That photo is too big (3 MB at most)")
                    return
                }
                if (path == "/backup/restore" && contentLength > limit) {
                    respond(c, 400, "text/plain; charset=utf-8", "That file is too big for a StreamLink backup")
                    return
                }
                val binary = path == "/photo/upload" || path == "/backup/restore"
                val body = if (binary) "" else String(raw, Charsets.UTF_8)

                // Links and commands only from our own phone page or a Shortcut, never from another
                // website (which a browser would label with its own Origin / Sec-Fetch-Site).
                val changes = path in CHANGING_PATHS
                if (changes && method != "POST") {
                    respond(c, 405, "text/plain; charset=utf-8", "Use POST")
                    return
                }
                if (changes && !trustedCaller(origin, fetchSite, host)) {
                    respond(c, 403, "text/plain; charset=utf-8", "Not allowed from another website")
                    return
                }
                route(c, method, path, query, host, contentType, body, raw)
            } catch (_: Exception) {
            }
        }
    }

    private fun trustedCaller(origin: String, fetchSite: String, host: String): Boolean {
        if (fetchSite.isNotEmpty() && fetchSite != "same-origin" && fetchSite != "none") return false
        if (origin.isEmpty()) return true // iPhone Shortcut, curl: no browser page involved
        val o = origin.lowercase().substringAfter("://", "")
        return o.isNotEmpty() && o == host.lowercase()
    }

    private fun route(c: Socket, method: String, path: String, query: String, host: String, contentType: String, body: String, payload: ByteArray = ByteArray(0)) {
        val html = "text/html; charset=utf-8"
        val text = "text/plain; charset=utf-8"
        val json = "application/json; charset=utf-8"
        when {
            method == "GET" && pages.containsKey(path) ->
                respond(c, 200, html, pages.getValue(path).replace("{{ADDR}}", host))

            method == "GET" && path == "/status" -> respond(c, 200, text, status())
            method == "GET" && path == "/state" -> respond(c, 200, "application/json; charset=utf-8", state())
            method == "GET" && path == "/report" -> respond(c, 200, text, report())
            method == "GET" && path == "/reports" -> {
                val name = "streamlink-logs-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".txt"
                respondBytes(c, 200, text, allReports().toByteArray(Charsets.UTF_8), "Content-Disposition: attachment; filename=$name\r\n")
            }
            method == "GET" && path == "/backup" -> {
                val zip = try { backup() } catch (_: Exception) { null }
                if (zip == null) respond(c, 500, text, "Could not make the backup")
                else respondBytes(c, 200, "application/zip", zip, "Content-Disposition: attachment; filename=" + SettingsBackup.fileName() + "\r\n")
            }
            method == "POST" && path == "/backup/restore" -> {
                val err = if (payload.isEmpty()) "No file was sent" else try { onRestore(payload) } catch (_: Exception) { "Could not restore that file" }
                if (err == null) respond(c, 200, text, "Restored") else respond(c, 400, text, err)
            }
            method == "POST" && path == "/reports/clear" -> { onReportsClear(); respond(c, 200, text, "Cleared") }
            method == "GET" && path == "/sample" -> {
                val zip = try { sample() } catch (_: Exception) { null }
                if (zip == null) {
                    respond(c, 404, text, "No stream has been loaded yet")
                } else {
                    val name = SampleRecorder.fileName()
                    respondBytes(c, 200, "application/zip", zip, "Content-Disposition: attachment; filename=$name\r\n")
                }
            }

            path == "/play" && (method == "GET" || method == "POST") -> {
                val raw = extractUrl(body, contentType)
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

            method == "GET" && path == "/lists" -> respond(c, 200, json, lists())
            method == "GET" && path == "/list" -> {
                val id = formValue(query, "id")
                val body1 = if (id.isNullOrEmpty()) null else try { list(id) } catch (_: Exception) { null }
                if (body1 == null) respond(c, 404, text, "No such list") else respond(c, 200, json, body1)
            }

            method == "GET" && path == "/photos" -> respond(c, 200, json, photos())
            method == "GET" && path == "/photo" -> {
                val bytes = formValue(query, "league")?.let { photo(it) }
                if (bytes == null) respond(c, 404, text, "No photo") else
                    respondBytes(c, 200, if (bytes.size > 3 && bytes[1] == 'P'.code.toByte()) "image/png" else "image/jpeg", bytes)
            }
            // The routes below only change things; handle() already insists on POST + same origin.
            method == "POST" && path == "/photo/upload" -> {
                val err = onPhotoUpload(formValue(query, "league") ?: "", payload)
                if (err == null) respond(c, 200, text, "Saved") else respond(c, 400, text, err)
            }
            method == "POST" && path == "/photo/focus" -> {
                val lg = formValue(body, "league") ?: ""
                val x = SportPhotoRules.focus(formValue(body, "x"))
                val y = SportPhotoRules.focus(formValue(body, "y"))
                if (x != null && y != null && onPhotoFocus(lg, x, y)) respond(c, 200, text, "Saved") else respond(c, 400, text, "No photo for that sport")
            }
            method == "POST" && path == "/photo/fade" -> {
                val lg = formValue(body, "league") ?: ""
                val w = SportPhotoRules.focus(formValue(body, "w"))
                val d = SportPhotoRules.focus(formValue(body, "d"))
                if (w != null && d != null && onPhotoFade(lg, w, d)) respond(c, 200, text, "Saved") else respond(c, 400, text, "No photo for that sport")
            }
            method == "GET" && path == "/cityphotos" -> respond(c, 200, json, cityPhotos())
            method == "GET" && path == "/cityphoto" -> {
                val bytes = formValue(query, "key")?.let { cityPhoto(it) }
                if (bytes == null) respond(c, 404, text, "No photo") else respondBytes(c, 200, "image/jpeg", bytes)
            }
            method == "GET" && path == "/demo" -> respond(c, 200, json, "{\"on\":" + demo() + "}")
            method == "POST" && path == "/demo/set" -> { onDemo(formValue(body, "on") == "1"); respond(c, 200, text, "Saved") }
            method == "POST" && path == "/cityphoto/next" -> {
                if (onCityNext(formValue(body, "key") ?: "")) respond(c, 200, text, "Saved") else respond(c, 400, text, "No other photo for that city")
            }
            method == "POST" && path == "/cityphoto/focus" -> {
                val k = formValue(body, "key") ?: ""
                val x = SportPhotoRules.focus(formValue(body, "x"))
                val y = SportPhotoRules.focus(formValue(body, "y"))
                if (x != null && y != null && onCityFocus(k, x, y)) respond(c, 200, text, "Saved") else respond(c, 400, text, "No photo for that city")
            }
            method == "POST" && path == "/photo/remove" -> {
                if (onPhotoRemove(formValue(body, "league") ?: "")) respond(c, 200, text, "Removed") else respond(c, 404, text, "No photo")
            }
            method == "POST" && path == "/lists/add" -> {
                val url = normalize(formValue(body, "url"))
                if (url == null) {
                    respond(c, 400, text, "That doesn't look like a web link.")
                } else {
                    val problem = try { onListAdd(url, (formValue(body, "name") ?: "").take(200), Sports.parse((formValue(body, "sport") ?: "").take(40))) } catch (_: Exception) { "Could not save the list" }
                    if (problem == null) respond(c, 200, text, "Saved as a stream list") else respond(c, 400, text, problem)
                }
            }
            method == "POST" && path == "/lists/remove" -> {
                val id = formValue(body, "id")
                if (!id.isNullOrEmpty() && onListRemove(id)) respond(c, 200, text, "Removed") else respond(c, 404, text, "No such list")
            }
            method == "POST" && path == "/lists/sport" -> {
                val id = formValue(body, "id")
                val sports = Sports.parse((formValue(body, "sport") ?: "").take(40))
                if (!id.isNullOrEmpty() && onListSport(id, sports)) respond(c, 200, text, "Saved") else respond(c, 404, text, "No such list")
            }
            method == "POST" && path == "/lists/rename" -> {
                val id = formValue(body, "id")
                val name = (formValue(body, "name") ?: "").take(200)
                if (!id.isNullOrEmpty() && onListRename(id, name)) respond(c, 200, text, "Renamed") else respond(c, 404, text, "No such list or empty name")
            }
            method == "POST" && path == "/lists/url" -> {
                val id = formValue(body, "id")
                val url = normalize(formValue(body, "url"))
                when {
                    id.isNullOrEmpty() -> respond(c, 404, text, "No such list")
                    url == null -> respond(c, 400, text, "That doesn't look like a web link.")
                    else -> {
                        val problem = try { onListUrl(id, url) } catch (_: Exception) { "Could not save the address" }
                        if (problem == null) respond(c, 200, text, "Address saved") else respond(c, if (problem == "No such list") 404 else 400, text, problem)
                    }
                }
            }
            method == "POST" && path == "/lists/rank" -> {
                val id = formValue(body, "id")
                val rank = formValue(body, "rank")?.trim()?.take(4)?.toIntOrNull()?.takeIf { it >= 1 }
                if (!id.isNullOrEmpty() && onListRank(id, rank)) respond(c, 200, text, "Saved") else respond(c, 404, text, "No such list")
            }
            method == "POST" && path == "/list/refresh" -> {
                val id = formValue(body, "id")
                if (!id.isNullOrEmpty() && onListRefresh(id)) respond(c, 200, text, "Refreshing") else respond(c, 404, text, "No such list")
            }
            method == "POST" && path == "/list/play" -> {
                val id = formValue(body, "id")
                val url = normalize(formValue(body, "url"))
                if (id.isNullOrEmpty() || url == null) {
                    respond(c, 400, text, "Missing list or link")
                } else if (onListPlay(id, url, formValue(body, "start")?.toLongOrNull())) {
                    respond(c, 200, text, "Sent to TV")
                } else {
                    respond(c, 404, text, "No such list")
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
        /** Routes that change something: POST only, and never from another website. */
        private val CHANGING_PATHS = setOf("/play", "/cmd", "/lists/add", "/lists/remove", "/lists/sport", "/lists/rank", "/lists/rename", "/lists/url", "/list/refresh", "/list/play", "/photo/upload", "/photo/focus", "/photo/fade", "/photo/remove", "/cityphoto/next", "/cityphoto/focus", "/demo/set", "/backup/restore")
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

    private fun respond(c: Socket, code: Int, type: String, body: String) =
        respondBytes(c, code, type, body.toByteArray(Charsets.UTF_8))

    private fun respondBytes(c: Socket, code: Int, type: String, bytes: ByteArray, extraHeaders: String = "") {
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 405 -> "Method Not Allowed"; else -> "Not Found" }
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" + extraHeaders +
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
