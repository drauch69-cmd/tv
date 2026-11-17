package com.streamlink.tv

import android.content.Context
import org.json.JSONObject

/**
 * Remembers, per site, whether the stream finder found anything there recently. After
 * [FAILS_TO_SHORTEN] misses in a row the finder only looks for [SHORT_TIMEOUT_MS] on that site and then
 * falls back to the site's own player, instead of 25 s. One success clears it; entries expire after a week.
 * Only host names and counts are stored.
 */
object SiteMemory {
    const val FAILS_TO_SHORTEN = 2
    const val SHORT_TIMEOUT_MS = 10_000L
    const val EXPIRE_MS = 7 * 24 * 60 * 60 * 1000L
    private const val PREFS = "streamlink"
    private const val KEY = "site_memory"
    private const val MAX_SITES = 80

    data class Entry(val fails: Int, val at: Long)

    // ---- pure, unit tested ----

    internal fun shorten(e: Entry?, now: Long): Boolean =
        e != null && e.fails >= FAILS_TO_SHORTEN && now - e.at < EXPIRE_MS

    internal fun afterResult(e: Entry?, found: Boolean, now: Long): Entry? =
        if (found) null else Entry(if (e == null || now - e.at >= EXPIRE_MS) 1 else e.fails + 1, now)

    internal fun key(host: String?): String? =
        host?.trim()?.lowercase()?.removePrefix("www.")?.ifEmpty { null }

    // ---- storage ----

    fun shouldShorten(ctx: Context, host: String?): Boolean {
        val k = key(host) ?: return false
        return shorten(load(ctx)[k], System.currentTimeMillis())
    }

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
