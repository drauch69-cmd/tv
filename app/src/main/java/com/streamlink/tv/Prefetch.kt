package com.streamlink.tv

/**
 * "Head start": the one stream result found in the background while an event had focus on the home or
 * list screen. Stream links carry expiring tokens, so a result is only handed out while it is young.
 * No Android classes here; unit tested.
 */
object Prefetch {
    private class Entry(val url: String, val set: StreamSet, val foundAt: Long)

    private var entry: Entry? = null

    /** Keeps [set] as the one prefetched result (replacing any earlier one). Empty results are not kept. */
    @Synchronized
    fun put(url: String, set: StreamSet, now: Long = System.currentTimeMillis()) {
        entry = if (set.sources.isEmpty()) null else Entry(url, set, now)
    }

    /** The prefetched result for [url], removed from here, if it is younger than 60 s; else null. */
    @Synchronized
    fun take(url: String, now: Long = System.currentTimeMillis()): StreamSet? {
        val e = entry ?: return null
        if (e.url != url) return null
        entry = null
        return if (PrefetchLogic.fresh(e.foundAt, now)) e.set else null
    }

    /** True when a fresh result for [url] is already held (so there is nothing to look for). */
    @Synchronized
    fun has(url: String, now: Long = System.currentTimeMillis()): Boolean {
        val e = entry ?: return false
        return e.url == url && PrefetchLogic.fresh(e.foundAt, now)
    }

    @Synchronized
    fun clear() {
        entry = null
    }

    private val misses = MissLog()

    /** Remember that looking for [url] found nothing, so it is not looked for again right away. */
    fun noteMiss(url: String, now: Long = System.currentTimeMillis()) = misses.record(url, now)

    fun recentlyMissed(url: String, now: Long = System.currentTimeMillis()): Boolean = misses.recent(url, now)
}

/** Rules for when to look ahead. Pure. */
object PrefetchLogic {
    /** How long an event must keep focus before its stream is looked for. */
    const val FOCUS_DELAY_MS = 2_000L
    /** A result older than this is never used (the links expire). */
    const val FRESH_MS = 60_000L
    /** After finding nothing for an event, do not look again for this long. */
    const val MISS_BACKOFF_MS = 45_000L

    /** Younger than [FRESH_MS]; a result from the future (clock changed) is not trusted either. */
    fun fresh(foundAt: Long, now: Long): Boolean = now - foundAt in 0 until FRESH_MS

    /** Only events likely to have a stream right now: live, or starting within 30 minutes. */
    fun statusWorthLooking(status: String): Boolean = status == ListStatus.LIVE || status == ListStatus.SOON

    /**
     * [screenMode] is the Session.mode this screen shows while it is in front ("idle" for the home
     * screen, "list" for a list); anything else means something is playing, being found or waited for.
     */
    fun eligible(status: String, sessionMode: String, screenMode: String, enabled: Boolean, screenActive: Boolean): Boolean =
        canRun(sessionMode, screenMode, enabled, screenActive) && statusWorthLooking(status)

    /** Everything about eligibility except the event itself. */
    fun canRun(sessionMode: String, screenMode: String, enabled: Boolean, screenActive: Boolean): Boolean =
        enabled && screenActive && sessionMode == screenMode
}

/** Which event links recently gave nothing. Small and bounded. */
class MissLog(private val backoffMs: Long = PrefetchLogic.MISS_BACKOFF_MS, private val max: Int = 40) {
    private val at = LinkedHashMap<String, Long>()

    @Synchronized
    fun record(url: String, now: Long) {
        at.remove(url)
        at[url] = now
        while (at.size > max) at.remove(at.keys.first())
    }

    @Synchronized
    fun recent(url: String, now: Long): Boolean {
        val t = at[url] ?: return false
        return now - t in 0 until backoffMs
    }
}
