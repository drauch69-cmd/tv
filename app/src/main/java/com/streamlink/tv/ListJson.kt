package com.streamlink.tv

import org.json.JSONArray
import org.json.JSONObject

/** JSON for the saved-list cache and for the phone page. Page-derived text is data only; the phone uses textContent. */
object ListJson {
    fun itemToJson(i: ListItem): JSONObject = JSONObject().apply {
        put("title", i.title); put("subtitle", i.subtitle); put("url", i.url)
        put("start", i.startMillis ?: JSONObject.NULL)
        put("timeText", i.timeText); put("section", i.section); put("liveHint", i.liveHint)
        put("imageUrl", i.imageUrl ?: JSONObject.NULL)
    }

    fun itemFromJson(o: JSONObject): ListItem = ListItem(
        title = o.optString("title"), subtitle = o.optString("subtitle"), url = o.optString("url"),
        startMillis = if (o.isNull("start")) null else o.optLong("start"),
        timeText = o.optString("timeText"), section = o.optString("section"), liveHint = o.optBoolean("liveHint"),
        imageUrl = if (o.isNull("imageUrl")) null else o.optString("imageUrl").ifEmpty { null },
    )

    fun resultToJson(r: ListResult): JSONObject = JSONObject().apply {
        put("pageUrl", r.pageUrl); put("pageTitle", r.pageTitle); put("method", r.method)
        put("error", r.error ?: JSONObject.NULL)
        put("items", JSONArray().also { a -> r.items.forEach { a.put(itemToJson(it)) } })
    }

    fun resultFromJson(o: JSONObject): ListResult {
        val arr = o.optJSONArray("items") ?: JSONArray()
        val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::itemFromJson) }
        return ListResult(
            pageUrl = o.optString("pageUrl"), pageTitle = o.optString("pageTitle"), items = items,
            method = o.optString("method"), error = if (o.isNull("error")) null else o.optString("error"),
        )
    }

    /**
     * What the TV shows and the phone lists: sorted. Events that look finished are kept (last, marked
     * "Maybe ended"): the page still lists them, and a misread start time must not make an event vanish.
     */
    fun visible(items: List<ListItem>, now: Long, games: List<Game> = ScoreFeed.latest()): List<ListItem> = ListStatus.sorted(items, now, games)

    /** GET /lists */
    fun listsForPhone(lists: List<SavedList>, cache: (String) -> CachedList?, busy: (String) -> Boolean,
                      error: (String) -> String?, now: Long): String {
        val arr = JSONArray()
        for (l in lists) {
            val c = cache(l.id)
            arr.put(JSONObject().apply {
                put("id", l.id); put("name", l.name); put("host", Report.host(l.url)); put("sports", JSONArray(l.sports.toList()))
                put("readAt", c?.readAt ?: 0L); put("ago", ListText.ago(c?.readAt ?: 0L, now))
                put("count", c?.let { visible(it.result.items, now).size } ?: -1)
                put("refreshing", busy(l.id)); put("error", error(l.id) ?: JSONObject.NULL)
            })
        }
        return JSONObject().put("lists", arr).toString()
    }

    /** GET /list?id= (status and badge computed now, on the TV). */
    fun listForPhone(l: SavedList, c: CachedList?, refreshing: Boolean, error: String?, now: Long): String {
        val items = JSONArray()
        c?.let { cl ->
            // one snapshot so order, status and badge agree; only the list's own sports (like the TV list screen)
            val games = ScoreFeed.forLeagues(l.sports)
            for (i in visible(cl.result.items, now, games)) {
                val r = ScoreStatus.resolve(i, now, games)
                items.put(JSONObject().apply {
                    put("title", ListText.displayTitle(i)); put("subtitle", i.subtitle); put("url", i.url); put("section", i.section)
                    put("start", r.item.startMillis ?: JSONObject.NULL)
                    put("status", r.status); put("badge", ListText.badge(i, now, games = games))
                })
            }
        }
        return JSONObject().apply {
            put("id", l.id); put("name", l.name); put("url", l.url); put("host", Report.host(l.url))
            put("readAt", c?.readAt ?: 0L); put("ago", ListText.ago(c?.readAt ?: 0L, now))
            put("refreshing", refreshing); put("error", error ?: JSONObject.NULL)
            put("pageTitle", c?.result?.pageTitle ?: ""); put("items", items)
        }.toString()
    }
}
