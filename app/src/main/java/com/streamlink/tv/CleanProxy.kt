package com.streamlink.tv

/**
 * Pure helpers for the clean browser player's request handling (see [CleanPlayerActivity] and
 * [CleanFetcher]): which headers to send upstream, what to call the response, how to answer.
 * No Android classes here, so they are unit-tested on the JVM.
 */
object CleanProxy {
    const val ASSET_HOST = "appassets.androidplatform.net"
    const val ASSET_ORIGIN = "https://appassets.androidplatform.net"
    const val PAGE_URL = "$ASSET_ORIGIN/assets/clean.html"

    /** The only files the clean page may load from the app. */
    val ASSET_PATHS = setOf("/assets/clean.html", "/assets/clean.js", "/assets/hls.min.js")

    /** Largest single response handed to the page (a chunk is a few MB at most). */
    const val MAX_BODY_BYTES = 32 * 1024 * 1024

    class Upstream(val headers: Map<String, String>, val userAgent: String?)

    /**
     * Headers for fetching the stream natively, same rules as the built-in player: the source's
     * own headers minus Referer/Origin/X-Requested-With, plus Referer = the first candidate page
     * and the Origin derived from it. The User-Agent is returned separately (the data sources
     * set it through their own option).
     */
    fun upstream(src: Source, refererIndex: Int = 0): Upstream {
        val headers = LinkedHashMap<String, String>()
        var userAgent: String? = null
        for ((k, v) in src.headers) {
            when {
                k.equals("User-Agent", true) -> userAgent = v
                k.equals("Referer", true) || k.equals("Origin", true) || k.equals("X-Requested-With", true) -> {}
                else -> headers[k] = v
            }
        }
        val referer = src.referers.getOrNull(refererIndex) ?: ""
        if (referer.isNotEmpty()) {
            headers["Referer"] = referer
            Headers.originFor(referer, Headers.capturedOrigin(src.headers))?.let { headers["Origin"] = it }
        }
        return Upstream(headers, userAgent)
    }

    private val generic = setOf("application/octet-stream", "binary/octet-stream", "application/x-octet-stream", "text/plain", "text/html")

    private fun startsWith(b: ByteArray, s: String, at: Int = 0): Boolean {
        if (b.size < at + s.length) return false
        for (i in s.indices) if (b[at + i].toInt() != s[i].code) return false
        return true
    }

    /**
     * What to call a response. The body wins over the server's label (relayed streams are often
     * labelled as images or text): playlist text, MPEG-TS sync bytes, MP4 boxes; then a specific
     * Content-Type from the server; then the file extension.
     */
    fun mimeFor(url: String, upstreamType: String?, head: ByteArray): String {
        var i = 0
        if (head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) i = 3
        while (i < head.size && (head[i].toInt() == ' '.code || head[i].toInt() == '\n'.code || head[i].toInt() == '\r'.code)) i++
        if (startsWith(head, "#EXTM3U", i)) return "application/vnd.apple.mpegurl"
        if (head.size >= 189 && head[0].toInt() == 0x47 && head[188].toInt() == 0x47) return "video/mp2t"
        if (startsWith(head, "ftyp", 4) || startsWith(head, "styp", 4) || startsWith(head, "moof", 4) || startsWith(head, "moov", 4)) return "video/mp4"

        val declared = upstreamType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (declared.isNotEmpty() && declared !in generic && !declared.startsWith("image/")) return declared

        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> "application/vnd.apple.mpegurl"
            path.endsWith(".ts") -> "video/mp2t"
            path.endsWith(".m4s") || path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".cmfv") -> "video/mp4"
            path.endsWith(".m4a") || path.endsWith(".cmfa") -> "audio/mp4"
            path.endsWith(".aac") -> "audio/aac"
            path.endsWith(".mp3") -> "audio/mpeg"
            path.endsWith(".vtt") || path.endsWith(".webvtt") -> "text/vtt"
            path.endsWith(".mpd") -> "application/dash+xml"
            else -> "application/octet-stream"
        }
    }

    /** "bytes=100-199" -> (100, 199); "bytes=100-" -> (100, null). Anything else -> null (serve it all). */
    fun parseRange(header: String?): Pair<Long, Long?>? {
        val h = header?.trim()?.lowercase() ?: return null
        if (!h.startsWith("bytes=") || h.contains(',')) return null
        val spec = h.removePrefix("bytes=")
        val dash = spec.indexOf('-')
        if (dash <= 0) return null // suffix ranges ("-500") aren't supported
        val start = spec.substring(0, dash).toLongOrNull() ?: return null
        val endText = spec.substring(dash + 1)
        val end = if (endText.isEmpty()) null else (endText.toLongOrNull() ?: return null)
        if (end != null && end < start) return null
        return Pair(start, end)
    }

    /**
     * A status a WebResourceResponse accepts (100-299 and 400-599); redirects can't be passed on
     * (the data sources follow them already), so anything else becomes 502 "bad gateway".
     */
    fun safeStatus(code: Int): Int = if (code in 100..299 || code in 400..599) code else 502

    fun reason(code: Int): String = when (code) {
        200 -> "OK"
        204 -> "No Content"
        206 -> "Partial Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        416 -> "Range Not Satisfiable"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> if (code in 200..299) "OK" else "Error"
    }

    /** CORS headers so hls.js (running at the asset origin) may read the answer. */
    fun corsHeaders(): MutableMap<String, String> = linkedMapOf(
        "Access-Control-Allow-Origin" to ASSET_ORIGIN,
        "Access-Control-Expose-Headers" to "Content-Length, Content-Range, Content-Type",
    )

    fun isAssetUrl(host: String?, path: String?): Boolean =
        host.equals(ASSET_HOST, ignoreCase = true) && path in ASSET_PATHS
}
