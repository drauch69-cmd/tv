package com.streamlink.tv

/**
 * The page's own numbered source addresses (assets/page-sources.js): the Source list offers them as "open page"
 * entries. Pure helpers, so they run on the JVM.
 */
object PageSources {
    const val MAX = 8

    class Found(val url: String, val label: String, val index: Int)

    /** Parses the answer of page-sources.js "read". Null while the script is not done (or the answer is unusable). */
    internal fun parse(raw: String?): List<Found>? {
        if (raw == null || raw == "null") return null
        val parsed: Map<*, *>? = try {
            val first = MiniJson.parse(raw)
            (if (first is String) MiniJson.parse(first) else first) as? Map<*, *>
        } catch (_: Exception) {
            null
        }
        val o = parsed ?: return null
        if (o["done"] != true) return null
        val list = o["urls"] as? List<*> ?: return emptyList()
        return list.mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            val url = m["url"] as? String ?: return@mapNotNull null
            if (!(url.startsWith("http://") || url.startsWith("https://"))) return@mapNotNull null
            Found(url, (m["label"] as? String).orEmpty(), (m["index"] as? Double)?.toInt() ?: 0)
        }
    }

    private fun key(url: String) = GameFolders.urlKey(url.substringBefore('#'))

    /**
     * "open page" Source entries for [found]: never the playing page itself, an address once, at most [MAX], labelled
     * "<label> · this site" and claiming [pageUrl] as referer.
     */
    fun toSources(pageUrl: String, found: List<Found>, existingUrls: Set<String> = emptySet()): List<Source> {
        val seen = HashSet<String>()
        seen.add(key(pageUrl))
        existingUrls.forEach { seen.add(key(it)) }
        val out = ArrayList<Source>()
        for (f in found) {
            if (out.size >= MAX) break
            if (!seen.add(key(f.url))) continue
            val base = f.label.trim().ifEmpty { "Source ${out.size + 1}" }
            out.add(Source(f.url, emptyMap(), listOf(pageUrl), "$base · this site", isDefaultLink = false, verified = false,
                note = "opens as its own page", playerUrl = f.url, openPage = true))
        }
        return out
    }
}
