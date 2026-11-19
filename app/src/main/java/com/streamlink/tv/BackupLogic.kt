package com.streamlink.tv

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/*
 * Pure rules for "backups while watching" (see BackupKeeper and PlayerActivity): which other sources of the game to keep
 * fresh, when to refresh them, when to stop, and what to do when the stream drops. No Android classes; unit tested.
 */

enum class BackupKind {
    /** Another page of the same game (numbered address or other site): needs a hidden-browser look to get a fresh stream address. */
    PAGE,
    /** Another playable source of the page being watched: a quick playlist check is all it needs. */
    STREAM,
}

/** The keeper's off switch for one watch. A watch ends when the user starts something ([AutoSwitch.reset] calls [reset]); automatic switches keep it. */
object BackupWatch {
    @Volatile var off = false
    fun reset() { off = false }
}

/** A pre-found stream set for a page about to be opened by the loading screen (a ready backup): the screen skips its own search. */
object PreFound {
    const val MAX_AGE_MS = 30_000L
    private class Entry(val url: String, val set: StreamSet, val at: Long)
    private var entry: Entry? = null

    @Synchronized
    fun put(url: String, set: StreamSet, now: Long = System.currentTimeMillis()) {
        entry = if (set.sources.isEmpty()) null else Entry(url, set, now)
    }

    /** The set for [url], removed from here, if it was put less than [MAX_AGE_MS] ago; else null. */
    @Synchronized
    fun take(url: String, now: Long = System.currentTimeMillis()): StreamSet? {
        val e = entry ?: return null
        if (e.url != url) return null
        entry = null
        return if (now - e.at in 0 until MAX_AGE_MS) e.set else null
    }

    @Synchronized
    fun clear() { entry = null }
}

object BackupChoice {
    const val MAX_BACKUPS = 2

    data class Pick(val url: String, val kind: BackupKind, val index: Int)

    /** Registrable-ish site of an address: host without "www.", last two labels (IP addresses and single labels stay whole). */
    fun site(url: String): String {
        val host = Report.host(url).lowercase().removePrefix("www.")
        if (host.isEmpty() || host.all { it.isDigit() || it == '.' || it == ':' }) return host
        val parts = host.split('.')
        return if (parts.size <= 2) host else parts.takeLast(2).joinToString(".")
    }

    /** The address a page-type backup opens: its player page, else its own address. */
    fun target(s: Source): String = s.playerUrl.ifEmpty { s.url }

    /**
     * The backups to keep fresh, best first, at most [max] (1-2). Page entries come first, in Source-list order but
     * with other sites ahead of this site's own numbered addresses (a site-wide failure takes those down too); then, only
     * to fill the slots, other verified playable sources of the page (a different stream server first). [playing] is
     * the index of the source being watched; dead ones ([isDead], by address) are never picked.
     */
    fun pick(sources: List<Source>, playing: Int, pageUrl: String, isDead: (String) -> Boolean, max: Int = MAX_BACKUPS): List<Pick> {
        val pageSite = site(pageUrl)
        val playingHost = sources.getOrNull(playing)?.let { site(it.url) }
        val pages = sources.indices.filter { i ->
            i != playing && sources[i].openPage && !isDead(target(sources[i])) && !isDead(sources[i].url)
        }
        val orderedPages = pages.filter { site(target(sources[it])) != pageSite } + pages.filter { site(target(sources[it])) == pageSite }
        val streams = sources.indices.filter { i ->
            i != playing && !sources[i].openPage && sources[i].verified && !isDead(sources[i].url) &&
                sources[i].url != sources.getOrNull(playing)?.url
        }
        val orderedStreams = streams.filter { site(sources[it].url) != playingHost } + streams.filter { site(sources[it].url) == playingHost }
        val out = ArrayList<Pick>()
        val seen = HashSet<String>()
        for (i in orderedPages) if (out.size < max && seen.add(sources[i].url)) out.add(Pick(sources[i].url, BackupKind.PAGE, i))
        for (i in orderedStreams) if (out.size < max && seen.add(sources[i].url)) out.add(Pick(sources[i].url, BackupKind.STREAM, i))
        return out
    }
}

object BackupTiming {
    const val FIRST_DELAY_MS = 30_000L
    const val LONG_INTERVAL_MS = 10 * 60_000L
    const val MIN_SHORT_INTERVAL_MS = 90_000L
    const val MAX_BACKOFF_MS = 15 * 60_000L
    /** A stall that starts within this long after a refresh run ended is blamed on it. */
    const val STALL_BLAME_MS = 5_000L
    /** Healthy buffer: this much video ahead of the picture. With low delay little is ever buffered, so a calm spell also counts. */
    const val HEALTHY_AHEAD_MS = 10_000L
    const val CALM_MS = 60_000L
    const val CALM_MIN_AHEAD_MS = 3_000L
    /** A run (hidden browser) still going after this long is stopped and counts as finding nothing. */
    const val RUN_LIMIT_MS = 60_000L

