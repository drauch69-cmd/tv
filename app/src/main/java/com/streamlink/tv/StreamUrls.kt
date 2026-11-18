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

    /**
     * Same stream address apart from the query and fragment: same scheme, host and path (case-insensitive,
     * a trailing slash ignored). A signed copy with a fresh access code is the "same stream" as an old one.
     */
    fun sameStream(a: String, b: String): Boolean {
        fun key(u: String): String? {
            val t = u.trim().substringBefore('#').substringBefore('?')
            val m = Regex("^(https?)://([^/]+)(/.*)?$", RegexOption.IGNORE_CASE).find(t) ?: return null
            val path = (m.groupValues[3]).trimEnd('/')
            return m.groupValues[1].lowercase() + "://" + m.groupValues[2].lowercase() + path
        }
        val x = key(a) ?: return false
        return x == key(b)
    }

    /** Path endings that are never a playlist (pictures, code, fonts, media pieces, data/pages). ".json" is kept out on purpose. */
    private val NOT_PLAYLIST_EXTENSIONS = listOf(
        ".woff", ".woff2", ".ttf", ".otf", ".eot", ".map", ".json", ".html", ".htm", ".xml",
        ".ts", ".m4s", ".mp4", ".m4v", ".m4a", ".aac", ".mp3", ".webm", ".cmfv", ".cmfa", ".fmp4", ".vtt", ".webvtt", ".key",
    )
    private val PLAYLIST_WORDS = setOf("playlist", "manifest", "chunklist", "master", "index", "list")

    /**
     * A request that is not a stream by [classify] but whose address may still be a playlist in disguise,
     * e.g. "/playlist/60325/load-playlist" or "/hls/master.txt". True when the path is not an image, script,
     * style, font, media piece, data or page file (see [NOT_PLAYLIST_EXTENSIONS]) and some path segment
     * holds one of the words playlist / manifest / chunklist / master / index / list as a whole word, optionally
     * followed by a number ("list2"): segments are
     * split at anything that is not a letter or digit, at lower-to-upper case changes ("loadPlaylist") and
     * before a trailing extension. "playlists", "indexed" and "reindex" do not count. "index.php" is a page, so
     * a segment that is exactly "index" with ".php"/".asp"/".aspx"/".jsp" does not count either. This only
     * decides what is worth downloading and looking at; the content check (#EXTM3U or <MPD) decides the rest.
     */
    fun isPossiblePlaylist(url: String): Boolean {
        if (!isWeb(url)) return false
        if (classify(url).first) return false
        val path = pathOf(url)
        if (IMAGE_OR_SCRIPT_EXTENSIONS.any { path.endsWith(it) } || NOT_PLAYLIST_EXTENSIONS.any { path.endsWith(it) }) return false
        val rawPath = url.substringBefore('#').substringBefore('?').substringAfter("://").substringAfter('/', "")
        if (rawPath.isEmpty()) return false
        for (segment in rawPath.split('/')) {
            if (segment.isEmpty()) continue
            val dot = segment.lastIndexOf('.')
            val ext = if (dot >= 0) segment.substring(dot).lowercase() else ""
            val stem = if (dot > 0) segment.substring(0, dot) else segment
            val camel = stem.replace(Regex("([a-z0-9])([A-Z])"), "\$1 \$2")
            val words = camel.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
            // A trailing number counts as part of the word: "list2", "index1", "master3".
            if (words.none { it.trimEnd { c -> c.isDigit() } in PLAYLIST_WORDS }) continue
            if (words == listOf("index") && ext in PAGE_EXTENSIONS) continue
            return true
        }
        return false
    }

    /** Could this (non-stream) request be a playlist whose address only gives itself away by repeating? See [RepeatWatch]. */
    fun isRepeatCandidate(url: String): Boolean {
        if (!isWeb(url) || classify(url).first) return false
        val path = pathOf(url)
        if (IMAGE_OR_SCRIPT_EXTENSIONS.any { path.endsWith(it) } || NOT_PLAYLIST_EXTENSIONS.any { path.endsWith(it) }) return false
        return url.substringBefore('#').substringBefore('?').substringAfter("://").substringAfter('/', "").isNotEmpty()
    }

    private val PAGE_EXTENSIONS = setOf(".php", ".asp", ".aspx", ".jsp")

    /** The address has a query string with something in it (often a signed, short-lived access code). */
    fun hasQuery(url: String): Boolean = url.substringBefore('#').substringAfter('?', "").isNotEmpty()

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

/**
 * "A repeating request is a possible playlist": a live playlist is asked for again every few seconds at the
 * same address, even when the address has no extension or playlist word. [seen] returns true exactly once per
 * address: when it is requested a 2nd time between [MIN_GAP_MS] and [MAX_GAP_MS] after its first request.
 * Only addresses that could be a playlist are tracked (see [StreamUrls.isRepeatCandidate]). Thread-safe.
 */
class RepeatWatch {
    private val first = LinkedHashMap<String, Long>()
    private val fired = HashSet<String>()

    @Synchronized
    fun seen(url: String, nowMs: Long): Boolean {
        if (!StreamUrls.isRepeatCandidate(url) || url in fired) return false
        val t = first[url]
        if (t == null) {
            first[url] = nowMs
            while (first.size > MAX_TRACKED) first.remove(first.keys.first())
            return false
        }
        val gap = nowMs - t
        if (gap < MIN_GAP_MS) return false
        if (gap > MAX_GAP_MS) { first[url] = nowMs; return false }
        first.remove(url)
        fired.add(url)
        if (fired.size > MAX_TRACKED) fired.remove(fired.first())
        return true
    }

    @Synchronized
    fun reset() { first.clear(); fired.clear() }

    companion object {
        const val MIN_GAP_MS = 1500L
        const val MAX_GAP_MS = 20000L
        const val MAX_TRACKED = 300
    }
}
