package com.streamlink.tv

import android.content.Context
import org.json.JSONObject

/**
 * Light "learned habits" per site (host, lower case, no www.). When a primary search ends with a verified stream,
 * the finder keeps what it had to do on the page before the stream appeared: the tap (as a fraction of the
 * hidden browser's size, so it does not depend on the screen), how many taps, which source button (index of count),
 * and how many seconds after the page loaded the stream was seen. The next primary search on that site does the same
 * thing right after the page loads, instead of waiting for its usual timers (the normal search carries on beside it).
 * A habit that stays silent [DROP_AFTER_MISSES] runs in a row is forgotten; entries expire after 14 days; 80 sites, least recently used dropped.
 * Only host names, fractions and small numbers are stored. (Not the same thing as the hand-written list recipes.)
 */
object SiteHabits {
    const val EXPIRE_MS = 14 * 24 * 60 * 60 * 1000L
    const val MAX_SITES = 80
    const val DROP_AFTER_MISSES = 2
    /** A habit counts as missed when no stream is seen within the site's usual seconds plus this. */
    const val EXTRA_WAIT_MS = 5_000L
    /** Gap between the two taps of a habit that needed 2+ taps (the normal search waits 2.5 s). */
    const val SECOND_TAP_MS = 1_000L
    const val KIND_PLAYER = "player"
    const val KIND_PAGE = "page"
    private const val PREFS = "streamlink"
    private const val KEY = "site_habits_1"

    /** [tapKind] is "" when no tap is part of the habit; [srcIndex] is -1 when no source button is. [taps] is 1 or 2 (2 = two or more). */
    data class Habit(
        val tapKind: String,
        val fx: Float,
        val fy: Float,
        val taps: Int,
        val srcIndex: Int,
        val srcCount: Int,
        val secs: Float,
        val misses: Int,
        val at: Long,
    ) {
        val hasTap get() = tapKind.isNotEmpty() && taps > 0
        val hasSource get() = srcIndex >= 0 && srcCount > 0
    }

    // ---- pure, unit tested ----

    internal fun key(host: String?): String? =
        host?.trim()?.lowercase()?.removePrefix("www.")?.ifEmpty { null }

    /** [pos] as a fraction of [size]; null when it cannot be one (no size, or not strictly inside the view). */
    internal fun fraction(pos: Float, size: Int): Float? {
        if (size <= 0) return null
        val f = pos / size
        return if (f > 0f && f < 1f) f else null
    }

    /** A remembered fraction back to pixels for a view of [size]. */
    internal fun toPixels(f: Float, size: Int): Float = f * size

    /**
     * What to remember after a successful run, or null when there is nothing to do on the page to remember
     * (no tap and no source button before the stream appeared). [taps] is the number of taps made before the stream
     * was seen (stored as 1, or 2 for two or more); [x],[y] the last of them in pixels of a [w] x [h] view.
     */
    internal fun fromRun(
        taps: Int, tapKind: String, x: Float, y: Float, w: Int, h: Int,
        srcIndex: Int, srcCount: Int, secs: Float, now: Long,
    ): Habit? {
        val fx = fraction(x, w)
        val fy = fraction(y, h)
        val tapOk = taps > 0 && (tapKind == KIND_PLAYER || tapKind == KIND_PAGE) && fx != null && fy != null
        val srcOk = srcIndex >= 0 && srcCount > 0 && srcIndex < srcCount
        if (!tapOk && !srcOk) return null
        return Habit(
            if (tapOk) tapKind else "",
            if (tapOk) fx!! else 0f,
            if (tapOk) fy!! else 0f,
            if (tapOk) (if (taps >= 2) 2 else 1) else 0,
            if (srcOk) srcIndex else -1,
            if (srcOk) srcCount else 0,
            secs.coerceIn(0f, 120f),
            0,
            now,
        )
    }

    internal fun usable(h: Habit?, now: Long): Boolean = h != null && now - h.at < EXPIRE_MS && (h.hasTap || h.hasSource)

    /** The remembered source button may be pressed only when the page shows the same number of buttons as when it was learned. */
    internal fun sameCount(h: Habit, foundCount: Int): Boolean = h.hasSource && h.srcCount == foundCount

