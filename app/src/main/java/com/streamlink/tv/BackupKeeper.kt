package com.streamlink.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import kotlin.concurrent.thread

/** A ready backup: a verified stream address of another source of the same game, found [foundAt] (epoch ms). */
data class BackupResult(
    val url: String,
    val kind: BackupKind,
    val label: String,
    /** The page opened for a PAGE backup (its player page, else its address); the address itself for STREAM. */
    val target: String,
    /** The referer to open [target] with (as the Source list entry sends it); null when none. */
    val referer: String?,
    /** Verified sources found (PAGE: from the backup's page; STREAM: the one source, refreshed). Never empty. */
    val set: StreamSet,
    val foundAt: Long,
) {
    val host: String get() = Report.host(set.sources.first().url)
}

/**
 * Owned by PlayerActivity. While a stream plays with a healthy buffer, keeps fresh addresses for the top 1-2 BACKUP sources of the
 * same game (see [BackupChoice]): a page-type backup gets a quiet light look in its own hidden browser (own [StreamSniffer], destroyed after each
 * run, one run at a time), a playable source of the page just a quick playlist check. When the stream drops, the player asks [ready]
 * for them (see PlayerActivity.recover). Pure rules: [BackupTiming], [BackupPacing], [BackupRecovery]. Quiet: writes only one report
 * line per refresh. Main thread only (callbacks of the background checks are posted back).
 */
