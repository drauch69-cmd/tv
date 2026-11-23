package com.streamlink.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Following sites that move to a new domain (lab, 2026-11-22). After every list read:
 * - the read worked but the page ended up on another site (the old address now redirects there): every list on the
 *   old site moves to the new one at once;
 * - the read failed (or came back empty twice): look where the old address goes now, and which of the site's name on
 *   other endings ([SiteKey.candidates]) answers; try up to [MAX_TRIALS] of them as a real list read in the hidden
 *   browser ([ListReads.probe]); the first that shows events becomes the site's new domain.
 * Lists are never removed here; a site that keeps failing with no move found is shown as "Not working" ([SiteHealth]).
 * A failed read while the internet itself is down doesn't count.
 */
object SiteMoves {
    private const val MAX_TRIALS = 3
    private const val CHECK_TIMEOUT_MS = 4_000
    private const val CHECKERS = 6

    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "site-moves").apply { isDaemon = true } }
    /** Sites with a move check running, so two lists of one site don't start two. Main thread only. */
    private val checking = HashSet<String>()

    /** Main thread: one list read finished. */
    fun afterRead(ctx: Context, list: SavedList, result: ListResult) {
        val app = ctx.applicationContext
        val site = SiteKey.ofUrl(list.url) ?: return
        val read = SiteHealth.kind(result)
        if (read == SiteHealth.Read.OK) {
            val landedHost = SiteKey.hostOf(result.pageUrl)
            val landed = SiteKey.of(landedHost)
            if (landedHost != null && landed != null && landed != site) {
                commit(app, site, landedHost, "its old address now opens the new one")
                return
            }
        }
        work.execute {
            try {
                val now = System.currentTimeMillis()
                if (read == SiteHealth.Read.FAILED && !Pinger.reachable()) {
                    Report.log("Site check: not counting $site's failed read (the internet is down)")
                    return@execute
                }
                val e = SiteHealth.record(app, site, read, now)
                if (!SiteHealth.wantsMoveCheck(e, read, now)) return@execute
                SiteHealth.markMoveCheck(app, site, now)
                main.post { if (checking.add(site)) work.execute { find(app, list, site) } }
            } catch (_: Exception) { }
        }
    }

    /** Background: the addresses worth a trial read, best first. */
    private fun find(app: Context, list: SavedList, site: String) {
        val trials = ArrayList<String>()
        try {
            val page = QuickFetch.download(app, list.url)
            val s = SiteKey.ofUrl(page.url)
            if (s != null && s != site) trials.add(page.url)
        } catch (_: Exception) { }
        val names = SiteKey.candidates(site)
        val alive = answering(app, names)
        for (h in alive) {
            if (trials.size >= MAX_TRIALS) break
            SiteKey.withHost(list.url, h)?.let { if (it !in trials) trials.add(it) }
        }
        Report.log(
            if (trials.isEmpty()) "Site check: $site isn't reading and no new address answered (${names.size} endings checked)"
            else "Site check: $site isn't reading; trying ${trials.joinToString { Report.host(it) }}"
        )
        main.post { tryNext(app, list.id, site, trials, tried = 0) }
    }

    /** Main thread: trial-read the next address; the first with events wins. */
    private fun tryNext(app: Context, listId: String, site: String, trials: List<String>, tried: Int) {
        val still = SavedLists.get(app, listId)
        if (trials.isEmpty() || still == null || SiteKey.ofUrl(still.url) != site) {
            if (trials.isEmpty() && tried > 0) Report.log("Site check: no working new address for $site")
            checking.remove(site)
            return
        }
        val url = trials.first()
        ListReads.probe(app, url) { r ->
            if (SiteHealth.kind(r) == SiteHealth.Read.OK) {
                checking.remove(site)
                commit(app, site, SiteKey.hostOf(r.pageUrl) ?: SiteKey.hostOf(url) ?: return@probe, "found by trying its name on other endings")
            } else {
                Report.log("Site check: ${Report.host(url)} has no events (${r.error ?: "empty"})")
                tryNext(app, listId, site, trials.drop(1), tried + 1)
            }
        }
    }

    /** Main thread: move every list on [oldSite] to [newHost], carry the site's memory over, and read them again. */
    private fun commit(app: Context, oldSite: String, newHost: String, why: String) {
        val newSite = SiteKey.of(newHost) ?: return
        if (newSite == oldSite) return
        val n = SavedLists.moveSite(app, oldSite, newHost)
        if (n == 0) return
        HostMemory.rename(app, oldSite, newSite)
        work.execute { try { SiteHealth.moved(app, oldSite, newSite, System.currentTimeMillis()) } catch (_: Exception) { } }
        Report.log("Moved: $oldSite → $newSite ($n list${if (n == 1) "" else "s"}; $why)")
        for (l in SavedLists.all(app)) if (SiteKey.ofUrl(l.url) == newSite) ListReads.request(app, l.id, ListReadLogic.Reason.MANUAL)
        ListEvents.changed()
    }

    /** Background: which of [hosts] answer a web request at all (a challenge page counts), in [hosts] order. */
    private fun answering(app: Context, hosts: List<String>): List<String> {
        if (hosts.isEmpty()) return emptyList()
        val agent = try { android.webkit.WebSettings.getDefaultUserAgent(app).replace("; wv", "") } catch (_: Exception) { "Mozilla/5.0" }
        val pool = Executors.newFixedThreadPool(CHECKERS)
        return try {
            val futures = hosts.map { h -> pool.submit(Callable { answers(h, agent) }) }
            hosts.filterIndexed { i, _ ->
                try { futures[i].get((CHECK_TIMEOUT_MS * 2).toLong(), TimeUnit.MILLISECONDS) } catch (_: Exception) { false }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun answers(host: String, agent: String): Boolean {
        val c = try { URL("https://$host/").openConnection() as HttpURLConnection } catch (_: Exception) { return false }
        return try {
            c.instanceFollowRedirects = false
            c.connectTimeout = CHECK_TIMEOUT_MS
            c.readTimeout = CHECK_TIMEOUT_MS
            c.setRequestProperty("User-Agent", agent)
            c.setRequestProperty("Accept", "text/html,*/*;q=0.8")
            val code = c.responseCode
            when (code) {
                in 200..299, 403, 503 -> true
                in 300..399 -> SiteKey.sameName(SiteKey.hostOf(c.getHeaderField("Location")) ?: host, host)
                else -> false
            }
        } catch (_: Exception) { false } finally { c.disconnect() }
    }
}
