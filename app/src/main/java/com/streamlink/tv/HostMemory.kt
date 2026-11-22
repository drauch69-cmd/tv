package com.streamlink.tv

import android.content.Context
import org.json.JSONObject

/**
 * Carries what the app learned about a site over to its new domain when the site moves (SiteMoves): misses
 * ([SiteMemory]), stream-page habits ([SiteHabits]) and list row shapes ([LearnedShapes]). Each is a JSON object in
 * the main settings file keyed by host without "www.". An entry already there for the new host is kept.
 */
object HostMemory {
    private const val PREFS = "streamlink"
    private val KEYS = listOf(SiteMemory.KEY, SiteHabits.KEY, LearnedShapes.KEY)

    /** Pure: [json] with key [from] renamed to [to] (unless [to] exists). Same text when nothing changes. */
    internal fun renamed(json: String?, from: String, to: String): String? {
        if (json == null || from == to) return json
        return try {
            val o = JSONObject(json)
            if (!o.has(from) || o.has(to)) return json
            val v = o.get(from)
            o.remove(from)
            o.put(to, v)
            o.toString()
        } catch (_: Exception) { json }
    }

    @Synchronized
    fun rename(ctx: Context, fromSite: String, toSite: String) {
        val from = SiteKey.of(fromSite) ?: return
        val to = SiteKey.of(toSite) ?: return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ed = p.edit()
        var changed = false
        for (k in KEYS) {
            val cur = p.getString(k, null) ?: continue
            val next = renamed(cur, from, to)
            if (next != null && next != cur) { ed.putString(k, next); changed = true }
        }
        if (changed) ed.apply()
    }
}
