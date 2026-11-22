package com.streamlink.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A running count of playback failures over the last 7 days (lab, 2026-11-22), by failure name, page site and stream
 * server, printed at the top of every "What happened?" report so one report shows the pattern, not just one game.
 * Kept in a small settings file (at most [MAX_EVENTS] events). The counting is pure and unit tested.
 */
object FailureTally {
    private const val PREFS = "failure_tally"
    private const val KEY = "events_1"
    const val WINDOW_MS = 7 * 24 * 60 * 60_000L
    const val MAX_EVENTS = 400
    private const val TOP = 5

    data class Event(val at: Long, val name: String, val site: String, val server: String)

    @Volatile private var app: Context? = null
    @Volatile private var events: List<Event> = emptyList()
    @Volatile private var loaded = false

    /** Call once at app start; the saved events are read the first time they are needed. */
    fun init(ctx: Context) { app = ctx.applicationContext }

    private fun ensureLoaded(ctx: Context) {
        if (!loaded) synchronized(this) {
            if (!loaded) { events = load(ctx); loaded = true }
        }
    }

    /** One failure while finding or playing a stream. Any thread. */
    fun note(f: Failure, pageUrl: String?, streamUrl: String?) {
        val ctx = app ?: return
        ensureLoaded(ctx)
        val e = Event(System.currentTimeMillis(), f.label, SiteKey.ofUrl(pageUrl) ?: "", SiteKey.ofUrl(streamUrl) ?: "")
        synchronized(this) {
            events = trim(events + e, e.at)
            save(ctx, events)
        }
    }

    /** Report lines (empty when nothing failed in the last 7 days). Any thread. */
    fun reportLines(now: Long = System.currentTimeMillis()): List<String> {
        val ctx = app ?: return emptyList()
        ensureLoaded(ctx)
        return lines(events, now)
    }

    // ---- pure ----

    internal fun trim(all: List<Event>, now: Long): List<Event> =
        all.filter { now - it.at in 0..WINDOW_MS }.takeLast(MAX_EVENTS)

    internal fun lines(all: List<Event>, now: Long): List<String> {
        val recent = trim(all, now)
        if (recent.isEmpty()) return emptyList()
        fun top(by: (Event) -> String): String = recent.map(by).filter { it.isNotEmpty() }
            .groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(TOP).joinToString(", ") { "${it.key} ×${it.value}" }
        val out = ArrayList<String>()
        out.add("Playback failures, last 7 days: ${recent.size}")
        top { it.name }.takeIf { it.isNotEmpty() }?.let { out.add("  by kind: $it") }
        top { it.site }.takeIf { it.isNotEmpty() }?.let { out.add("  by site: $it") }
        top { it.server }.takeIf { it.isNotEmpty() }?.let { out.add("  by stream server: $it") }
        return out
    }

    private fun load(ctx: Context): List<Event> = try {
        val a = JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { Event(it.optLong("t"), it.optString("n"), it.optString("s"), it.optString("v")) }
        }
    } catch (_: Exception) { emptyList() }

    private fun save(ctx: Context, all: List<Event>) {
        val a = JSONArray()
        all.forEach { a.put(JSONObject().put("t", it.at).put("n", it.name).put("s", it.site).put("v", it.server)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }
}
