package com.streamlink.tv

import android.content.Context
import org.json.JSONObject

/**
 * How each site (SiteKey) has been reading lately (lab, 2026-11-22). A site that keeps failing, with no move found,
 * is shown as "Not working" on the Lists screen so the user can remove it or keep it. Also remembers a recent move
 * for the "Moved from" note. One entry per site in a small settings file. The rules are pure and unit tested.
 */
object SiteHealth {
    private const val PREFS = "site_health"
    private const val KEY = "sites_1"
    private const val MAX_SITES = 60
    const val DAY = 24 * 60 * 60_000L
    /** "Not working": 3+ failed loads in a row over at least a day, or 3+ bad reads (failed or empty) over 3 days. */
    const val FAILED_FOR_MS = DAY
    const val BAD_FOR_MS = 3 * DAY
    const val MIN_BAD = 3
    /** A move is looked for at most this often per site. */
    const val MOVE_CHECK_EVERY_MS = 6 * 60 * 60_000L
    /** "Moved from" shows this long. */
    const val MOVED_NOTE_MS = 3 * DAY

    data class Entry(
        val fails: Int = 0, val empties: Int = 0, val firstFail: Long = 0L, val firstEmpty: Long = 0L,
        val moveCheckAt: Long = 0L, val movedFrom: String = "", val movedAt: Long = 0L,
    )

    enum class Read { OK, FAILED, EMPTY }

    /** What a finished list read says: worked (events found), the page failed to load, or it loaded with no events. */
    fun kind(result: ListResult): Read = when {
        result.error == null && result.items.isNotEmpty() -> Read.OK
        result.error != null && !result.noEvents -> Read.FAILED
        else -> Read.EMPTY
    }

    // ---- pure rules ----

    internal fun after(e: Entry?, read: Read, now: Long): Entry {
        val cur = e ?: Entry()
        return when (read) {
            Read.OK -> cur.copy(fails = 0, empties = 0, firstFail = 0L, firstEmpty = 0L)
            Read.FAILED -> cur.copy(fails = cur.fails + 1, firstFail = if (cur.firstFail == 0L) now else cur.firstFail)
            Read.EMPTY -> cur.copy(empties = cur.empties + 1, firstEmpty = if (cur.firstEmpty == 0L) now else cur.firstEmpty)
        }
    }

    internal fun notWorking(e: Entry?, now: Long): Boolean {
        if (e == null) return false
        if (e.fails >= MIN_BAD && e.firstFail > 0 && now - e.firstFail >= FAILED_FOR_MS) return true
        val bad = e.fails + e.empties
        val since = listOf(e.firstFail, e.firstEmpty).filter { it > 0 }.minOrNull() ?: return false
        return bad >= MIN_BAD && now - since >= BAD_FOR_MS
    }

    /** Look for a move: after a failed load, or the 2nd empty read in a row; at most every [MOVE_CHECK_EVERY_MS]. */
    internal fun wantsMoveCheck(e: Entry?, read: Read, now: Long): Boolean {
        val cur = e ?: return false
        val bad = when (read) { Read.OK -> false; Read.FAILED -> cur.fails >= 1; Read.EMPTY -> cur.empties >= 2 }
        return bad && now - cur.moveCheckAt >= MOVE_CHECK_EVERY_MS
    }

    internal fun movedNote(e: Entry?, now: Long): String? =
        e?.takeIf { it.movedAt > 0 && it.movedFrom.isNotEmpty() && now - it.movedAt < MOVED_NOTE_MS }?.movedFrom

    // ---- storage ----

    @Synchronized
    fun get(ctx: Context, site: String?): Entry? = SiteKey.of(site)?.let { load(ctx)[it] }

    /** Records one finished read of a list on [site]; returns the new entry. */
    @Synchronized
    fun record(ctx: Context, site: String, read: Read, now: Long): Entry = update(ctx, site) { after(it, read, now) }

    @Synchronized
    fun markMoveCheck(ctx: Context, site: String, now: Long) { update(ctx, site) { (it ?: Entry()).copy(moveCheckAt = now) } }

    /** The site moved: the new site starts clean, with the note; the old one is forgotten. */
    @Synchronized
    fun moved(ctx: Context, oldSite: String, newSite: String, now: Long) {
        val all = LinkedHashMap(load(ctx))
        val o = SiteKey.of(oldSite) ?: return
        all.remove(o)
        val n = SiteKey.of(newSite) ?: return
        all.remove(n)
        all[n] = Entry(movedFrom = o, movedAt = now, moveCheckAt = now)
        save(ctx, all)
    }

    /** The user chose "Keep": start counting again from zero. */
    @Synchronized
    fun keep(ctx: Context, site: String) { update(ctx, site) { (it ?: Entry()).copy(fails = 0, empties = 0, firstFail = 0L, firstEmpty = 0L) } }

    @Synchronized
    fun forget(ctx: Context, site: String) {
        val k = SiteKey.of(site) ?: return
        val all = LinkedHashMap(load(ctx))
        if (all.remove(k) != null) save(ctx, all)
    }

    fun isNotWorking(ctx: Context, site: String?, now: Long): Boolean = notWorking(get(ctx, site), now)
    fun movedFrom(ctx: Context, site: String?, now: Long): String? = movedNote(get(ctx, site), now)

    @Volatile private var app: Context? = null
    fun init(ctx: Context) { app = ctx.applicationContext }

    /** Report lines: sites that moved lately or look not working. Any thread; empty before [init]. */
    fun reportLines(now: Long = System.currentTimeMillis()): List<String> = app?.let { reportLines(it, now) } ?: emptyList()

    @Synchronized
    fun reportLines(ctx: Context, now: Long): List<String> {
        val out = ArrayList<String>()
        for ((site, e) in load(ctx)) {
            movedNote(e, now)?.let { out.add("Site moved: $it → $site") }
            if (notWorking(e, now)) out.add("Site not working: $site (${e.fails} failed, ${e.empties} empty reads in a row)")
        }
        return out
    }

    private inline fun update(ctx: Context, site: String, f: (Entry?) -> Entry): Entry {
        val k = SiteKey.of(site) ?: return Entry()
        val all = LinkedHashMap(load(ctx))
        val next = f(all[k])
        all.remove(k)
        all[k] = next
        while (all.size > MAX_SITES) all.remove(all.keys.first())
        save(ctx, all)
        return next
    }

    private fun load(ctx: Context): Map<String, Entry> {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyMap()
        return try {
            val o = JSONObject(s)
            val out = LinkedHashMap<String, Entry>()
            for (k in o.keys()) o.optJSONObject(k)?.let {
                out[k] = Entry(it.optInt("f"), it.optInt("e"), it.optLong("ff"), it.optLong("fe"), it.optLong("mc"), it.optString("mf"), it.optLong("ma"))
            }
            out
        } catch (_: Exception) { emptyMap() }
    }

    private fun save(ctx: Context, all: Map<String, Entry>) {
        val o = JSONObject()
        for ((k, e) in all) o.put(k, JSONObject().put("f", e.fails).put("e", e.empties).put("ff", e.firstFail).put("fe", e.firstEmpty)
            .put("mc", e.moveCheckAt).put("mf", e.movedFrom).put("ma", e.movedAt))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }
}
