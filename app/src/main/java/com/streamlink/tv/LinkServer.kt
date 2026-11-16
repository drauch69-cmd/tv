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
 * A tiny web server on the TV. Your phone opens its page, pastes a link, and it's sent to [onLink].
 * Only runs while the app is open.
 */
class LinkServer(
    private val onLink: (String) -> Unit,
    private val status: () -> String,
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
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1].substringBefore('?')

                var contentLength = 0
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    if (line.lowercase().startsWith("content-length:")) {
                        contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
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

                when {
                    method == "GET" && path == "/" -> respond(c, 200, "text/html; charset=utf-8", PAGE)
                    method == "GET" && path == "/status" -> respond(c, 200, "text/plain; charset=utf-8", status())
                    method == "POST" && path == "/play" -> {
                        val url = normalize(formValue(body, "url"))
                        if (url == null) {
                            respond(c, 400, "text/plain; charset=utf-8", "That doesn't look like a web link.")
                        } else {
                            onLink(url)
                            respond(c, 200, "text/plain; charset=utf-8", "Sent to TV")
                        }
                    }
                    else -> respond(c, 404, "text/plain", "Not found")
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun formValue(body: String, key: String): String? =
        body.split('&').firstNotNullOfOrNull { pair ->
            val k = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
            if (k == key) URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") else null
        }

    private fun normalize(raw: String?): String? {
        var u = raw?.trim() ?: return null
        if (u.isEmpty() || u.contains(' ')) return null
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "https://$u"
        return if (u.substringAfter("://").contains('.')) u else null
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

    companion object {
        private val PAGE = """
<!DOCTYPE html>
<html><head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>StreamLink</title>
<style>
  body{margin:0;font-family:system-ui,sans-serif;background:#0d1220;color:#e8edf8;padding:24px}
  h1{font-size:24px;margin:0 0 16px}
  input{width:100%;box-sizing:border-box;font-size:18px;padding:14px;border-radius:10px;border:1px solid #2c3a5c;background:#162038;color:#fff}
  button{width:100%;margin-top:12px;font-size:20px;padding:14px;border:0;border-radius:10px;background:#4f8cff;color:#fff;font-weight:600}
  #status{margin-top:20px;font-size:17px;color:#b8c4e0;min-height:24px}
</style></head>
<body>
<h1>Send to TV</h1>
<form id="f">
  <input id="u" type="url" placeholder="Paste a link" autocomplete="off" autofocus>
  <button type="submit">Play on TV</button>
</form>
<div id="status"></div>
<script>
var st=document.getElementById('status'), timer=null;
document.getElementById('f').onsubmit=function(e){
  e.preventDefault();
  var u=document.getElementById('u').value.trim(); if(!u) return;
  st.textContent='Sending…';
  fetch('/play',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'url='+encodeURIComponent(u)})
    .then(function(r){return r.text().then(function(t){ st.textContent=t; if(r.ok) poll(); });})
    .catch(function(){ st.textContent='Could not reach the TV. Is the app open?'; });
};
function poll(){
  clearInterval(timer); var n=0;
  timer=setInterval(function(){
    fetch('/status').then(function(r){return r.text();}).then(function(t){
      st.textContent=t;
      if(++n>30 || /^(Playing|Couldn|Playback)/.test(t)) clearInterval(timer);
    }).catch(function(){});
  },1500);
}
</script>
</body></html>
""".trimIndent()
    }
}