class BackupKeeper(
    private val context: Context,
    holder: ViewGroup,
    /** The set being watched and the index of the source playing, or null when there is none. */
    private val current: () -> Pair<StreamSet, Int>?,
    private val health: (Long) -> BackupTiming.Health,
) {
    private val main = Handler(Looper.getMainLooper())
    private val sniffer = StreamSniffer(context, holder).also { it.timed = false; it.quiet = true }
    private val pacing = BackupPacing()
    private val results = LinkedHashMap<String, BackupResult>()
    private var ticking = false
    private var runGen = 0
    private var runKey: String? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            tickOnce()
            main.postDelayed(this, TICK_MS)
        }
    }

    private val watchdog = Runnable {
        val k = runKey ?: return@Runnable
        stopRun()
        pacing.end(k, false, baseFor(k), System.currentTimeMillis())
    }

    /** A new stream set (new link or reconnect that replaced it): forget everything. */
    fun onNewSet() {
        pause()
        results.clear()
        pacing.clear()
    }

    /** A picture is playing: (re)start the schedule, first run after [BackupTiming.FIRST_DELAY_MS]. */
    fun onPicture() {
        if (BackupWatch.off) return
        pacing.sync(picks().map { it.url }, System.currentTimeMillis())
        pacing.pictureShown(System.currentTimeMillis())
        if (!ticking) {
            ticking = true
            main.postDelayed(tick, TICK_MS)
        }
    }

    /** Recovery, a restart of the player or leaving the screen: stop whatever runs and wait for the next picture. */
    fun pause() {
        ticking = false
        main.removeCallbacks(tick)
        stopRun()
    }

    fun stop() {
        pause()
        sniffer.cancel()
    }

    /** The player stalled. Turns the keeper off for the rest of the watch when a run was active or had just ended. */
    fun noteStall() {
        if (pacing.stall(System.currentTimeMillis())) {
            Report.log("Backup refresh paused for this watch (it may have caused a stall)")
            pause()
        }
    }

    /** The page replaced the playing set (a reconnect): addresses saved for STREAM backups may be gone; PAGE results stay. */
    fun dropStreamResults() {
        results.values.removeAll { it.kind == BackupKind.STREAM }
    }

    /** Ready backups, best (list order) first, with those whose pages went dead and the playing address left out. */
    fun ready(now: Long): List<BackupResult> {
        val order = picks().map { it.url }
        val cur = current()
        val playingUrl = cur?.first?.sources?.getOrNull(cur.second)?.url
        val list = results.values.filter {
            !AutoSwitch.isDead(it.target, now) && !AutoSwitch.isDead(it.url, now) &&
                !BackupReady.isPlaying(it.url, it.set.sources.map { s -> s.url }, playingUrl)
        }
        return list.sortedBy { order.indexOf(it.url).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }

    fun known(now: Long): List<BackupRecovery.Known> =
        ready(now).map { BackupRecovery.Known(it.url, now - it.foundAt, ServerMemory.lifetimeFor(it.host)) }

    private fun picks(): List<BackupChoice.Pick> {
        val (s, playing) = current() ?: return emptyList()
        val now = System.currentTimeMillis()
        return BackupChoice.pick(s.sources, playing, s.pageUrl, { AutoSwitch.isDead(it, now) })
    }

    private fun baseFor(url: String): Long {
        val r = results[url]
        return BackupTiming.interval(if (r != null) ServerMemory.lifetimeFor(r.host) else ServerMemory.Lifetime(ServerMemory.LifeKind.UNKNOWN))
    }

    private fun tickOnce() {
        if (BackupWatch.off) { pause(); return }
        val now = System.currentTimeMillis()
        val all = picks()
        pacing.sync(all.map { it.url }, now)
        val key = pacing.next(now, health(now)) ?: return
        val pick = all.firstOrNull { it.url == key } ?: return
        startRun(pick)
    }

    private fun candidate(s: Source) = StreamCandidate(s.url, s.headers, s.referers, s.label, s.isDefaultLink)

    private fun startRun(pick: BackupChoice.Pick) {
        val (cur, _) = current() ?: return
        val src = cur.sources.getOrNull(pick.index) ?: return
        val gen = ++runGen
        runKey = pick.url
        pacing.begin()
        main.postDelayed(watchdog, BackupTiming.RUN_LIMIT_MS)
        if (pick.kind == BackupKind.STREAM) {
            thread(isDaemon = true, name = "backup-check") {
                val ok = try { Verifier.quickCheck(candidate(src), 3000L) { } } catch (_: Exception) { null }
                main.post {
                    if (gen != runGen) return@post
                    if (ok == null) {
                        finishRun(pick.url, null)
                    } else {
                        val fresh = ok.copy(label = src.label)
                        finishRun(pick.url, BackupResult(pick.url, BackupKind.STREAM, src.label, src.url, null,
                            StreamSet(cur.pageUrl, listOf(fresh)), System.currentTimeMillis()))
                    }
                }
            }
            return
        }
        val target = BackupChoice.target(src)
        val referer = src.referers.firstOrNull { it.isNotBlank() }
        // While the page is looked at, re-check the address found last time: how old it is and whether it still works teaches the lifetime.
        results[pick.url]?.let { prev ->
            thread(isDaemon = true, name = "backup-learn") {
                val first = prev.set.sources.firstOrNull() ?: return@thread
                val age = System.currentTimeMillis() - prev.foundAt
                if (age < ServerMemory.MIN_SAMPLE_AGE_MS) return@thread
                val ok = try { Verifier.quickCheck(candidate(first), 3000L) { } != null } catch (_: Exception) { return@thread }
                ServerMemory.recordAge(Report.host(first.url), age, ok)
            }
        }
        sniffer.sniff(target, referer = referer, light = true, useRemembered = false) { found ->
            if (gen != runGen) return@sniff
            val good = found.sources.filter { it.verified && !it.openPage }
            if (good.isEmpty()) {
                finishRun(pick.url, null)
            } else {
                finishRun(pick.url, BackupResult(pick.url, BackupKind.PAGE, src.label, target, referer,
                    StreamSet(found.pageUrl, good, requestedUrl = found.requestedUrl), System.currentTimeMillis()))
            }
        }
    }

    private fun finishRun(url: String, result: BackupResult?) {
        main.removeCallbacks(watchdog)
        runKey = null
        val now = System.currentTimeMillis()
        if (result != null) {
            results[url] = result
            pacing.end(url, true, BackupTiming.interval(ServerMemory.lifetimeFor(result.host)), now)
            Report.log("Backup ready: ${result.label} (${result.host}), checked just now")
        } else {
            pacing.end(url, false, baseFor(url), now)
        }
        sniffer.cancel() // the hidden browser is destroyed after every run
    }

    private fun stopRun() {
        if (runKey == null) return
        runKey = null
        runGen++
        main.removeCallbacks(watchdog)
        pacing.abort()
        sniffer.cancel()
    }

    private companion object {
        const val TICK_MS = 3_000L
    }
}
