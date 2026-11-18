package com.streamlink.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** [sport] is "nfl", "cfb" or "" (none); see [Sports]. */
data class SavedList(val id: String, val name: String, val url: String, val sport: String = "")

/** The sports a saved list can be tagged with. */
object Sports {
    const val NFL = "nfl"
    const val CFB = "cfb"
    /** Allowed non-empty values, in display order. */
    val ALL = listOf(NFL, CFB)
    fun label(sport: String): String = when (sport) { NFL -> "NFL"; CFB -> "College football"; else -> "" }
    /** Unknown values read as "" (no sport). */
    fun clean(raw: String?): String = raw?.trim()?.lowercase()?.takeIf { it in ALL } ?: ""
}

/** The lists JSON kept in preferences. Pure, so it can be unit tested. */
object SavedListsJson {
    fun parse(text: String): List<SavedList> = try {
        val a = JSONArray(text)
        (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { o ->
                val id = o.optString("id"); val url = o.optString("url")
                if (id.isEmpty() || url.isEmpty()) null
                else SavedList(id, o.optString("name").ifEmpty { Report.host(url) }, url, Sports.clean(o.optString("sport")))
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun toJson(lists: List<SavedList>): String {
        val a = JSONArray()
        lists.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("sport", it.sport)) }
        return a.toString()
    }
}

/** The last successful read of a list and when it happened. */
data class CachedList(val result: ListResult, val readAt: Long)

/** Stream lists the user saved (index pages), plus a cache of the events last read from each. */
object SavedLists {
    const val MAX = 20
    private const val KEY = "lists"
    private const val CACHE_PREFIX = "listcache."

    // In-memory only: which lists are being read right now, and the last read error per list.
    private val busy = HashSet<String>()
    private val errors = HashMap<String, String>()

    @Synchronized
    fun all(ctx: Context): List<SavedList> = SavedListsJson.parse(prefs(ctx).getString(KEY, "[]") ?: "[]")

    fun get(ctx: Context, id: String): SavedList? = all(ctx).firstOrNull { it.id == id }

    /**
     * Adds a list (or renames the existing one with the same link; a non-empty [sport] also updates the
     * existing one's sport). Null when the limit is reached.
     */
    @Synchronized
    fun add(ctx: Context, url: String, name: String, sport: String = ""): SavedList? {
        val cur = all(ctx)
        val clean = cleanName(name)
        val tag = Sports.clean(sport)
        cur.firstOrNull { it.url == url }?.let { existing ->
            if (clean.isEmpty() && tag.isEmpty()) return existing
            val updated = existing.copy(name = clean.ifEmpty { existing.name }, sport = tag.ifEmpty { existing.sport })
            save(ctx, cur.map { if (it.id == existing.id) updated else it })
            return updated
        }
        if (cur.size >= MAX) return null
        val l = SavedList(UUID.randomUUID().toString().take(8), clean.ifEmpty { Report.host(url) }, url, tag)
        save(ctx, cur + l)
        return l
    }

    @Synchronized
    fun remove(ctx: Context, id: String): Boolean {
        val cur = all(ctx)
        if (cur.none { it.id == id }) return false
        save(ctx, cur.filter { it.id != id })
        prefs(ctx).edit().remove(CACHE_PREFIX + id).apply()
        busy.remove(id); errors.remove(id)
        return true
    }

    @Synchronized
    fun rename(ctx: Context, id: String, name: String) {
        val clean = cleanName(name)
        if (clean.isEmpty()) return
        save(ctx, all(ctx).map { if (it.id == id) it.copy(name = clean) else it })
    }

    /** Sets or clears a list's sport. False if there is no such list. */
    @Synchronized
    fun setSport(ctx: Context, id: String, sport: String): Boolean {
        val cur = all(ctx)
        if (cur.none { it.id == id }) return false
        val tag = Sports.clean(sport)
        save(ctx, cur.map { if (it.id == id) it.copy(sport = tag) else it })
        return true
    }

    fun cleanName(raw: String): String = raw.filter { !it.isISOControl() }.trim().take(60)

    fun cached(ctx: Context, id: String): CachedList? = try {
        prefs(ctx).getString(CACHE_PREFIX + id, null)?.let {
            val o = JSONObject(it)
            CachedList(ListJson.resultFromJson(o.getJSONObject("result")), o.optLong("readAt"))
        }
    } catch (_: Exception) {
        null
    }

    fun saveCache(ctx: Context, id: String, result: ListResult, readAt: Long) {
        val o = JSONObject().put("readAt", readAt).put("result", ListJson.resultToJson(result))
        prefs(ctx).edit().putString(CACHE_PREFIX + id, o.toString()).apply()
    }

    /**
     * Records a finished read: keeps the cache when the read worked (or at least found events), remembers
     * the error text otherwise. Returns the error to show, or null.
     */
    @Synchronized
    fun applyResult(ctx: Context, list: SavedList, result: ListResult, now: Long): String? {
        if (get(ctx, list.id) == null) return null // removed while reading
        if (result.error == null || result.items.isNotEmpty()) saveCache(ctx, list.id, result, now)
        val err = result.error?.let { "Couldn't read events from ${Report.host(list.url)}: $it" }
        if (err == null) errors.remove(list.id) else errors[list.id] = err
        return err
    }

    @Synchronized fun lastError(id: String): String? = errors[id]
    @Synchronized fun isBusy(id: String): Boolean = id in busy
    @Synchronized fun setBusy(id: String, on: Boolean) { if (on) busy.add(id) else busy.remove(id) }

    private fun save(ctx: Context, lists: List<SavedList>) {
        prefs(ctx).edit().putString(KEY, SavedListsJson.toJson(lists)).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("streamlink", Context.MODE_PRIVATE)
}
