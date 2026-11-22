package com.streamlink.tv

import android.content.Context
import org.json.JSONObject

/**
 * Remembers, per site, whether the stream finder found anything there recently. After
 * [FAILS_TO_SHORTEN] misses in a row the finder only looks for [SHORT_TIMEOUT_MS] on that site and then
 * falls back to the site's own player, instead of 25 s. One success clears it; entries expire after a week.
 * The site's own player also clears it when the page there loads a playlist the finder could have
 * caught (the miss was the finder's, not a site the app can't read), see [clear].
 * Every [FULL_EVERY]th miss in a row gets a full search again, so a site whose page now needs longer than
 * the short search can't stay stuck (ntv sat at 25 misses, 2026-11-21).
 * Only host names and counts are stored.
 */
object SiteMemory {
    /**
     * Off for now (user, 2026-11-21): a failed site should never get a shorter search while testing.
     * Misses are still counted and shown in the report. Set true to bring the short search back.
     */
    const val SHORTEN_ENABLED = false
    const val FAILS_TO_SHORTEN = 2
    const val SHORT_TIMEOUT_MS = 15_000L // 10 s left only ~8 s after the page load and first tap
    const val FULL_EVERY = 4
    const val EXPIRE_MS = 7 * 24 * 60 * 60 * 1000L
    private const val PREFS = "streamlink"
    // "_2": forgets misses recorded while builds 153–156 gave up early on other sites' players.
    internal const val KEY = "site_memory_2"
    private const val MAX_SITES = 80

    data class Entry(val fails: Int, val at: Long)

    // ---- pure, unit tested ----

    internal fun shorten(e: Entry?, now: Long): Boolean =
        e != null && e.fails >= FAILS_TO_SHORTEN && now - e.at < EXPIRE_MS && e.fails % FULL_EVERY != 0

    internal fun afterResult(e: Entry?, found: Boolean, now: Long): Entry? =
        if (found) null else Entry(if (e == null || now - e.at >= EXPIRE_MS) 1 else e.fails + 1, now)

    /**
     * What a finished search tells the site memory: true = found (clears misses), false = one miss, null = nothing (not a finder failure).
     * A miss only for a primary search that saw no candidate at all and whose stream was not offline; never from light or reconnect searches.
     */
    internal fun outcome(light: Boolean, reconnect: Boolean, found: Boolean, candidateSeen: Boolean, offline: Boolean): Boolean? = when {
        light || reconnect -> null
        found -> true
        candidateSeen || offline -> null
        else -> false
    }

    internal fun key(host: String?): String? =
        host?.trim()?.lowercase()?.removePrefix("www.")?.ifEmpty { null }

    // ---- storage ----

    fun shouldShorten(ctx: Context, host: String?): Boolean {
        if (!SHORTEN_ENABLED) return false
        val k = key(host) ?: return false
        return shorten(load(ctx)[k], System.currentTimeMillis())
    }

    /** Forget the misses for [host]: the stream there turned out to be one the finder can catch. */
    fun clear(ctx: Context, host: String?) = record(ctx, host, found = true)

    fun fails(ctx: Context, host: String?): Int = key(host)?.let { load(ctx)[it]?.fails } ?: 0

    @Synchronized
    fun record(ctx: Context, host: String?, found: Boolean) {
        val k = key(host) ?: return
        val all = LinkedHashMap(load(ctx))
        val next = afterResult(all[k], found, System.currentTimeMillis())
        if (next == all[k]) return
        all.remove(k)
        if (next != null) all[k] = next
        while (all.size > MAX_SITES) all.remove(all.keys.first())
        val o = JSONObject()
        for ((h, e) in all) o.put(h, JSONObject().put("fails", e.fails).put("at", e.at))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }

    private fun load(ctx: Context): Map<String, Entry> {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyMap()
        return try {
            val o = JSONObject(s)
            val out = LinkedHashMap<String, Entry>()
            for (k in o.keys()) o.optJSONObject(k)?.let { out[k] = Entry(it.optInt("fails"), it.optLong("at")) }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
