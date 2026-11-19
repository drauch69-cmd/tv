package com.streamlink.tv

/**
 * Automatic move to another source when a stream goes down. In-memory only; the rules are pure and take the time
 * as a parameter so they run on the JVM.
 *
 * - At most [MAX_SWITCHES] automatic switches per "watch" (a watch starts when the user starts an item or link:
 *   [reset]; the automatic switches themselves never reset it).
 * - A page or source that went down is remembered as dead for [DEAD_MS]; a dead one is never picked.
 * - Order: playable (verified) sources of the page, then "open page" entries in list order (this site's numbered
 *   addresses first, then other sites, as the Source list orders them).
 */
object AutoSwitch {
    const val MAX_SWITCHES = 3
    const val DEAD_MS = 5 * 60_000L

    private val lock = Any()
    private var used = 0
    private val dead = HashMap<String, Long>()
    private var pending: List<Source> = emptyList()

    private fun key(url: String) = GameFolders.urlKey(url.substringBefore('#'))

    /** A new watch: the user started something. Dead pages are kept (they expire by time). */
    fun reset() = synchronized(lock) { used = 0; pending = emptyList() }

    fun markDead(url: String?, now: Long) {
        if (url.isNullOrBlank()) return
        synchronized(lock) { dead[key(url)] = now }
    }

    fun isDead(url: String, now: Long): Boolean = synchronized(lock) {
        val t = dead[key(url)] ?: return false
        if (now - t >= DEAD_MS) { dead.remove(key(url)); false } else true
    }

    fun canSwitch(): Boolean = synchronized(lock) { used < MAX_SWITCHES }
    fun switchesUsed(): Int = synchronized(lock) { used }

    /** Indexes of [sources] to try, in order: not the current one, not dead; playable first, then open-page entries. */
    fun order(sources: List<Source>, current: Int, now: Long): List<Int> {
        val ok = sources.indices.filter { it != current && !isDead(sources[it].url, now) }
        val playable = ok.filter { !sources[it].openPage && sources[it].verified }
        val pages = ok.filter { sources[it].openPage }
        return playable + pages
    }

    /** Records one automatic switch made by the player, and the candidates that remain for the loading screen to continue with. */
    fun begin(remaining: List<Source>) = synchronized(lock) { used++; pending = remaining }

    /**
     * The loading screen found no stream on an automatic page: the next remembered candidate that is not dead, counted
     * as one more switch; null when none is left or the limit is reached. Dead ones are skipped and dropped.
     */
    fun takeNext(now: Long): Source? = synchronized(lock) {
        if (used >= MAX_SWITCHES) return null
        while (pending.isNotEmpty()) {
            val c = pending.first()
            pending = pending.drop(1)
            val t = dead[key(c.url)]
            if (t == null || now - t >= DEAD_MS) { used++; return c }
        }
        null
    }

    internal fun resetAllForTest() = synchronized(lock) { used = 0; dead.clear(); pending = emptyList() }
}
