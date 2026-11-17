package com.streamlink.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup

// STUB — replaced by list-reader at merge. Same API as /home/claude/team/shared/LIST_API.md.
class IndexReader(@Suppress("UNUSED_PARAMETER") context: Context, @Suppress("UNUSED_PARAMETER") holder: ViewGroup) {
    private val main = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    fun read(url: String, callback: (ListResult) -> Unit) {
        cancel()
        val r = Runnable {
            pending = null
            val now = System.currentTimeMillis()
            val min = 60_000L
            callback(ListResult(
                pageUrl = url, pageTitle = "Stub index", method = "automatic", error = null,
                items = listOf(
                    ListItem("Stub: live event", "Stub organiser", "https://example.com/live", now - 20 * min, "Started 20 min ago", "Today", true, null),
                    ListItem("Stub: starts soon", "Stub organiser", "https://example.com/soon", now + 12 * min, "Soon", "Today", false, null),
                    ListItem("Stub: later", "Stub organiser", "https://example.com/later", now + 26 * 60 * min, "Tomorrow", "Tomorrow", false, null),
                ),
            ))
        }
        pending = r
        main.postDelayed(r, 1000)
    }

    fun cancel() {
        pending?.let { main.removeCallbacks(it) }
        pending = null
    }
}
