package com.streamlink.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * [sports] holds any of "nfl", "cfb", "nba" (empty = no sport); see [Sports]. One index page can mix leagues.
 * [rank]: the user's ranking of lists, 1 = best; null = unranked (see [Ranking]). Ranked lists' streams come first.
 */
data class SavedList(
    val id: String, val name: String, val url: String, val sports: Set<String> = emptySet(),
    val rank: Int? = null,
) {
    fun has(league: String): Boolean = league in sports
}

/**
 * The list ranking rules. Pure, so it can be unit tested.
 * Ranked lists come first, by rank (1 = best); unranked lists follow in saved order. Ranks are always 1..k with
 * no gaps or ties ([normalize]); giving a list a taken rank moves the others down one ([set]).
 */
object Ranking {
    /** [lists] with ranks renumbered 1..k in rank order (ties: saved order); unranked stay null. Same object if already clean. */
    fun normalize(lists: List<SavedList>): List<SavedList> {
        val ranked = lists.withIndex().filter { it.value.rank != null }
            .sortedWith(compareBy({ it.value.rank }, { it.index })).map { it.value.id }
        val want = ranked.withIndex().associate { it.value to it.index + 1 }
        if (lists.all { it.rank == want[it.id] }) return lists
        return lists.map { l -> if (l.rank == want[l.id]) l else l.copy(rank = want[l.id]) }
    }

    /**
     * The lists after giving list [id] rank [rank] (null = unranked). The list is taken out of the ranking, then put
     * in at [rank] (clamped to 1..ranked+1), and the ranks are renumbered. Saved order is never changed. An [id]
     * that does not exist changes nothing (same object).
     */
    fun set(lists: List<SavedList>, id: String, rank: Int?): List<SavedList> {
        if (lists.none { it.id == id }) return lists
        val clean = normalize(lists)
        val order = clean.filter { it.rank != null && it.id != id }.sortedBy { it.rank }.map { it.id }.toMutableList()
        if (rank != null) order.add((rank - 1).coerceIn(0, order.size), id)
        val want = order.withIndex().associate { it.value to it.index + 1 }
        val next = clean.map { l -> if (l.rank == want[l.id]) l else l.copy(rank = want[l.id]) }
        return if (next == lists) lists else next
    }

    /** Every list id, best first: ranked by rank, then unranked in saved order. */
    fun order(lists: List<SavedList>): List<String> = sorted(lists).map { it.id }

    /** [lists] best first (see [order]). */
    fun sorted(lists: List<SavedList>): List<SavedList> =
        lists.withIndex().sortedWith(compareBy({ it.value.rank ?: Int.MAX_VALUE }, { it.index })).map { it.value }

    /**
     * [items] grouped by their list's place in [order] (best list first); items of the same list, and items whose
     * list is not in [order], keep their order (the latter go last). [order] null or empty: [items] unchanged.
     */
    fun <T> sort(items: List<T>, order: List<String>?, listIdOf: (T) -> String): List<T> {
        if (order.isNullOrEmpty() || items.size < 2) return items
        val pos = order.withIndex().associate { it.value to it.index }
        return items.withIndex().sortedWith(compareBy({ pos[listIdOf(it.value)] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
    }

    /** "#1", or "" when unranked. */
    fun label(rank: Int?): String = if (rank == null) "" else "#$rank"
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
                else SavedList(id, o.optString("name").ifEmpty { Report.host(url) }, url, readSports(o), readRank(o))
            }
        }.let { Ranking.normalize(it) }
    } catch (_: Exception) {
        emptyList()
    }

    /** "rank" (1 or more) if present; else the older single "preferred" mark reads as rank 1; else unranked. */
    private fun readRank(o: JSONObject): Int? {
        if (o.has("rank") && !o.isNull("rank")) return o.optInt("rank", 0).takeIf { it >= 1 }
        return if (o.optBoolean("preferred", false)) 1 else null
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
            a.put(JSONObject().put("id", l.id).put("name", l.name).put("url", l.url).put("sports", JSONArray(l.sports.toList())).put("rank", l.rank ?: JSONObject.NULL))
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

    /** Renames a list (name cleaned, max 60 chars). False if there is no such list or the name is empty. */
    @Synchronized
    fun rename(ctx: Context, id: String, name: String): Boolean {
        val clean = cleanName(name)
        val lists = all(ctx)
        if (clean.isEmpty() || lists.none { it.id == id }) return false
        save(ctx, lists.map { if (it.id == id) it.copy(name = clean) else it })
        return true
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

    /** Every list id, best first (see [Ranking.order]). */
    fun rankOrder(ctx: Context): List<String> = Ranking.order(all(ctx))

    /** Gives list [id] rank [rank] (null = unranked); others move down to make room. False if there is no such list. */
    @Synchronized
    fun setRank(ctx: Context, id: String, rank: Int?): Boolean {
        val cur = all(ctx)
        if (cur.none { it.id == id }) return false
        val next = Ranking.set(cur, id, rank)
        if (next != cur) save(ctx, next) // a new raw string: the parsed lists in memory are replaced on the next all()
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
        if (ListCachePolicy.shouldSave(result)) saveCache(ctx, list.id, result, now)
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
