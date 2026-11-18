package com.streamlink.tv

import java.net.URLDecoder

/**
 * Pure URL rules for the sniffer: which requests are playlists, and which playlist addresses are
 * carried (URL-encoded) inside other requests, such as an analytics ping's "mu=" parameter.
 */
object StreamUrls {
    private val IMAGE_OR_SCRIPT_EXTENSIONS = listOf(".gif", ".png", ".jpg", ".jpeg", ".webp", ".ico", ".svg", ".js", ".css")
    private val ENCODED = Regex("%[0-9A-Fa-f]{2}")

    /**
     * (isStream, embeddedStreamUrls) for one request URL.
     * - A path ending in an image/script/style extension is never a stream.
     * - A path with "m3u8" in it, or ending in ".mpd", is a stream.
     * - Otherwise each query value is decoded. Absolute http(s) values whose own path is a stream are
     *   returned as embedded streams. A non-address value containing "m3u8" (a proxy-style link such as
     *   "/play?file=chan.m3u8") makes the URL itself a stream.
     */
    fun classify(url: String): Pair<Boolean, List<String>> {
        if (!isWeb(url)) return false to emptyList()
        val path = pathOf(url)
        val image = IMAGE_OR_SCRIPT_EXTENSIONS.any { path.endsWith(it) }
        if (!image && (path.contains("m3u8") || path.endsWith(".mpd"))) return true to emptyList()
        val values = queryValues(url)
        val embedded = values.filter { isStreamAddress(it) }.distinct()
        val proxy = !image && values.any { !isWeb(it) && it.lowercase().contains("m3u8") }
        return proxy to embedded
    }

    private fun isWeb(url: String): Boolean = url.startsWith("http://", true) || url.startsWith("https://", true)

    /** An absolute http(s) address whose own path is a stream (and not an image or script). */
    private fun isStreamAddress(url: String): Boolean {
        if (!isWeb(url)) return false
        val path = pathOf(url)
        if (IMAGE_OR_SCRIPT_EXTENSIONS.any { path.endsWith(it) }) return false
        return path.contains("m3u8") || path.endsWith(".mpd")
    }

    /** The lowercased path: no scheme-less query, no fragment. */
    private fun pathOf(url: String): String = url.substringBefore('#').substringBefore('?').lowercase()

    /** Decoded query values. Empty when the query does not mention a playlist at all. */
    private fun queryValues(url: String): List<String> {
        val query = url.substringBefore('#').substringAfter('?', "")
        if (!query.contains("m3u8", true) && !query.contains(".mpd", true)) return emptyList()
        return query.split('&').filter { it.isNotEmpty() }.map { param ->
            decodeValue(if (param.contains('=')) param.substringAfter('=') else param)
        }
    }

    /** Decodes once, and once more if the result still holds percent-escapes. */
    private fun decodeValue(raw: String): String {
        var v = safeDecode(raw)
        if (ENCODED.containsMatchIn(v)) v = safeDecode(v)
        return v
    }

    private fun safeDecode(s: String): String = try {
        // Keep a literal '+' (URLDecoder would turn it into a space).
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (_: IllegalArgumentException) {
        s
    }
}
