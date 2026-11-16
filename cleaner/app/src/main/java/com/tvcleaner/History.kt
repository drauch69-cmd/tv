package com.tvcleaner

import android.annotation.SuppressLint
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Every change this app made, newest first, so any of it can be undone. */
data class Change(val pkg: String, val label: String, val action: String, val time: Long, val ok: Boolean)

class History(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("history", Context.MODE_PRIVATE)

    fun all(): List<Change> = try {
        val arr = JSONArray(prefs.getString("changes", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Change(o.getString("pkg"), o.optString("label"), o.getString("action"), o.getLong("time"), o.optBoolean("ok", true))
        }
    } catch (_: Exception) {
        emptyList()
    }

    @SuppressLint("ApplySharedPref") // Always called off the main thread; see below.
    fun add(change: Change) {
        val list = (listOf(change) + all()).take(500)
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("pkg", it.pkg)
                    .put("label", it.label)
                    .put("action", it.action)
                    .put("time", it.time)
                    .put("ok", it.ok),
            )
        }
        // commit, not apply: if a change cuts the TV's connection mid-run, the record is already saved.
        prefs.edit().putString("changes", arr.toString()).commit()
    }

    /** Apps this app turned off that are still off, so "Turn everything back on" knows what to do. */
    fun turnedOff(): Set<String> {
        val latest = LinkedHashMap<String, Change>()
        all().filter { it.ok }.forEach { latest.putIfAbsent(it.pkg, it) }
        return latest.values.filter { it.action == "disable" }.map { it.pkg }.toSet()
    }
}
