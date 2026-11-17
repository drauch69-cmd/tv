package com.streamlink.tv

/** What assets/source-switch.js found next to the player: a row of alternate-stream buttons. */
data class Switcher(
    val count: Int,
    /** Index of the link that is showing now, or -1 when the page does not show which. */
    val active: Int,
    val labels: List<String>,
    val xs: List<Float>,
    val ys: List<Float>,
    val iw: Float,
)

/** Pure helpers for the alternate-links phase of [StreamSniffer] (kept apart so they run on the JVM). */
object LinkOrder {
    const val MAX_LINKS = 8
    private const val MAX_LABEL = 24

    /** Parses the answer of source-switch.js "find"; null when there is no usable switcher. */
    internal fun parseFind(raw: String?): Switcher? {
        if (raw == null || raw == "null") return null
        val parsed: Map<*, *>? = try {
            // evaluateJavascript hands back the JSON string quoted once more
            val first = MiniJson.parse(raw)
            (if (first is String) MiniJson.parse(first) else first) as? Map<*, *>
        } catch (_: Exception) {
            null
        }
        val o = parsed ?: return null
        if (o["found"] != true) return null
        val labels = (o["labels"] as? List<*>)?.map { cleanLabel(it as? String ?: "") } ?: return null
        val n = labels.size
        if (n < 2 || n > MAX_LINKS) return null
        fun nums(k: String) = (o[k] as? List<*>)?.map { (it as? Double)?.toFloat() ?: 0f } ?: List(n) { 0f }
        val xs = nums("xs").let { if (it.size == n) it else List(n) { 0f } }
        val ys = nums("ys").let { if (it.size == n) it else List(n) { 0f } }
        val active = ((o["active"] as? Double)?.toInt() ?: -1).let { if (it in 0 until n) it else -1 }
        return Switcher(n, active, labels, xs, ys, (o["iw"] as? Double)?.toFloat() ?: 0f)
    }

    /** Log line for a "find" answer with no usable row of links: whether the player box was seen, and the page's reason. */
    internal fun noLinksLine(raw: String?): String {
        val o: Map<*, *>? = try {
            val first = MiniJson.parse(raw ?: "null")
            (if (first is String) MiniJson.parse(first) else first) as? Map<*, *>
        } catch (_: Exception) {
            null
        }
        if (o == null) return "no link buttons found next to the player (player box: unknown; no answer from the page)"
        val why = o["why"] as? String ?: ""
        val box = if (why == "no player box") "no" else "yes"
        val reason = when {
            o["found"] == true -> "${(o["labels"] as? List<*>)?.size ?: 0} items, need 2 to $MAX_LINKS"
            why.isNotEmpty() -> why
            else -> "no reason given"
        }
        return "no link buttons found next to the player (player box: $box; $reason)"
    }

    /** Text from the page for display only: whitespace collapsed, shortened. */
    fun cleanLabel(raw: String): String {
        val t = raw.replace(Regex("\\s+"), " ").trim()
        return if (t.length <= MAX_LABEL) t else t.substring(0, MAX_LABEL - 1).trimEnd() + "…"
    }

    /** "<link label> · adaptive"; without a link label the host is shown, as before. */
    fun sourceLabel(linkLabel: String, host: String, kind: String, responded: Boolean): String =
        (if (linkLabel.isNotBlank()) linkLabel else host) + " · " + kind + (if (responded) "" else " · didn't respond")

    private fun hostOf(url: String): String? = try { java.net.URI(url).host?.lowercase() } catch (_: Exception) { null }

    private fun noFragment(url: String) = url.substringBefore('#')

    /** A frame URL worth recording as a link's player: a real page of a non-ad host, other than [previous]. */
    fun isNewPlayerFrame(url: String, previous: String): Boolean {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return false
        val host = hostOf(url) ?: return false
        if (AdBlock.isBlockedHost(host)) return false
        return previous.isEmpty() || noFragment(url) != noFragment(previous)
    }

