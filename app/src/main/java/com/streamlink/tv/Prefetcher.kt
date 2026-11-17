package com.streamlink.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup

/** The "Head start" switch: on by default, kept in SharedPreferences("streamlink") under "prefetch". */
object PrefetchSettings {
    const val KEY = "prefetch"

    fun enabled(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences("streamlink", Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun set(ctx: Context, on: Boolean) {
        ctx.applicationContext.getSharedPreferences("streamlink", Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        if (!on) Prefetch.clear()
    }
}

/**
 * Owned by a screen (home or a list). When an event keeps focus for 2 s, looks for its stream in the
 * background (own hidden [holder], own sniffer) and leaves the result in [Prefetch] for the waiting
 * screen. Moving focus, leaving the screen, or anything starting to play cancels it. One look at a
 * time. Quiet: it neither touches Session/AppStatus nor writes Report lines (see [Report.muted]).
 *
 * [screenMode] is the Session.mode this screen has while in front: "idle" (home) or "list".
 */
class Prefetcher(private val context: Context, holder: ViewGroup, private val screenMode: String) {
    private val sniffer = StreamSniffer(context, holder)
    private val main = Handler(Looper.getMainLooper())
    private var active = false
    private var url: String? = null     // the event waiting for its 2 s, or being looked up
    private var running = false
    private var muted = false

    // Coming back from a player or the waiting screen, Session.mode still says so for a moment (the other
    // screen is stopped after this one resumes): a focused event is looked at again a few times.
    private var wanted: ListItem? = null
    private var retries = 0
    private var inRecheck = false
    private val recheck = Runnable {
        val w = wanted ?: return@Runnable
        if (++retries > MAX_RETRIES) { wanted = null; return@Runnable }
        inRecheck = true
        try { focus(w) } finally { inRecheck = false }
    }

    private val begin = Runnable { startNow() }
    private val lostFocus = Runnable { cancel() }

    /** Call from onResume (true) and onPause (false). Going inactive cancels everything. */
    fun setActive(on: Boolean) {
        active = on
        if (!on) cancel()
    }

    /** An event view gained focus. Safe to call again for the same event (a re-render giving focus back). */
    fun focus(item: ListItem) {
        main.removeCallbacks(lostFocus)
        if (!inRecheck) retries = 0
        val now = System.currentTimeMillis()
        if (!eligible(item, now)) {
            cancel()
            if (PrefetchSettings.enabled(context) && active && PrefetchLogic.statusWorthLooking(ListStatus.of(item, now)) && Session.mode != screenMode) {
                wanted = item
                main.postDelayed(recheck, 1000)
            }
            return
        }
        if (item.url == url) return
        cancel()
        if (Prefetch.has(item.url, now) || Prefetch.recentlyMissed(item.url, now)) return
        url = item.url
        main.postDelayed(begin, PrefetchLogic.FOCUS_DELAY_MS)
    }

    /**
     * An event view lost focus. Cancels after the current message, so that a screen rebuilding its rows
     * (old view loses focus, the new one for the same event gains it) does not restart the look-up.
     */
    fun blur() {
        main.removeCallbacks(lostFocus)
        main.post(lostFocus)
    }

    fun cancel() {
        main.removeCallbacks(begin)
        main.removeCallbacks(lostFocus)
        main.removeCallbacks(recheck)
        wanted = null
        url = null
        if (running) {
            running = false
            sniffer.cancel()
        }
        unmute()
    }

    private fun eligible(item: ListItem, now: Long) =
        PrefetchLogic.eligible(ListStatus.of(item, now), Session.mode, screenMode, PrefetchSettings.enabled(context), active)

    private fun startNow() {
        val u = url ?: return
        // Re-check: something may have started playing or the switch may have been turned off meanwhile.
        if (!PrefetchLogic.canRun(Session.mode, screenMode, PrefetchSettings.enabled(context), active)) {
            url = null
            return
        }
        running = true
        muted = true
        Report.muted = true
        sniffer.sniff(u) { set ->
            running = false
            url = null
            unmute()
            if (set.sources.isNotEmpty()) Prefetch.put(u, set) else Prefetch.noteMiss(u, System.currentTimeMillis())
        }
    }

    private fun unmute() {
        if (muted) {
            muted = false
            Report.muted = false
        }
    }

    private companion object {
        const val MAX_RETRIES = 10
    }
}
