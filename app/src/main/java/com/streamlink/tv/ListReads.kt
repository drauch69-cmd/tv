package com.streamlink.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup

/**
 * The one list reader for the whole app (B1): one queue, one read at a time, run in a hidden web view on the
 * newest attached screen (home, a list screen, a sport screen). Screens ask with [request] and hear results
 * through [addListener]; they never read pages themselves. Duplicate requests are merged; non-manual requests for
 * a list read a moment ago are skipped (B2, [ListReadLogic.skipFresh]). [ListReadGate] still holds new reads while
 * a video is being found or plays. Main thread only.
 */
// Lint: holds only the application context (and, for screens, entries removed in onDestroy), not a leak.
@SuppressLint("StaticFieldLeak")
object ListReads {
    const val WATCHDOG_MS = 90_000L
    private const val TICK_MS = 5_000L

    private class Host(val owner: Activity, val holder: ViewGroup) { var reader: IndexReader? = null }

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    /** Attached screens, most recently attached last; reads start on the last one. */
    private val hosts = ArrayList<Host>()
    /** Waiting lists in order: id -> manual. */
    private val queue = LinkedHashMap<String, Boolean>()
    private var current: String? = null
    private var currentManual = false
    private var currentHost: Host? = null
    private var currentList: SavedList? = null
    private var startedAt = 0L
    private var token = 0
    /** When each list's last read finished (worked or not), so a failing list is not read again at once. */
    private val lastDone = HashMap<String, Long>()
    /** The read time a "skipped" line was last written for, per list: one line per skip, not per tick. */
    private val skipLogged = HashMap<String, Long>()
    private val listeners = LinkedHashSet<(String, ListResult?) -> Unit>()
    private var ticking = false

    private val tick = object : Runnable {
        override fun run() {
            ticking = false
            val id = current
            if (id != null && System.currentTimeMillis() - startedAt > WATCHDOG_MS) {
                // The reader never answered; drop it.
                currentHost?.reader?.cancel()
                currentList?.let { finishRead(it, ListResult(it.url, "", emptyList(), "automatic", "the page took too long to answer")) }
            }
            pump()
            if (current != null || queue.isNotEmpty()) schedule()
        }
    }

    private fun schedule() {
        if (ticking) return
        ticking = true
        main.postDelayed(tick, TICK_MS)
    }

    /** A screen in front offers its laid-out hidden holder (call in onResume). Reads start on the newest one. */
    fun attach(owner: Activity, holder: ViewGroup) {
        app = owner.applicationContext
        val old = hosts.firstOrNull { it.owner === owner }
        if (old != null && old.holder === holder) {
            hosts.remove(old)
            hosts.add(old) // newest again; keeps its reader
        } else {
            if (old != null) detach(owner)
            hosts.add(Host(owner, holder))
        }
        pump()
    }

    /** The screen is going away (call in onDestroy). A read running in its web view is cancelled and queued again first. */
    fun detach(owner: Activity) {
        val h = hosts.firstOrNull { it.owner === owner } ?: return
        hosts.remove(h)
        val id = current
        if (id != null && currentHost === h) {
            token++
            h.reader?.cancel()
            current = null
            currentHost = null
            currentList = null
            val rest = LinkedHashMap(queue)
            queue.clear()
            queue[id] = currentManual
            queue.putAll(rest)
        }
        h.reader?.cancel()
        h.reader = null
        pump()
    }

    fun addListener(l: (String, ListResult?) -> Unit) { listeners.add(l) }
    fun removeListener(l: (String, ListResult?) -> Unit) { listeners.remove(l) }

    /**
     * Asks for a read. Returns false when it was skipped (the list is gone, or a non-manual request came while the
     * list was read a moment ago); true when it is queued or already being read.
     */
    fun request(ctx: Context, id: String, reason: ListReadLogic.Reason): Boolean {
        app = ctx.applicationContext
        val l = SavedLists.get(ctx, id) ?: return false
        if (current != id && !queue.containsKey(id)) {
            val now = System.currentTimeMillis()
            val readAt = SavedLists.cached(ctx, id)?.readAt
            if (ListReadLogic.skipFresh(reason, readAt, now) && SavedLists.lastError(id) == null) {
                if (readAt != null && skipLogged[id] != readAt) {
                    skipLogged[id] = readAt
                    Report.log("List: skipped ${Report.host(l.url)} (read ${(now - readAt) / 1000} s ago)")
                }
                return false
            }
        }
        if (ListReadLogic.merge(queue, current, id, reason == ListReadLogic.Reason.MANUAL)) {
            SavedLists.setBusy(id, true)
            notify(id, null)
        }
        pump()
        return true
    }

    /** When this list was last read or tried (0 = never), for the screens' own re-read timers. */
    fun lastAttempt(ctx: Context, id: String): Long =
        maxOf(SavedLists.cached(ctx, id)?.readAt ?: 0L, lastDone[id] ?: 0L)

    private fun pump() {
        if (current != null) return
        if (queue.isEmpty()) return
        if (ListReadGate.blocked()) { schedule(); return } // held during a video; the tick starts it again
        val host = hosts.lastOrNull() ?: return // no screen to read in yet; the next attach starts it
        val ctx = app ?: return
        val id = queue.keys.first()
        val manual = queue.remove(id) ?: false
        val l = SavedLists.get(ctx, id)
        if (l == null) {
            SavedLists.setBusy(id, false)
            pump()
            return
        }
        val reader = host.reader ?: IndexReader(host.owner, host.holder).also { host.reader = it }
        current = id
        currentManual = manual
        currentHost = host
        currentList = l
        startedAt = System.currentTimeMillis()
        val tok = ++token
        notify(id, null)
        reader.read(l.url) { r -> if (tok == token) finishRead(l, r) }
        schedule()
    }

    private fun finishRead(l: SavedList, result: ListResult) {
        if (current != l.id) return
        token++
        current = null
        currentHost = null
        currentList = null
        val now = System.currentTimeMillis()
        lastDone[l.id] = now
        SavedLists.setBusy(l.id, false)
        app?.let { ctx ->
            SavedLists.applyResult(ctx, l, result, now)
            val items = SavedLists.cached(ctx, l.id)?.result?.items ?: emptyList()
            Report.log("List: next read in ${ListReadLogic.nextReadDelay(items, now) / 1000} s (while its list or sport screen is open)")
        }
        notify(l.id, result)
        pump()
    }

    private fun notify(id: String, result: ListResult?) {
        for (l in listeners.toList()) {
            try { l(id, result) } catch (_: Exception) { }
        }
    }
}
