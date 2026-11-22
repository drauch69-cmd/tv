package com.streamlink.tv

/**
 * Sites (lab, 2026-11-22): saved lists on the same domain are one site. A site is its host without "www."; the lists
 * under it keep their own path, name, rank and sports. When a site moves to a new domain (SiteMoves) every list under
 * it follows at once. Pure, unit tested (java.net only).
 */
object SiteKey {
    /**
     * Endings tried when a site stops answering, most common for these sites first. The site's own ending is skipped.
     * Kept short: each one is a quick connection check.
     */
    val ENDINGS = listOf(
        "to", "mx", "is", "su", "me", "cc", "tv", "live", "top", "st", "sx", "ws", "io", "gg", "li", "ac", "net",
        "org", "com", "link", "xyz", "online", "site", "pro", "info", "club", "fun",
    )

    /** Two-part endings, so "site.co.uk" names "site", not "co". */
    private val TWO_PART = setOf("co.uk", "com.br", "com.ar", "com.mx", "com.au", "co.in", "co.za", "com.co")

    /** A name this short is too generic to match sites by (e.g. "tv", "api"). */
    const val MIN_NAME = 5

    /** The site a host belongs to: lowercase, no "www.", no trailing dot. Null when empty. */
    fun of(host: String?): String? =
        host?.trim()?.lowercase()?.trimEnd('.')?.removePrefix("www.")?.ifEmpty { null }

    /** The site of a web address, or null. */
    fun ofUrl(url: String?): String? = of(hostOf(url))

    fun hostOf(url: String?): String? = try { url?.let { java.net.URI(it.trim()).host } } catch (_: Exception) { null }

    /** The ending of [site], e.g. "to" or "co.uk". Empty when there is none. */
    fun ending(site: String): String {
        val parts = site.split('.')
        if (parts.size < 2) return ""
        val two = parts.takeLast(2).joinToString(".")
        return if (parts.size >= 3 && two in TWO_PART) two else parts.last()
    }

    /** The site's name without its ending and subdomains: "api.reedstreams.link" -> "reedstreams". Null for an IP or one word. */
    fun name(site: String?): String? {
        val s = of(site) ?: return null
        if (s.all { it.isDigit() || it == '.' || it == ':' }) return null
        val end = ending(s)
        if (end.isEmpty()) return null
        val rest = s.removeSuffix(".$end")
        return rest.substringAfterLast('.').ifEmpty { null }
    }

    /** Same site, or the same name on another ending (and the name is long enough to mean something). */
    fun sameName(a: String?, b: String?): Boolean {
        val na = name(a) ?: return false
        return na.length >= MIN_NAME && na == name(b)
    }

    /** Hosts to try when [site] stops answering: its name on every other ending, in [ENDINGS] order. */
    fun candidates(site: String): List<String> {
        val n = name(site) ?: return emptyList()
        if (n.length < MIN_NAME) return emptyList()
        val own = ending(of(site) ?: return emptyList())
        return ENDINGS.filter { it != own }.map { "$n.$it" }
    }

    /** [url] with its host replaced by [newHost] (scheme, path, query and fragment kept). Null if [url] can't be read. */
    fun withHost(url: String, newHost: String): String? = try {
        val u = java.net.URI(url.trim())
        val scheme = u.scheme ?: return null
        if (u.host == null) return null
        val path = u.rawPath?.ifEmpty { null } ?: "/"
        val q = u.rawQuery?.let { "?$it" } ?: ""
        val f = u.rawFragment?.let { "#$it" } ?: ""
        val port = if (u.port > 0) ":${u.port}" else ""
        "$scheme://$newHost$port$path$q$f"
    } catch (_: Exception) { null }
}

/** Moving every list of one site to a new domain. Pure; SavedLists.moveSite saves the result. */
object SiteMove {
    /** The outcome: the lists after the move, and how many moved. */
    data class Result(val lists: List<SavedList>, val moved: Int)

    /**
     * Gives every list on [oldSite] the host [newHost], keeping path, rank and sports. A name that was just the old
     * host follows the new one. A list whose new address another list already has stays as it was.
     */
    fun apply(lists: List<SavedList>, oldSite: String, newHost: String): Result {
        val old = SiteKey.of(oldSite) ?: return Result(lists, 0)
        val taken = lists.map { it.url }.toHashSet()
        var moved = 0
        val out = lists.map { l ->
            val host = SiteKey.hostOf(l.url)
            if (SiteKey.of(host) != old) return@map l
            val url = SiteKey.withHost(l.url, newHost) ?: return@map l
            if (url == l.url || url in taken) return@map l
            taken.add(url)
            moved++
            val name = if (host != null && (l.name == host || l.name == old)) newHost.removePrefix("www.") else l.name
            l.copy(url = url, name = name)
        }
        return Result(if (moved == 0) lists else out, moved)
    }

    /** Lists grouped by site, groups in the order of their best list ([ordered] is already best first). */
    fun groups(ordered: List<SavedList>): List<Pair<String, List<SavedList>>> {
        val m = LinkedHashMap<String, MutableList<SavedList>>()
        for (l in ordered) m.getOrPut(SiteKey.ofUrl(l.url) ?: l.url) { ArrayList() }.add(l)
        return m.map { it.key to it.value }
    }
}