    /** Players of the alternate links still worth opening on their own: no ad hosts, no repeats, not the default's own frame. */
    fun playersToOpen(defaultPlayer: String, players: List<Pair<String, String>>, max: Int = MAX_LINKS): List<Pair<String, String>> {
        val seen = HashSet<String>()
        if (defaultPlayer.isNotEmpty()) seen.add(noFragment(defaultPlayer))
        val out = ArrayList<Pair<String, String>>()
        for (p in players) {
            if (out.size >= max) break
            if (!isNewPlayerFrame(p.second, "")) continue
            if (!seen.add(noFragment(p.second))) continue
            out.add(p)
        }
        return out
    }

    /** The next link still to try (lowest index not tried yet), or null when all were. */
    fun nextToTry(count: Int, tried: Set<Int>): Int? = (0 until count).firstOrNull { it !in tried }

    /** Keeps the ranking, except that the first (default) link's stream goes first when it is good. */
    fun <T> defaultFirst(ranked: List<T>, isGoodDefault: (T) -> Boolean): List<T> {
        val i = ranked.indexOfFirst(isGoodDefault)
        if (i <= 0) return ranked
        return listOf(ranked[i]) + ranked.filterIndexed { j, _ -> j != i }
    }

    /** Two streams from the same link get " (2)", " (3)" so the Source list stays readable. */
    fun uniqueLabels(labels: List<String>): List<String> {
        val seen = HashMap<String, Int>()
        val total = labels.groupingBy { it }.eachCount()
        return labels.map { l ->
            if ((total[l] ?: 0) < 2) l else {
                val n = (seen[l] ?: 0) + 1
                seen[l] = n
                if (n == 1) l else "$l ($n)"
            }
        }
    }

    /**
     * Adds sources found by the background link run to the ones already there: the stream the player has
     * (same URL, or same host and path; or the run's own copy of the default link) is dropped, the rest is
     * appended after the existing ones, so the playing source's index never changes.
     */
    fun mergeExtra(existing: List<Source>, extra: List<Source>, max: Int = 8): List<Source> {
        val seen = HashSet<String>()
        existing.forEach { seen.add(it.url); seen.add(hostPath(it.url)) }
        val out = ArrayList(existing)
        for (e in extra) {
            if (out.size >= max) break
            if (e.isDefaultLink || e.url in seen || hostPath(e.url) in seen) continue
            seen.add(e.url); seen.add(hostPath(e.url))
            out.add(e.copy(isDefaultLink = false))
        }
        return out
    }

    private fun hostPath(url: String): String {
        val u = url.substringBefore('#').substringBefore('?')
        return u.substringAfter("://").lowercase()
    }

    enum class Gate { START, WAIT, GIVE_UP }

    const val LINKS_FIRST_CHECK_MS = 10_000L
    const val LINKS_RECHECK_MS = 3_000L
    const val LINKS_GIVE_UP_MS = 90_000L
    const val LINKS_QUIET_MS = 10_000L

    /**
     * Background run gate: [sinceStartMs] since "Playback started", [sinceStallMs] since the last stall
     * (null = none). Starts once playback is 10 s old and has had no stall for 10 s; buffer level is irrelevant.
     */
    fun gate(sinceStartMs: Long, sinceStallMs: Long?): Gate = when {
        sinceStartMs >= LINKS_FIRST_CHECK_MS && (sinceStallMs == null || sinceStallMs >= LINKS_QUIET_MS) -> Gate.START
        sinceStartMs >= LINKS_GIVE_UP_MS -> Gate.GIVE_UP
        else -> Gate.WAIT
    }

    /** Only a stream that came from a web page, and that has no alternatives yet, gets the background run. */
    fun wantsLinkRun(pageUrl: String, sourceCount: Int): Boolean =
        (pageUrl.startsWith("http://") || pageUrl.startsWith("https://")) && sourceCount < 2
}