    /** [recovering]: a drop is being handled (reconnect, race, renewal, quick switch); [calmMs]: time since the picture started or the last stall. */
    data class Health(val playing: Boolean, val aheadMs: Long, val calmMs: Long, val recovering: Boolean)

    fun healthy(h: Health): Boolean =
        h.playing && !h.recovering && (h.aheadMs >= HEALTHY_AHEAD_MS || (h.calmMs >= CALM_MS && h.aheadMs >= CALM_MIN_AHEAD_MS))

    /** How often to refresh a backup whose server has this address lifetime: short: 2/3 of the oldest age that still worked, at least 90 s; else every 10 min. */
    fun interval(life: ServerMemory.Lifetime): Long =
        if (life.kind == ServerMemory.LifeKind.SHORT) maxOf(MIN_SHORT_INTERVAL_MS, life.maxOkMs * 2 / 3) else LONG_INTERVAL_MS

    /** Wait after [misses] runs in a row that found nothing (1 = the first): the interval doubled each time, at most [MAX_BACKOFF_MS]. */
    fun backoff(base: Long, misses: Int): Long {
        var d = base
        repeat(misses.coerceAtLeast(0)) { d = minOf(MAX_BACKOFF_MS, d * 2) }
        return minOf(MAX_BACKOFF_MS, d)
    }
}

/**
 * When each backup is due, one run at a time. Time is passed in. [stall] is the auto-off: a stall that starts while a run is active
 * or within [BackupTiming.STALL_BLAME_MS] after one ended turns the keeper off for the rest of the watch ([BackupWatch]).
 */
class BackupPacing {
    private class Key(var nextAt: Long, var misses: Int = 0)
    private val keys = LinkedHashMap<String, Key>()
    var active = false
        private set
    private var lastEndAt = Long.MIN_VALUE / 2

    /** Makes [urls] the tracked backups: new ones first run [BackupTiming.FIRST_DELAY_MS] after [now]; dropped ones are forgotten. */
    fun sync(urls: List<String>, now: Long) {
        keys.keys.retainAll(urls.toSet())
        for (u in urls) if (u !in keys) keys[u] = Key(now + BackupTiming.FIRST_DELAY_MS)
    }

    /** The picture (re)started: nothing runs for the first-delay again. */
    fun pictureShown(now: Long) {
        for (k in keys.values) k.nextAt = maxOf(k.nextAt, now + BackupTiming.FIRST_DELAY_MS)
    }

    /** The backup to refresh now, or null: needs a healthy player, no run active, the keeper not off, and the backup due; earliest due first. */
    fun next(now: Long, health: BackupTiming.Health): String? {
        if (BackupWatch.off || active || !BackupTiming.healthy(health)) return null
        return keys.entries.filter { it.value.nextAt <= now }.minByOrNull { it.value.nextAt }?.key
    }

    fun begin() { active = true }

    /** A run ended with a result ([found]) or without; [base] is the refresh interval for this backup (from [BackupTiming.interval]). */
    fun end(url: String, found: Boolean, base: Long, now: Long) {
        active = false
        lastEndAt = now
        val k = keys[url] ?: return
        if (found) { k.misses = 0; k.nextAt = now + base } else { k.misses++; k.nextAt = now + BackupTiming.backoff(base, k.misses) }
    }

    /** A run was cut short by a pause (recovery, stop): no result and no penalty. */
    fun abort() { active = false }

    /** The player stalled at [now]. True when this turned the keeper off for the watch. */
    fun stall(now: Long): Boolean {
        if (BackupWatch.off) return false
        if (active || now - lastEndAt in 0..BackupTiming.STALL_BLAME_MS) {
            BackupWatch.off = true
            active = false
            return true
        }
        return false
    }

    fun nextAtFor(url: String): Long? = keys[url]?.nextAt
    fun missesFor(url: String): Int = keys[url]?.misses ?: 0
    fun clear() { keys.clear(); active = false }
}

object BackupRecovery {
    /** Without anything learned about the server a result is trusted this long. */
    const val UNKNOWN_FRESH_MS = 2 * 60_000L
    const val LONG_FRESH_MS = 30 * 60_000L

