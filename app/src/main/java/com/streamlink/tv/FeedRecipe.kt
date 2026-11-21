package com.streamlink.tv

import org.json.JSONArray
import org.json.JSONObject

/**
 * "Data file" recipes (user, 2026-11-20): some index pages draw their event rows with scripts from a JSON file the
 * page downloads. A recipe's "feed" says where that file is and which fields hold what; the TV then downloads the
 * file instead of drawing the page. Pure (org.json only), unit tested in FeedRecipeTest; no site names in code.
 *
 * Templates (in url, link and filter values): {path:N} = the list address's Nth path part (0 first, -1 last);
 * {field.sub} = a field of the event; {x|text} uses text when x is missing; a filter value "*" matches everything. Field names are dotted paths ("teams.home.name").
 */
object FeedRecipe {
    data class Feed(
        val url: String,
        /** Dotted path to the events array in the answer; "" = the answer itself is the array. */
        val items: String = "",
        /** Keep only events whose field equals the (expanded) value. */
        val filter: Map<String, String> = emptyMap(),
        /** The event's page, relative to the list page or absolute. */
        val link: String,
        val title: String = "title",
        val subtitle: String? = null,
        val section: String? = null,
        /** Start time field: epoch ms, or seconds when [startUnit] is "s". 0 or missing = no start time. */
        val start: String? = null,
        val startUnit: String = "ms",
        /** End time field (same unit): the event counts as live between start and end. */
        val end: String? = null,
        /** A field that says live (true, "live", "1"...). */
        val live: String? = null,
    )

    /** Parses a recipe's "feed" object (from MiniJson); null when url or link is missing. Never throws. */
    fun parse(m: Map<*, *>?): Feed? {
        if (m == null) return null
        fun s(k: String) = (m[k] as? String)?.trim()?.ifEmpty { null }
        val url = s("url") ?: return null
        val link = s("link") ?: return null
        if (!url.startsWith("http") && !url.startsWith("/")) return null
        val filter = (m["filter"] as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
            val key = (k as? String)?.trim()?.ifEmpty { null } ?: return@mapNotNull null
            val value = (v as? String) ?: (v as? Number)?.toString() ?: (v as? Boolean)?.toString() ?: return@mapNotNull null
            key to value
        }?.toMap() ?: emptyMap()
        return Feed(url, s("items") ?: "", filter, link, s("title") ?: "title", s("subtitle"), s("section"),
            s("start"), if (s("startUnit") == "s") "s" else "ms", s("end"), s("live"))
    }

    /** The list address's path parts, for {path:N}. */
    fun pathParts(pageUrl: String): List<String> = try {
        (java.net.URI(pageUrl).rawPath ?: "").split('/').filter { it.isNotEmpty() }
    } catch (_: Exception) { pageUrl.substringAfter("://").substringAfter('/', "").substringBefore('?').split('/').filter { it.isNotEmpty() } }

    /** Fills {path:N} from [parts] and {field} from [event]; a missing value makes the whole result null. */
    fun expand(template: String, parts: List<String>, event: JSONObject? = null): String? {
        // Read by hand, no regex: Android's regex engine refuses an unescaped "}" (it crashed the app at start, 2026-11-20).
        var missing = false
        val out = StringBuilder()
        var i = 0
        while (i < template.length) {
            val open = template.indexOf('{', i)
            val close = if (open < 0) -1 else template.indexOf('}', open + 1)
            if (open < 0 || close < 0) { out.append(template, i, template.length); break }
            out.append(template, i, open)
            val inner = template.substring(open + 1, close)
            i = close + 1
            if (inner.isEmpty() || inner.contains('{')) { out.append(template, open, close + 1); continue }
            val key = inner.substringBefore('|')
            val fallback = inner.substringAfter('|', "").ifEmpty { null }
            val v = (if (key.startsWith("path:")) {
                val n = key.removePrefix("path:").toIntOrNull()
                if (n == null) null else parts.getOrNull(if (n < 0) parts.size + n else n)
            } else event?.let { text(it, key) })?.ifEmpty { null } ?: fallback
            if (v.isNullOrEmpty()) missing = true else out.append(v)
        }
        return if (missing) null else out.toString()
    }