    /** Milliseconds after the page loaded by which the stream must be seen, or the habit counts as missed. */
    internal fun deadlineMs(h: Habit): Long = (h.secs * 1000).toLong() + EXTRA_WAIT_MS

    /** The habit after a miss, or null when it has missed [DROP_AFTER_MISSES] runs in a row (forget it). */
    internal fun afterMiss(h: Habit, now: Long): Habit? =
        if (h.misses + 1 >= DROP_AFTER_MISSES) null else h.copy(misses = h.misses + 1, at = now)

    /** The habit after it helped (or was at least not wrong): misses cleared, moved to "most recently used". */
    internal fun afterWorked(h: Habit, now: Long): Habit = h.copy(misses = 0, at = now)

    /** Adds or replaces [k] as the most recently used entry, drops expired ones, keeps at most [max]. */
    internal fun insert(all: Map<String, Habit>, k: String, h: Habit, now: Long, max: Int = MAX_SITES): LinkedHashMap<String, Habit> {
        val out = LinkedHashMap<String, Habit>()
        for ((key, v) in all) if (key != k && now - v.at < EXPIRE_MS) out[key] = v
        out[k] = h
        while (out.size > max) out.remove(out.keys.first())
        return out
    }

    /** Report text for the parts of the habit that will be done now. */
    internal fun describe(h: Habit, withTap: Boolean, withSource: Boolean): String {
        val parts = ArrayList<String>()
        if (withTap && h.hasTap) {
            val where = if (h.tapKind == KIND_PLAYER) "player spot" else "middle of the page"
            parts += "tap at $where, ${if (h.taps >= 2) "2 taps" else "1 tap"}"
        }
        if (withSource && h.hasSource) parts += "source button ${h.srcIndex + 1} of ${h.srcCount}"
        return parts.joinToString(" + ")
    }

    // ---- storage ----

    fun get(ctx: Context, host: String?): Habit? {
        val k = key(host) ?: return null
        val h = load(ctx)[k]
        return if (usable(h, System.currentTimeMillis())) h else null
    }

    /** Remember [h] for [host] (replaces what was there, misses start again). */
    @Synchronized
    fun save(ctx: Context, host: String?, h: Habit) {
        val k = key(host) ?: return
        store(ctx, insert(load(ctx), k, h, System.currentTimeMillis()))
    }

    /** The habit for [host] helped: clear its misses. */
    @Synchronized
    fun worked(ctx: Context, host: String?) {
        val k = key(host) ?: return
        val now = System.currentTimeMillis()
        val cur = load(ctx)[k] ?: return
        store(ctx, insert(load(ctx), k, afterWorked(cur, now), now))
    }

    /** The habit for [host] missed once. Returns true when that made it drop out. */
    @Synchronized
    fun missed(ctx: Context, host: String?): Boolean {
        val k = key(host) ?: return false
        val now = System.currentTimeMillis()
        val all = load(ctx)
        val cur = all[k] ?: return false
        val next = afterMiss(cur, now)
        if (next == null) {
            store(ctx, LinkedHashMap(all).also { it.remove(k) })
            return true
        }
        store(ctx, insert(all, k, next, now))
        return false
    }

    private fun store(ctx: Context, all: Map<String, Habit>) {
        val o = JSONObject()
        for ((host, h) in all) {
            o.put(
                host,
                JSONObject().put("tk", h.tapKind).put("fx", h.fx.toDouble()).put("fy", h.fy.toDouble()).put("taps", h.taps)
                    .put("si", h.srcIndex).put("sc", h.srcCount).put("secs", h.secs.toDouble()).put("miss", h.misses).put("at", h.at),
            )
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }

    private fun load(ctx: Context): Map<String, Habit> {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyMap()
        return try {
            val o = JSONObject(s)
            val out = LinkedHashMap<String, Habit>()
            for (k in o.keys()) {
                val e = o.optJSONObject(k) ?: continue
                out[k] = Habit(
                    e.optString("tk"), e.optDouble("fx").toFloat(), e.optDouble("fy").toFloat(), e.optInt("taps"),
                    e.optInt("si", -1), e.optInt("sc"), e.optDouble("secs").toFloat(), e.optInt("miss"), e.optLong("at"),
                )
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
