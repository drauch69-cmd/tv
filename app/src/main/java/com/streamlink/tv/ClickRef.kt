package com.streamlink.tv

import java.net.URLDecoder

/**
 * An event row that has no URL of its own (the list page opens it with a JS click handler).
 * It is stored as the list page's URL plus a fragment: `<pageUrl>#sl-click=<URL-encoded JSON {"t","i","s"}>`
 * where t = title, i = index among the rows matching s, s = CSS selector of the rows ("" if unknown).
 * The URL stays an absolute http(s) URL, so everything that handles event URLs accepts it.
 * StreamSniffer loads [pageUrl] and replays the click with assets/click-row.js.
 */
internal data class ClickRef(val pageUrl: String, val title: String, val index: Int, val selector: String) {

    /** The `#sl-click=` URL for this row (same format list-extract.js writes). */
    fun toUrl(): String {
        // A '#' inside the page address (hash-routed sites) can't also be in the fragment, so it travels as "h".
        val hash = pageUrl.indexOf('#').let { if (it < 0) "" else pageUrl.substring(it) }
        val base = pageUrl.substringBefore('#')
        return base + MARK + encode(json(hash))
    }

    private fun json(hash: String = ""): String =
        "{\"t\":" + quote(title) + ",\"i\":" + index + ",\"s\":" + quote(selector) +
            (if (hash.isEmpty()) "" else ",\"h\":" + quote(hash)) + "}"

    companion object {
        const val MARK = "#sl-click="

        fun isClickUrl(url: String): Boolean = url.contains(MARK)

        /** The page URL without any click fragment (the URL itself if it has none). */
        fun pagePart(url: String): String {
            val at = url.lastIndexOf(MARK)
            return if (at < 0) url else (parse(url)?.pageUrl ?: url.substring(0, at))
        }

        /** Reads a click URL; null if it is not one or its fragment is malformed. Never throws. */
        fun parse(url: String): ClickRef? {
            val at = url.lastIndexOf(MARK)
            if (at < 0) return null
            val page = url.substring(0, at)
            if (!page.startsWith("http://") && !page.startsWith("https://")) return null
            return try {
                val raw = URLDecoder.decode(url.substring(at + MARK.length), "UTF-8")
                val o = MiniJson.parse(raw) as? Map<*, *> ?: return null
                val title = (o["t"] as? String)?.trim().orEmpty()
                val sel = (o["s"] as? String)?.trim().orEmpty()
                val idx = (o["i"] as? Double)?.toInt() ?: -1
                val hash = (o["h"] as? String)?.takeIf { it.startsWith("#") }.orEmpty()
                if (title.isEmpty() && sel.isEmpty()) null else ClickRef(page + hash, title, idx, sel)
            } catch (_: Exception) {
                null
            }
        }

        /** The JSON spec handed to click-row.js. */
        fun specJson(r: ClickRef): String = r.json()

        private fun encode(s: String): String =
            java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        private fun quote(s: String): String {
            val sb = StringBuilder("\"")
            for (c in s) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> sb.append("\\r")
                    c == '\t' -> sb.append("\\t")
                    c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                    else -> sb.append(c)
                }
            }
            return sb.append('"').toString()
        }
    }
}

/** Picks the embedded frame to read instead of the page itself, when the page's own list is empty. */
internal object FramePick {
    /** One iframe as reported by list-extract.js. [pct] = its size as a percentage of the viewport (clamped to it). */
    data class Frame(val src: String, val pct: Double, val sameOrigin: Boolean, val visible: Boolean)

    /** [strong]: the frame is large (>= 35% of the viewport), so follow it right away; otherwise it is only the largest one. */
    data class Pick(val url: String, val strong: Boolean)

    const val STRONG_PCT = 35.0
    private const val MIN_PCT = 3.0

    fun pick(
        frames: List<Frame>,
        pageUrl: String,
        hostBlocked: (String?) -> Boolean = { AdBlock.isBlockedHost(it) || AdBlock.isLocalHost(it) },
    ): Pick? {
        val here = pageUrl.substringBefore('#').trimEnd('/')
        val ok = frames.filter { f ->
            if (!f.visible || f.pct < MIN_PCT) return@filter false
            val u = try { java.net.URI(f.src) } catch (_: Exception) { return@filter false }
            u.scheme?.lowercase() in listOf("http", "https") && u.host != null && !hostBlocked(u.host) &&
                f.src.substringBefore('#').trimEnd('/') != here
        }
        val best = ok.maxByOrNull { it.pct } ?: return null
        return Pick(best.src, best.pct >= STRONG_PCT)
    }
}
