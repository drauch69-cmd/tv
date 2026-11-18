package com.streamlink.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** [sports] holds any of "nfl", "cfb", "nba" (empty = no sport); see [Sports]. One index page can mix leagues. */
data class SavedList(val id: String, val name: String, val url: String, val sports: Set<String> = emptySet()) {
    fun has(league: String): Boolean = league in sports
}

/** The sports a saved list can be tagged with. */
object Sports {
    const val NFL = "nfl"
    const val CFB = "cfb"
    const val NBA = "nba"
    /** Allowed non-empty values, in display order. */
    val ALL = listOf(NFL, CFB, NBA)
    fun label(sport: String): String = when (sport) { NFL -> "NFL"; CFB -> "College football"; NBA -> "NBA"; else -> "" }
    /** Unknown values read as "" (no sport). */
    fun clean(raw: String?): String = raw?.trim()?.lowercase()?.takeIf { it in ALL } ?: ""
    /** Cleans every value, drops unknown ones and returns the rest in [ALL] order. */
    fun cleanSet(raw: Iterable<String?>): Set<String> {
        val have = raw.map { clean(it) }.filter { it.isNotEmpty() }.toSet()
        return ALL.filterTo(LinkedHashSet()) { it in have }
    }
    /** "nfl,cfb" (comma-separated; empty or unknown values ignored) to a clean set. */
    fun parse(raw: String?): Set<String> = cleanSet((raw ?: "").split(','))
}

/** The lists JSON kept in preferences. Pure, so it can be unit tested. */
object SavedListsJson {
    fun parse(text: String): List<SavedList> = try {
        val a = JSONArray(text)
        (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { o ->
                val id = o.optString("id"); val url = o.optString("url")
                if (id.isEmpty() || url.isEmpty()) null
                else SavedList(id, o.optString("name").ifEmpty { Report.host(url) }, url, readSports(o))
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** The "sports" array if present, else the older single "sport" string. */
    private fun readSports(o: JSONObject): Set<String> {
        val arr = o.optJSONArray("sports")
        if (arr != null) return Sports.cleanSet((0 until arr.length()).map { arr.optString(it) })
        return Sports.cleanSet(listOf(o.optString("sport")))
    }

    fun toJson(lists: List<SavedList>): String {
        val a = JSONArray()
        lists.forEach { l ->
            a.put(JSONObject().put("id", l.id).put("name", l.name).put("url", l.url).put("sports", JSONArray(l.sports.toList())))
        }
        return a.toString()
    }
}

/**
 * Thread-safe in-memory memo keyed by id. Remembers loaded values (also "nothing there") so the same
 * object is returned until it is replaced or invalidated. Pure, so it can be unit tested.
 */
class CacheMemo<T : Any> {
    private val map = HashMap<String, Any>() // value, or NONE for "loaded, nothing there"
    private object NONE

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun get(id: String, load: () -> T?): T? {
        val have = map[id]
        if (have != null) return if (have === NONE) null else have as T
        val v = load()
        map[id] = v ?: NONE
        return v
    }

    @Synchronized fun put(id: String, value: T) { map[id] = value }
    @Synchronized fun invalidate(id: String) { map.remove(id) }
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
    // Parsed list caches kept in memory: the JSON in preferences is parsed once per change, not on every screen draw.
    private val cacheMemo = CacheMemo<CachedList>()
    private var listsRaw: String? = null
    private var listsParsed: List<SavedList> = emptyList()

    @Synchronized
    fun all(ctx: Context): List<SavedList> {
        val raw = prefs(ctx).getString(KEY, "[]") ?: "[]"
        if (raw != listsRaw) { listsParsed = SavedListsJson.parse(raw); listsRaw = raw }
        return listsParsed
    }

    fun get(ctx: Context, id: String): SavedList? = all(ctx).firstOrNull { it.id == id }

    /**
     * Adds a list (or renames the existing one with the same link; a non-empty [sports] set also replaces the
     * existing one's tags). Null when the limit is reached.
     */
    @Synchronized
    fun add(ctx: Context, url: String, name: String, sports: Set<String> = emptySet()): SavedList? {
        val cur = all(ctx)
        val clean = cleanName(name)
        val tags = Sports.cleanSet(sports)
        cur.firstOrNull { it.url == url }?.let { existing ->
            if (clean.isEmpty() && tags.isEmpty()) return existing
            val updated = existing.copy(name = clean.ifEmpty { existing.name }, sports = tags.ifEmpty { existing.sports })
            save(ctx, cur.map { if (it.id == existing.id) updated else it })
            return updated
        }
        if (cur.size >= MAX) return null
        val l = SavedList(UUID.randomUUID().toString().take(8), clean.ifEmpty { Report.host(url) }, url, tags)
        save(ctx, cur + l)
        return l
    }

    @Synchronized
    fun remove(ctx: Context, id: String): Boolean {
        val cur = all(ctx)
        if (cur.none { it.id == id }) return false
        save(ctx, cur.filter { it.id != id })
        prefs(ctx).edit().remove(CACHE_PREFIX + id).apply()
        cacheMemo.invalidate(id)
        busy.remove(id); errors.remove(id)
        return true
    }

    @Synchronized
    fun rename(ctx: Context, id: String, name: String) {
        val clean = cleanName(name)
        if (clean.isEmpty()) return
        save(ctx, all(ctx).map { if (it.id == id) it.copy(name = clean) else it })
    }

    /** Sets or clears a list's sports. False if there is no such list. */
    @Synchronized
    fun setSports(ctx: Context, id: String, sports: Set<String>): Boolean {
        val cur = all(ctx)
        if (cur.none { it.id == id }) return false
        val tags = Sports.cleanSet(sports)
        save(ctx, cur.map { if (it.id == id) it.copy(sports = tags) else it })
        return true
    }

    fun cleanName(raw: String): String = raw.filter { !it.isISOControl() }.trim().take(60)

    /** The last read of a list; the same object is returned until the cache is replaced or removed. */
    fun cached(ctx: Context, id: String): CachedList? = cacheMemo.get(id) {
        try {
            prefs(ctx).getString(CACHE_PREFIX + id, null)?.let {
                val o = JSONObject(it)
                CachedList(ListJson.resultFromJson(o.getJSONObject("result")), o.optLong("readAt"))
            }
        } catch (_: Exception) {
            null
        }
    }

    fun saveCache(ctx: Context, id: String, result: ListResult, readAt: Long) {
        val o = JSONObject().put("readAt", readAt).put("result", ListJson.resultToJson(result))
        prefs(ctx).edit().putString(CACHE_PREFIX + id, o.toString()).apply()
        cacheMemo.invalidate(id) // the next read parses what was saved, exactly as before
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
