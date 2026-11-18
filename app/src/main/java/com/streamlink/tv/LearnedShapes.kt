package com.streamlink.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-site memory of what one event row looks like (a CSS selector the extractor reported for a
 * real list). Pages of the same site that show a single event have nothing that repeats, so the
 * extractor uses these to find that one row. Only structure is stored, never page text.
 */
object LearnedShapes {
    private const val PREFS = "streamlink"
    internal const val KEY = "list_shapes_v2" // v2: forgets shapes learned by an older reader that picked up junk link groups
    private const val PER_HOST = 4
    private const val MAX_HOSTS = 60

    fun forHost(context: Context, host: String?): List<String> {
        val h = norm(host) ?: return emptyList()
        return load(context)[h] ?: emptyList()
    }

    @Synchronized
    fun remember(context: Context, host: String?, shape: String) {
        val h = norm(host) ?: return
        if (shape.isBlank() || shape.length > 300) return
        val all = LinkedHashMap(load(context))
        val updated = merge(all[h] ?: emptyList(), shape)
        if (updated == all[h]) return
        all.remove(h)
        all[h] = updated // most recently used last
        while (all.size > MAX_HOSTS) all.remove(all.keys.first())
        val o = JSONObject()
        for ((k, v) in all) o.put(k, JSONArray(v))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }

    /** Newest first, no duplicates, at most [PER_HOST]. Pure; unit tested. */
    internal fun merge(list: List<String>, shape: String): List<String> =
        (listOf(shape) + list.filter { it != shape }).take(PER_HOST)

    internal fun norm(host: String?): String? =
        host?.trim()?.lowercase()?.removePrefix("www.")?.trimEnd('.')?.ifEmpty { null }

    private fun load(context: Context): Map<String, List<String>> {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyMap()
        return try {
            val o = JSONObject(text)
            val out = LinkedHashMap<String, List<String>>()
            for (k in o.keys()) {
                val a = o.optJSONArray(k) ?: continue
                out[k] = (0 until a.length()).mapNotNull { a.optString(it).ifEmpty { null } }
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
