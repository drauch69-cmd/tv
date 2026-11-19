package com.streamlink.tv

/**
 * Background list reads (a hidden browser each) must not compete with finding or playing a video on the slow box:
 * while [Session.mode] says a stream is being found or played, no NEW read starts (one already running may finish;
 * queued ones wait). The home and sport screens ask [blocked] before starting each read and again on their ticks.
 */
object ListReadGate {
    private val busyModes = setOf("waiting", "finding", "player", "clean", "site")

    /** Pure rule (unit tested). */
    fun pausedFor(mode: String): Boolean = mode in busyModes

    private var logged = false

    /** True when new reads must wait now. Logs once when the pause starts and once when it ends. Main thread. */
    fun blocked(): Boolean {
        val p = pausedFor(Session.mode)
        if (p != logged) {
            logged = p
            Report.log(if (p) "List reads paused while a video plays" else "List reads resumed")
        }
        return p
    }
}
