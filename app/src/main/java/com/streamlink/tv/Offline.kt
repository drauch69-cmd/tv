package com.streamlink.tv

import android.content.Context

/**
 * Events whose stream was dead at the source (the check found it offline or refused on every referer). For
 * [MARK_MS] such an event shows as "Offline" (status Finished) in lists, the Live rows and the phone, so it is not
 * picked again; opening it anyway still runs the normal search, and a stream that plays clears the mark.
 * Keyed by [GameFolders.urlKey]; kept in preferences so an app restart doesn't forget it.
 */
object OfflineMarks {
    const val MARK_MS = 15 * 60_000L
    const val BADGE = "Offline"
    private const val PREFS = "offline_marks"
    private const val KEY = "marks"

    private val lock = Any()
    private var app: Context? = null
    private val marks = HashMap<String, Long>()

    fun init(ctx: Context) {
        synchronized(lock) {
            app = ctx.applicationContext
            marks.clear()
            marks.putAll(OfflineText.parse(prefs()?.getString(KEY, null), System.currentTimeMillis()))
        }
    }

    private fun prefs() = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun save() { prefs()?.edit()?.putString(KEY, OfflineText.format(marks))?.apply() }
    private fun key(url: String) = GameFolders.urlKey(url.substringBefore('#'))

    fun mark(url: String?, now: Long) {
        if (url.isNullOrBlank()) return
        synchronized(lock) { marks[key(url)] = now; save() }
    }

    fun clear(url: String?) {
        if (url.isNullOrBlank()) return
        synchronized(lock) { if (marks.remove(key(url)) != null) save() }
    }

    fun isOffline(url: String, now: Long): Boolean = synchronized(lock) {
        if (marks.isEmpty()) return false
        val t = marks[key(url)] ?: return false
        if (now - t >= MARK_MS || now < t) { marks.remove(key(url)); save(); false } else true
    }
}

/** The stored form of the marks ("key\ttime" lines). Pure, so it can be unit tested. */
object OfflineText {
    fun format(marks: Map<String, Long>): String = marks.entries.joinToString("\n") { "${it.key}\t${it.value}" }

    /** Marks still inside [OfflineMarks.MARK_MS] at [now]; bad lines are skipped. */
    fun parse(text: String?, now: Long): Map<String, Long> {
        if (text.isNullOrEmpty()) return emptyMap()
        val out = HashMap<String, Long>()
        for (line in text.split('\n')) {
            val k = line.substringBefore('\t', "")
            val t = line.substringAfter('\t', "").toLongOrNull() ?: continue
            if (k.isNotEmpty() && now - t in 0 until OfflineMarks.MARK_MS) out[k] = t
        }
        return out
    }
}