    /** A dotted path's value: an object, array, string, number or boolean; null when absent. */
    fun value(o: Any?, path: String): Any? {
        if (path.isEmpty()) return o
        var cur: Any? = o
        for (part in path.split('.')) {
            cur = when (cur) {
                is JSONObject -> if (cur.has(part) && !cur.isNull(part)) cur.get(part) else null
                is JSONArray -> part.toIntOrNull()?.let { if (it in 0 until cur.length()) cur.get(it) else null }
                else -> null
            }
            if (cur == null) return null
        }
        return cur
    }

    fun text(o: JSONObject, path: String): String? = when (val v = value(o, path)) {
        null -> null
        is String -> v.trim().ifEmpty { null }
        is Number, is Boolean -> v.toString()
        else -> null
    }

    private fun millis(o: JSONObject, path: String?, unit: String): Long? {
        if (path == null) return null
        val v = value(o, path)
        val n = when (v) { is Number -> v.toLong(); is String -> v.trim().toLongOrNull(); else -> null } ?: return null
        if (n <= 0L) return null
        return if (unit == "s") n * 1000L else n
    }

    private fun truthy(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.trim().lowercase() in setOf("true", "live", "1", "yes", "on", "in")
        else -> false
    }

    /** The data file's address for this list page; null when a {path:N} part is missing. */
    fun feedUrl(feed: Feed, pageUrl: String): String? {
        val u = expand(feed.url, pathParts(pageUrl)) ?: return null
        return if (u.startsWith("/")) absolute(pageUrl, u) else u
    }

    private fun absolute(pageUrl: String, link: String): String? = try {
        val abs = java.net.URI(pageUrl).resolve(link.replace(" ", "%20")).toString()
        if (abs.startsWith("http://") || abs.startsWith("https://")) abs else null
    } catch (_: Exception) { null }

    /**
     * The events of one data-file answer as list items, in file order, links made absolute from [pageUrl], one per
     * link. Live: the [Feed.live] field says so, or [now] is between start and end. Never throws (bad answers = empty).
     */
    /** A filter value that matches every event. */
    const val ANY = "*"

    fun items(answer: String, feed: Feed, pageUrl: String, now: Long): List<ListItem> {
        val root: Any = try { org.json.JSONTokener(answer).nextValue() } catch (_: Exception) { return emptyList() }
        val arr = value(root, feed.items) as? JSONArray ?: return emptyList()
        val parts = pathParts(pageUrl)
        val filled = feed.filter.mapNotNull { (k, v) -> expand(v, parts)?.let { k to it.lowercase() } }
        if (filled.size != feed.filter.size) return emptyList() // a filter value couldn't be filled in: don't guess
        val filter = filled.filter { it.second != ANY } // "*" (e.g. "{path:-1|*}" on the site's home page) = no filter
        val out = ArrayList<ListItem>()
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            if (filter.any { (k, v) -> text(e, k)?.lowercase() != v }) continue
            val title = text(e, feed.title) ?: continue
            val rel = expand(feed.link, parts, e) ?: continue
            val url = absolute(pageUrl, rel) ?: continue
            if (!seen.add(url)) continue
            val start = millis(e, feed.start, feed.startUnit)
            val end = millis(e, feed.end, feed.startUnit)
            val live = (feed.live != null && truthy(value(e, feed.live))) ||
                (start != null && end != null && now >= start && now < end)
            out.add(ListItem(
                title = title,
                subtitle = feed.subtitle?.let { text(e, it) } ?: "",
                url = url,
                startMillis = start,
                timeText = if (live) "LIVE" else "",
                section = feed.section?.let { text(e, it) } ?: "",
                liveHint = live,
                imageUrl = null,
            ))
        }
        return out
    }
}
