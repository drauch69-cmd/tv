package com.streamlink.tv

import java.util.concurrent.ConcurrentHashMap

/**
 * Pure rules (no Android types, unit tested in RefusalTest) for streams whose video pieces are REFUSED by their server
 * (HTTP 401/403/451), as opposed to pieces that time out or get no answer. A refusal is a decision by the server about who
 * may ask; another network engine, a restart of the engine, or the clean browser player does not change it.
 */
object Refusal {
    /** "Not allowed" answers. 404/410 (gone), 5xx and no answer at all are something else. */
    fun isRefusal(status: Int?) = status == 401 || status == 403 || status == 451

    /** Appended to a check's note when every referer got an HTTP refusal (see [isRefusedSource]). */
    const val REFUSED_MARK = "; refused on every referer"
    fun isRefusedSource(s: Source): Boolean = !s.verified && !s.openPage && s.note.endsWith(REFUSED_MARK)
    const val REFUSED_LINE = "The stream server refuses this stream (server said 403 to every referer); not playing it"

    /** The HTTP status that best explains a failed check: a refusal if any try was refused, else the last HTTP status, else 0. */
    fun statusOf(answers: List<Int>): Int = answers.firstOrNull { isRefusal(it) } ?: answers.lastOrNull { it != 0 } ?: 0

    /** Every try of a check got an HTTP answer and every answer was a refusal (a try that threw or timed out means it is not a clear refusal). */
    fun allRefused(answers: List<Int>) = answers.isNotEmpty() && answers.all { isRefusal(it) }

    /**
     * Whether the player should go on trying the source's other referers after a refusal before the first picture.
     * Not when the refusing host is a piece host (not the playlist's host) that the finder / an earlier source already saw refuse
     * every referer: the remaining referers would be refused again (each costs a few seconds).
     */
    fun cycleReferers(hasMoreReferers: Boolean, pieceHostKnownRefused: Boolean): Boolean = hasMoreReferers && !pieceHostKnownRefused

    /** The clean browser player fetches the same refused pieces with the same headers: pointless when the pieces were refused. */
    fun cleanPlayerCanHelp(piecesRefused: Boolean) = !piecesRefused

    /** A page reload handed back only the stream (same address ignoring the query) whose pieces were refused. */
    fun sameRefusedStream(refusedUrl: String?, freshUrls: List<String>): Boolean =
        refusedUrl != null && freshUrls.isNotEmpty() && freshUrls.all { DeadStream.samePath(it, refusedUrl) }
}

/**
 * Piece hosts that refused every referer in a stream check (the finder's check of a possible playlist whose address turned out
 * to be a video piece, or any candidate), kept in memory for [TTL_MS]. The player uses it as corroboration: when that host's
 * pieces are refused in the player too, it stops at once instead of cycling the referers and reloading the page.
 */
object RefusedHosts {
    const val TTL_MS = 10 * 60 * 1000L
    private val map = ConcurrentHashMap<String, Long>()

    fun record(host: String?, now: Long = System.currentTimeMillis()) {
        val k = ServerMemory.key(host) ?: return
        map[k] = now
    }

    fun isRefused(host: String?, now: Long = System.currentTimeMillis()): Boolean {
        val k = ServerMemory.key(host) ?: return false
        val at = map[k] ?: return false
        if (now - at !in 0..TTL_MS) { map.remove(k); return false }
        return true
    }

    fun clear() = map.clear()
}

/**
 * Which referers may be REMEMBERED for a stream server ([ServerMemory.recordReferer]). By structure: the referer's host is the
 * page the search opened (or where it ended up, or the page that sent the user there) or the player frame the sniffer's nudge
 * picked (the biggest landscape frame on the page). The Referer that a request carried on its own (its initiator, often a tiny
 * tracker/pixel frame that is not a player) and any other sub-frame address are NOT: some servers answer any referer, so the first
 * one tried would otherwise be remembered even though it is meaningless.
 */
object RefererTrust {
    fun sameSite(a: String, b: String): Boolean {
        val x = a.lowercase().removePrefix("www.")
        val y = b.lowercase().removePrefix("www.")
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || x.endsWith(".$y") || y.endsWith(".$x")
    }

    /** [trustedHosts]: hosts of the page and of the player frame (blank entries ignored). */
    fun eligible(referer: String, trustedHosts: Collection<String>): Boolean {
        val host = Headers.originOf(referer)?.substringAfter("://")?.substringBefore(':') ?: return false
        return trustedHosts.any { it.isNotBlank() && sameSite(host, it) }
    }

    /**
     * [referers] with the ones from the page or player frame ([trustedHosts]) moved to the front, each group in its
     * original order. Ad/tracker frames on a page can otherwise fill every try before the page itself is reached.
     */
    fun trustedFirst(referers: List<String>, trustedHosts: Collection<String>): List<String> {
        val (mine, other) = referers.partition { eligible(it, trustedHosts) }
        return mine + other
    }

    /** The referers of a candidate that may be remembered. */
    fun eligibleReferers(referers: List<String>, trustedHosts: Collection<String>): List<String> =
        referers.filter { it.isNotEmpty() && eligible(it, trustedHosts) }
}
