package com.streamlink.tv

import java.net.URI

/**
 * The "What happened?" log for the current link: what was found, what was blocked, how the
 * stream servers answered. Shown on the phone page so problems can be diagnosed in one go.
 */
object Report {
    private val lines = ArrayList<String>()
    private val once = HashSet<String>()
    private val blockedHosts = LinkedHashMap<String, Int>()
    private var t0 = System.currentTimeMillis()
    private var link = ""

    /** True while a background look-ahead (see Prefetcher) runs: its lines belong to no play, so none are kept. */
    @Volatile var muted = false

    @Synchronized
    fun start(url: String) {
        if (lines.isNotEmpty()) ReportLog.archive(text()) // the running log keeps every play (user, 2026-11-20)
        muted = false
        lines.clear()
        once.clear()
        blockedHosts.clear()
        t0 = System.currentTimeMillis()
        link = url
        Timing.start()
        SampleRecorder.reset()
    }

    /** Lines about the last exit/crash, found at app start; they survive [start] and print under the link line. */
    private val pinned = ArrayList<String>()

    @Synchronized
    fun pin(msg: String) {
        if (pinned.size < 120) pinned.add(msg)
    }

    @Synchronized
    fun log(msg: String) {
        if (muted) return
        val s = (System.currentTimeMillis() - t0) / 1000.0
        lines.add(String.format(java.util.Locale.US, "%6.1fs  %s", s, msg))
        if (lines.size > 500) lines.removeAt(0)
        ReportLog.touch { text() }
    }

    @Synchronized
    fun logOnce(key: String, msg: String) {
        if (muted) return
        if (once.add(key)) log(msg)
    }

    @Synchronized
    fun countBlocked(host: String?) {
        val h = host ?: return
        if (muted) return
        val n = (blockedHosts[h] ?: 0) + 1
        blockedHosts[h] = n
        if (n == 1) log("Blocked ad/popup network: $h")
    }

    @Synchronized
    fun text(): String {
        val sb = StringBuilder()
        sb.append("StreamLink report (app build ").append(BuildConfig.VERSION_NAME).append(")\n")
        sb.append("Link: ").append(link.ifEmpty { "(none yet)" }).append("\n")
        val tally = try { FailureTally.reportLines() + SiteHealth.reportLines() } catch (_: Exception) { emptyList() }
        tally.forEach { sb.append(it).append('\n') }
        sb.append('\n')
        pinned.forEach { sb.append(it).append('\n') }
        if (pinned.isNotEmpty()) sb.append('\n')
        lines.forEach { sb.append(it).append('\n') }
        val scoreTimes = ScoreFeed.timingLines()
        if (scoreTimes.isNotEmpty()) {
            sb.append("\nScores timing (ESPN, latest last):\n")
            scoreTimes.forEach { sb.append("  ").append(it).append('\n') }
        }
        if (blockedHosts.isNotEmpty()) {
            sb.append("\nBlocked ").append(blockedHosts.values.sum()).append(" requests to ")
                .append(blockedHosts.size).append(" ad/popup networks:\n")
            blockedHosts.forEach { (h, n) -> sb.append("  ").append(h).append(" ×").append(n).append('\n') }
        }
        return sb.toString()
    }

    fun host(url: String): String = try { URI(url).host ?: url } catch (_: Exception) { url }

    /** "cdn.example.com/…/index.m3u8" — enough to tell streams apart without the long tokens. */
    fun short(url: String): String {
        val h = host(url)
        val last = url.substringBefore('?').trimEnd('/').substringAfterLast('/').take(40)
        return if (last.isEmpty() || last == h) h else "$h/…/$last"
    }
}