    /** Whether a result found [ageMs] ago is still likely valid on a server with this lifetime. */
    fun fresh(ageMs: Long, life: ServerMemory.Lifetime): Boolean = ageMs >= 0 && when (life.kind) {
        ServerMemory.LifeKind.SHORT -> ageMs <= life.maxOkMs
        ServerMemory.LifeKind.LONG -> ageMs < LONG_FRESH_MS
        ServerMemory.LifeKind.UNKNOWN -> ageMs < UNKNOWN_FRESH_MS
    }

    /** What is known about one ready backup. */
    data class Known(val url: String, val ageMs: Long, val life: ServerMemory.Lifetime)

    enum class Plan { SWITCH_FAST, PARALLEL, TODAY }
    data class Decision(val plan: Plan, val url: String? = null)

    /**
     * The drop just happened. A fresh ready backup: switch to it (after a quick check); the freshest of them, ties in list order.
     * Otherwise, if any other address is already known ([ready] backups or [otherPlayable] sources of the page), run the usual
     * reconnect and check those at the same time. Otherwise (or when no automatic switch is left) just today's recovery.
     */
    fun decide(ready: List<Known>, otherPlayable: Int, canSwitch: Boolean): Decision {
        if (!canSwitch) return Decision(Plan.TODAY)
        val fresh = ready.withIndex().filter { fresh(it.value.ageMs, it.value.life) }.minWithOrNull(compareBy({ it.value.ageMs }, { it.index }))
        if (fresh != null) return Decision(Plan.SWITCH_FAST, fresh.value.url)
        if (ready.isNotEmpty() || otherPlayable > 0) return Decision(Plan.PARALLEL)
        return Decision(Plan.TODAY)
    }
}

/**
 * One recovery after a drop: the page reload and the quick checks of known addresses run at once; the first verified result
 * [claim]s the episode and the other is ignored. Id 0 means "no episode" (a reconnect that is not racing): always allowed.
 */
class RecoveryEpisode {
    private var id = 0
    private var next = 1
    private var open = false
    private var claimed = false
    private var raced = false

    val isOpen: Boolean get() = open

    /** Starts an episode and returns its id. */
    fun begin(): Int { id = next++; open = true; claimed = false; raced = false; return id }

    /** The id to carry into callbacks: the open episode's, else 0. */
    fun current(): Int = if (open) id else 0

    /** True the first time it is called in an episode (the race starts once). */
    fun markRaced(): Boolean { if (!open || raced) return false; raced = true; return true }

    /** The first caller with the open episode's id (or 0 when no episode was open) wins; everyone else loses. */
    fun claim(ep: Int): Boolean {
        if (ep == 0) return true
        if (!open || ep != id || claimed) return false
        claimed = true
        return true
    }

    /** The episode is over (a picture is playing, or the player moved on): late results are dropped. */
    fun end() { open = false; id = 0 }
}

/** Quick checks of several addresses at once: the first that passes wins, the rest are ignored. */
object QuickRace {
    /**
     * Runs [check] on every item in its own thread and returns the first item with a non-null answer, or null when all fail or
     * [limitMs] passes. Late answers are dropped. Call off the main thread.
     */
    private val END = Any()

    @Suppress("UNCHECKED_CAST")
    fun <T, R : Any> first(items: List<T>, limitMs: Long, check: (T) -> R?): Pair<T, R>? {
        if (items.isEmpty()) return null
        val queue = LinkedBlockingQueue<Any>()
        val left = AtomicInteger(items.size)
        for (item in items) {
            thread(isDaemon = true, name = "quick-race") {
                val r = try { check(item) } catch (_: Exception) { null }
                if (r != null) queue.offer(item to r)
                else if (left.decrementAndGet() == 0) queue.offer(END)
            }
        }
        val got = try { queue.poll(limitMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null }
        return if (got == null || got === END) null else got as Pair<T, R>
    }
}

/** A definitive "not found" (404/410) from every try: the stream behind the page is offline. Such a source is never played or fallen back to. */
object DeadStream {
    private const val MARK = " (not found)"
    fun isGoneCode(code: Int) = code == 404 || code == 410
    fun goneNote(code: Int) = "server said $code$MARK"
    fun isGone(s: Source): Boolean = !s.verified && s.note.contains(MARK)
    /** The sources minus the gone ones. */
    fun playable(sources: List<Source>): List<Source> = sources.filter { !isGone(it) }
    /** Whether [a] and [b] are the same address ignoring the query (the same dead stream handed back by a page reload). */
    fun samePath(a: String, b: String): Boolean = a.substringBefore('?').substringBefore('#') == b.substringBefore('?').substringBefore('#')
    const val OFFLINE_LINE = "The site's stream for this page is offline (server said 404)"
}
