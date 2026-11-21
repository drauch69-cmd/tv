package com.streamlink.tv

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Playlists already downloaded while checking streams. The player takes them from here instead
 * of downloading again, which matters for links that only work once.
 */
object PrefetchCache {
    /** A saved live playlist goes stale fast: older than this, download it fresh instead. */
    private const val MAX_AGE_MS = 15_000L

    private class Entry(val bytes: ByteArray, val finalUrl: String, val at: Long)

    private val map = ConcurrentHashMap<String, Entry>()

    fun put(url: String, bytes: ByteArray, finalUrl: String) {
        if (map.size > 20) map.clear()
        map[url] = Entry(bytes, finalUrl, System.currentTimeMillis())
    }

    fun take(url: String): Pair<ByteArray, String>? {
        val e = map.remove(url) ?: return null
        if (System.currentTimeMillis() - e.at > MAX_AGE_MS) return null
        return Pair(e.bytes, e.finalUrl)
    }

    fun clear() = map.clear()
}

/** Checks each found stream really is a playlist, then ranks them best first. */
object Verifier {

    private class Resp(val code: Int, val body: ByteArray, val finalUrl: String)

    private class Checked(
        val cand: StreamCandidate,
        val ok: Boolean,
        val master: Boolean,
        val dash: Boolean,
        val live: Boolean,
        val adLike: Boolean,
        val workingReferer: String?,
        val variants: List<String>,
        /** Why it failed, when it did (what the server or the page answered); "" when it passed. */
        val note: String = "",
        /** Content type to tell the player when the address does not say it (see [Source.mime]); "" otherwise. */
        val mime: String = "",
    ) {
        fun withCand(c: StreamCandidate) = Checked(c, ok, master, dash, live, adLike, workingReferer, variants, note, mime)

        val score: Int
            get() = (if (ok) 10 else 0) + (if (master) 3 else 0) + (if (live) 2 else 0) + (if (adLike) -10 else 0)
    }

    private const val TOTAL_LIMIT_MS = 10_000L
    private const val PROBE_TIMEOUT_MS = 1500
    private const val PROBE_PLAYLIST_BYTES = 64 * 1024
    private const val PROBE_PIECE_BYTES = 16 * 1024
    private const val MIME_HLS = "application/x-mpegURL"
    private const val MIME_DASH = "application/dash+xml"
    /** Results of early single checks (possible playlists), taken by [rank] so the address is not fetched twice. */
    private class Pre(val checked: Checked, val at: Long)
    private val pre = ConcurrentHashMap<String, Pre>()
    private const val PRE_MAX_AGE_MS = 20_000L

    /**
     * Content check of one possible playlist (an address with no m3u8/.mpd in it): true only when the body starts
     * with #EXTM3U or holds <MPD (the same test as every stream). A pass is kept briefly for [rank].
     */
    fun confirmPossible(c: StreamCandidate, log: (String) -> Unit = { Report.log(it) }): Boolean {
        val r = check(c, log)
        if (r.ok) {
            if (pre.size > 20) pre.clear()
            pre[c.url] = Pre(r, System.currentTimeMillis())
        }
        return r.ok
    }

    /** Early checks still running, so [rank] waits for them instead of fetching the same address a second time. */
    private val inflight = ConcurrentHashMap<String, java.util.concurrent.FutureTask<Checked>>()

    /**
     * Early check of the first stream the sniffer sees (started the moment it is found, before the wait for siblings
     * is over). Call off the main thread. Says whether it is a working non-ad playlist, and whether that is adaptive (master/DASH). A pass is kept briefly for [rank],
     * and so is a failure (rank reuses it; no second fetch).
     */
    fun precheck(c: StreamCandidate, log: (String) -> Unit = { Report.log(it) }): EarlyFinish.State {
        val task = java.util.concurrent.FutureTask(Callable { check(c, log) })
        if (inflight.putIfAbsent(c.url, task) != null) return EarlyFinish.State.BAD
        try {
            task.run()
            val r = task.get()
            // Failed results are kept too (same age rule), so [rank] does not fetch and log the same failure again.
            if (pre.size > 20) pre.clear()
            pre[c.url] = Pre(r, System.currentTimeMillis())
            return when {
                !r.ok || r.adLike -> EarlyFinish.State.BAD
                r.master || r.dash -> EarlyFinish.State.GOOD_ADAPTIVE
                else -> EarlyFinish.State.GOOD_SINGLE
            }
        } catch (e: Exception) {
            return EarlyFinish.State.BAD
        } finally {
            inflight.remove(c.url)
        }
    }

    private fun takePre(c: StreamCandidate): Checked? {
        inflight[c.url]?.let { try { it.get(TOTAL_LIMIT_MS, TimeUnit.MILLISECONDS) } catch (_: Exception) {} }
        val p = pre.remove(c.url) ?: return null
        if (System.currentTimeMillis() - p.at > PRE_MAX_AGE_MS) return null
        return p.checked.withCand(c)
    }

    private val adWords = listOf("/ads/", "/ad/", "vast", "preroll", "pre-roll", "advert", "doubleclick", "imasdk")

    /**
     * [rank], plus the stuck-engine rule: when every candidate (and at least one; possible playlists left out) got no HTTP
     * answer at all on Chrome's engine, the engine is restarted (off the main thread: call this off it; rate limited by
     * [ChromeNet.canReset]; never when [allowRestart] is false: light/quiet runs beside a playing stream) and those candidates are checked once more. Still no answer: they come back marked
     * [NoAnswer.FINAL], which the sniffer treats as no stream instead of playing them unchecked.
     */
    fun rankWithRestart(cands: List<StreamCandidate>, allowRestart: Boolean = true, log: (String) -> Unit = { Report.log(it) }): List<Source> {
        val first = rank(cands, log)
        val possible = cands.filter { it.possible }.map { it.url }.toSet()
        if (!NoAnswer.allSilent(first.filter { it.url !in possible })) return first
        if (ChromeNet.engine == null || !allowRestart) return first
        if (!ChromeNet.canReset()) {
            // Restarted less than a minute ago: the silence is about the server, not the engine. Do not play them unchecked.
            if (!ChromeNet.recentlyReset()) return first
            log("Still no answer from the stream server (the engine was restarted a moment ago)")
            return first.map { if (it.url !in possible) NoAnswer.markFinal(listOf(it)).first() else it }
        }
        log("No answer from the stream server; restarting Chrome's network engine and checking again")
        val how = ChromeNet.reset()
        if (how == null) {
            log("Chrome's network engine could not be restarted")
            return first
        }
        log("Chrome's network engine restarted ($how)")
        val again = rank(cands, log, fresh = true)
        val rel = again.filter { it.url !in possible }
        if (NoAnswer.allSilent(rel)) {
            log("Still no answer from the stream server after the restart")
            return again.map { if (it.url !in possible) NoAnswer.markFinal(listOf(it)).first() else it }
        }
        log("The stream server answers after the restart")
        return again
    }

    fun rank(cands: List<StreamCandidate>, log: (String) -> Unit = { Report.log(it) }, fresh: Boolean = false): List<Source> {
        // Check all candidates at once, with a hard overall time limit, so a slow site can't keep
        // the TV on "Finding the video…" for minutes.
        val pool = Executors.newFixedThreadPool(cands.size.coerceIn(1, 12))
        // The answer of each try so far, per candidate: a candidate cut off by the time limit is judged on what it already
        // answered (a slow 404 is offline, not "too slow"). Checks still running after this returns write no more lines.
        val answers = cands.map { java.util.Collections.synchronizedList(ArrayList<Int>()) }
        val over = java.util.concurrent.atomic.AtomicBoolean(false)
        val quietLog: (String) -> Unit = { m -> if (!over.get()) log(m) }
        // Every stream here is on a server remembered as down: the memory alone must not sink the page (the server may be
        // back), so each is tried once for real. A fresh failure is remembered again.
        val retryDead = DeadHostText.allRemembered(cands.map { Report.host(it.url) }) { DeadHosts.isDead(it) }
        if (retryDead) log("Every stream here is on a server remembered as down; trying them once anyway")
        val futures = try {
            pool.invokeAll(cands.mapIndexed { i, c -> Callable { (if (fresh || retryDead) { pre.remove(c.url); null } else takePre(c)) ?: check(c, quietLog, answers[i], over, retryDead) } }, TOTAL_LIMIT_MS, TimeUnit.MILLISECONDS)
        } finally {
            pool.shutdownNow()
        }
        val partial = answers.map { a -> synchronized(a) { a.toList() } }
        over.set(true)
        val checked = cands.mapIndexed { i, c ->
            val f = futures[i]
            try {
                if (f.isCancelled) {
                    val note = DeadStream.cutOffNote(partial[i])
                    log("✗ ${Report.short(c.url)}: $note")
                    unchecked(c, note)
                } else {
                    f.get()
                }
            } catch (e: Exception) {
                log("✗ ${Report.short(c.url)}: ${(e.cause ?: e).javaClass.simpleName}")
                unchecked(c, "checking failed")
            }
        }

        // Quality levels inside an adaptive playlist aren't separate sources.
        val children = HashSet<String>()
        checked.filter { it.ok && it.master }.forEach { m ->
            m.variants.forEach { v -> children.add(v); children.add(v.substringBefore('?')) }
        }
        var list = checked.filter { it.cand.url !in children && it.cand.url.substringBefore('?') !in children }
        if (list.isEmpty()) list = checked

        if (list.any { it.ok && !it.adLike }) list = list.filter { !it.adLike }

        // Best first; but the stream of the page's own (default) link stays first when it works.
        val ranked = LinkOrder.defaultFirst(list.sortedByDescending { it.score }) { it.cand.isDefault && it.ok && !it.adLike }.take(8)
        val labels = LinkOrder.uniqueLabels(ranked.map { c ->
            val kind = when {
                c.dash -> "DASH"
                c.master -> "adaptive"
                else -> "single quality"
            }
            LinkOrder.sourceLabel(c.cand.linkLabel, Report.host(c.cand.url), kind, c.ok)
        })
        return ranked.mapIndexed { i, c ->
            val refs = if (c.workingReferer != null) {
                listOf(c.workingReferer) + c.cand.referers.filter { it != c.workingReferer }
            } else c.cand.referers
            Source(c.cand.url, c.cand.headers, refs, labels[i], c.cand.isDefault, verified = c.ok, note = if (c.ok) "" else c.note, mime = c.mime)
        }
    }

    /**
     * One quick check of a remembered source (see [LastAddress]): true live playlist (HLS/DASH, live, not ad-like) within
     * [limitMs]. Returns the source with the referer that worked first and its content type, or null. Call off the main thread.
     */
    fun quickCheck(c: StreamCandidate, limitMs: Long = 3000L, log: (String) -> Unit = { Report.log(it) }): Source? =
        quickCheckStatus(c, limitMs, log).source

    /** Result of [quickCheckStatus]: the source when it passed, else null, plus the HTTP status that explains a failure (see [QuickStatus]). */
    class QuickResult(val source: Source?, val status: Int)

    /** Same as [quickCheck], but also returns the HTTP status answered (a refusal code if any try was refused, else the last HTTP status; 0 = no answer / not a playlist). */
    fun quickCheckStatus(c: StreamCandidate, limitMs: Long = 3000L, log: (String) -> Unit = { Report.log(it) }): QuickResult {
        val answers = java.util.Collections.synchronizedList(ArrayList<Int>())
        val over = java.util.concurrent.atomic.AtomicBoolean(false)
        val task = java.util.concurrent.FutureTask(Callable { check(c, { m -> if (!over.get()) log(m) }, answers, over) })
        val t = Thread(task, "quick-check")
        t.isDaemon = true
        t.start()
        val r = try {
            task.get(limitMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            over.set(true)
            task.cancel(true)
            return QuickResult(null, Refusal.statusOf(synchronized(answers) { answers.toList() }))
        }
        if (!r.ok || !r.live || r.adLike) return QuickResult(null, Refusal.statusOf(synchronized(answers) { answers.toList() }))
        val refs = if (r.workingReferer != null) listOf(r.workingReferer) + c.referers.filter { it != r.workingReferer } else c.referers
        return QuickResult(Source(c.url, c.headers, refs, c.linkLabel, c.isDefault, verified = true, mime = r.mime), 200)
    }

    private fun check(
        c: StreamCandidate,
        log: (String) -> Unit,
        /** Filled with the answer of each try as it comes (see [rank]); [over] says the caller stopped waiting. */
        answers: MutableList<Int> = java.util.Collections.synchronizedList(ArrayList<Int>()),
        over: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
        /** Try servers remembered as down anyway (every candidate on the page is on one; see [rank]). */
        ignoreDead: Boolean = false,
    ): Checked {
        // Up to 5 real referers (the finder already caps each list), then the no-referer attempt last.
        // The referer this stream server accepted last time goes first (see [ServerMemory]).
        val host = Report.host(c.url)
        // A remembered ad/tracker referer (saved by builds before 234) is ignored rather than tried first.
        val remembered = ServerMemory.refererFor(host)?.takeUnless { AdBlock.isBlockedHost(Report.host(it)) }
        val refs = ServerMemory.refererOrder(c.referers, remembered)
        if (remembered != null && refs.firstOrNull() == remembered) log("Trying the referer this server accepted last time")
        if (!ignoreDead && DeadHosts.isDead(host)) {
            answers.add(0)
            log("✗ ${Report.short(c.url)}: ${DeadNotes.SERVER_DOWN}")
            return Checked(c, false, false, false, false, looksLikeAd(c.url), null, emptyList(), DeadNotes.SERVER_DOWN)
        }
        var note = "no response"
        val tried = ArrayList<String>()
        var broken = false
        var why: String? = null
        // Every try answered 404/410: the address is gone (the stream is offline), not refused (see [DeadStream]).
        // The answer of each try: the HTTP status, or 0 when the fetch threw (no answer at all; see [DeadStream.goneVerdict]).
        for (ref in refs) {
            if (over.get() || DeadStream.enough(synchronized(answers) { answers.toList() })) break
            tried.add(Headers.describeReferer(ref))
            var r = fetch(c.url, c.headers, ref, log) { e -> if (DeadHostText.brokenSecureLink(e)) { broken = true; why = DeadHostText.sslReason(e) } }
            if (r == null && broken && ChromeNet.engine != null) {
                // Chrome's engine was refused: the page's browser often still gets through, so try Android's own
                // networking once before calling the server down (crackstreams ESPN, build 287).
                NetDiag.once(host, log)
                val s = fetch(c.url, c.headers, ref, log, standard = true)
                if (s != null) {
                    log("$host refused Chrome's network engine but answered standard networking; the player will use that")
                    FastStart.recordEngine(host, FastStart.STANDARD)
                    DeadHosts.clear(host)
                    broken = false
                    r = s
                } else {
                    log("$host refused standard networking too")
                }
            }
            if (r == null) {
                answers.add(0)
                // A broken secure connection is the server, not the referer: the other referers would fail the same way.
                if (broken) {
                    if (DeadHosts.record(host) || ignoreDead) log("$host's secure connection is broken ($why); remembering it as down for a few minutes")
                    note = DeadNotes.SERVER_DOWN
                    break
                }
                continue
            }
            answers.add(r.code)
            if (r.code !in 200..299) {
                note = "server said ${r.code}"
                continue
            }
            val text = String(r.body, Charsets.UTF_8)
            val t = text.trimStart('\uFEFF', ' ', '\r', '\n', '\t')
            val isHls = t.startsWith("#EXTM3U")
            val isDash = !isHls && text.contains("<MPD")
            if (!isHls && !isDash) {
                // What came back instead, for the report: size and the first characters (non-text as ·).
                val head = r.body.take(24).joinToString("") { b ->
                    val ch = (b.toInt() and 0xFF).toChar()
                    if (ch in ' '..'~') ch.toString() else "·"
                }
                val what = "${r.body.size} bytes, starts \"$head\"" + if (r.finalUrl != c.url) ", after a redirect" else ""
                note = (if (t.startsWith("<")) "got a web page instead of video" else "not a playlist") + " ($what)"
                continue
            }
            PrefetchCache.put(c.url, r.body, r.finalUrl)
            val master = isHls && text.contains("#EXT-X-STREAM-INF")
            val live = isDash || master || !text.contains("#EXT-X-ENDLIST")
            val duration = if (isHls && !master) {
                Regex("#EXTINF:\\s*([0-9.]+)").findAll(text).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
            } else 0.0
            val adLike = looksLikeAd(c.url) || (!live && duration < 90.0)
            val variants = if (master) variantUrls(text, r.finalUrl) else emptyList()
            val kind = when {
                isDash -> "DASH"
                master -> "adaptive"
                else -> "single quality"
            }
            DeadHosts.clear(host)
            DeadHosts.seen(host)
            // One level down: the first quality level and the first video piece must reach their servers too (many sites
            // share piece servers, and a broken one fails every source behind it). Only a broken secure connection fails
            // the check; a slow or refused answer leaves it to the player.
            if (live && !adLike && isHls && !over.get()) {
                val down = probeDown(text, r.finalUrl, master, variants, c.headers, ref, log, ignoreDead)
                if (down != null) {
                    log("✗ ${Report.short(c.url)}: $down")
                    return Checked(c, false, master, false, live, adLike, null, variants, down)
                }
            }
            if (ref.isNotEmpty() && ref in c.rememberable) ServerMemory.recordReferer(host, ref)
            val refText = if (ref.isEmpty()) "none" else Report.short(ref)
            log("✓ ${Report.short(c.url)}: $kind, ${if (live) "live" else "recorded"} (referer: $refText)" +
                if (adLike) " — looks like an ad" else "")
            // An address that does not say what it is (no m3u8/.mpd in it): tell the player what the content is.
            val mime = if (StreamUrls.classify(c.url).first) "" else if (isDash) MIME_DASH else MIME_HLS
            return Checked(c, true, master, isDash, live, adLike, ref, variants, mime = mime)
        }
        // A refusal: say which referers were tried (hosts only, never tokens).
        if (note.startsWith("server said 403")) note += "; tried: " + tried.joinToString(", ")
        val seen = synchronized(answers) { answers.toList() }
        val goneCode = DeadStream.goneVerdict(seen)
        if (goneCode != 0) note = DeadStream.goneNote(goneCode)
        else if (NoAnswer.silent(seen)) note = NoAnswer.SILENT
        // Every referer refused (and every try answered): the player's requests to this host will be refused too.
        if (Refusal.allRefused(seen)) {
            RefusedHosts.record(host)
            note += Refusal.REFUSED_MARK
        }
        log("✗ ${Report.short(c.url)}: $note")
        return Checked(c, false, false, false, false, looksLikeAd(c.url), null, emptyList(), note)
    }

    private fun unchecked(c: StreamCandidate, note: String) =
        Checked(c, false, false, false, false, looksLikeAd(c.url), null, emptyList(), note)

    private fun looksLikeAd(url: String): Boolean {
        val u = url.lowercase()
        return adWords.any { u.contains(it) }
    }

    private fun variantUrls(text: String, base: String): List<String> {
        val out = ArrayList<String>()
        val lines = text.lines()
        for (i in lines.indices) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
            for (j in i + 1 until lines.size) {
                val l = lines[j].trim()
                if (l.isEmpty() || l.startsWith("#")) continue
                try { out.add(URI(base).resolve(l).toString()) } catch (_: Exception) { out.add(l) }
                break
            }
        }
        return out
    }

    /**
     * Fetch the first quality level (for an adaptive playlist) and the first bytes of the first video piece with the
     * referer that worked. Returns why the stream can't play (a server on the way is down), or null when it may.
     */
    private fun probeDown(
        text: String, base: String, master: Boolean, variants: List<String>,
        headers: Map<String, String>, ref: String, log: (String) -> Unit, ignoreDead: Boolean = false,
    ): String? {
        var mediaText = text
        var mediaBase = base
        if (master) {
            val v = variants.firstOrNull() ?: return null
            when (val p = probe(v, headers, ref, PROBE_PLAYLIST_BYTES, log, ignoreDead)) {
                is Probe.Down -> return p.note
                is Probe.Got -> {
                    val t = String(p.resp.body, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n', '\t')
                    if (!t.startsWith("#EXTM3U")) return null
                    mediaText = t
                    mediaBase = p.resp.finalUrl
                }
                Probe.NoVerdict -> return null
            }
        }
        val piece = DeadNotes.firstPiece(mediaText, mediaBase) ?: return null
        return (probe(piece, headers, ref, PROBE_PIECE_BYTES, log, ignoreDead) as? Probe.Down)?.note
    }

    private sealed class Probe {
        class Got(val resp: Resp) : Probe()
        class Down(val note: String) : Probe()
        object NoVerdict : Probe()
    }

    private fun probe(url: String, headers: Map<String, String>, ref: String, max: Int, log: (String) -> Unit, ignoreDead: Boolean = false): Probe {
        val h = Report.host(url)
        if (!ignoreDead && DeadHosts.isDead(h)) return Probe.Down(DeadNotes.downstreamDown(h))
        var broken = false
        var why: String? = null
        var r = fetch(url, headers, ref, log, max, PROBE_TIMEOUT_MS) { e -> if (DeadHostText.brokenSecureLink(e)) { broken = true; why = DeadHostText.sslReason(e) } }
        if (r == null && broken && ChromeNet.engine != null) {
            NetDiag.once(h, log)
            val s = fetch(url, headers, ref, log, max, PROBE_TIMEOUT_MS, standard = true)
            if (s != null) {
                log("$h refused Chrome's network engine but answered standard networking; the player will use that")
                FastStart.recordEngine(h, FastStart.STANDARD)
                broken = false
                r = s
            } else {
                log("$h refused standard networking too")
            }
        }
        if (r == null) {
            if (!broken) return Probe.NoVerdict
            if (DeadHosts.record(h) || ignoreDead) log("$h's secure connection is broken ($why); remembering it as down for a few minutes")
            return Probe.Down(DeadNotes.downstreamDown(h))
        }
        if (r.code !in 200..299) return Probe.NoVerdict
        DeadHosts.clear(h)
        DeadHosts.seen(h)
        return Probe.Got(r)
    }

    private fun fetch(
        url: String, headers: Map<String, String>, referer: String, log: (String) -> Unit,
        max: Int = 512 * 1024, timeoutMs: Int = 4000, onError: ((Exception) -> Unit)? = null,
        standard: Boolean = false,
    ): Resp? {
        var conn: HttpURLConnection? = null
        return try {
            conn = if (standard) java.net.URL(url).openConnection() as HttpURLConnection else ChromeNet.open(url)
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            for ((k, v) in headers) {
                if (k.equals("Referer", true) || k.equals("Origin", true) || k.equals("X-Requested-With", true)) continue
                conn.setRequestProperty(k, v)
            }
            if (referer.isNotEmpty()) {
                conn.setRequestProperty("Referer", referer)
                Headers.originFor(referer, Headers.capturedOrigin(headers))?.let { conn.setRequestProperty("Origin", it) }
            }
            val code = conn.responseCode
            val stream: InputStream? = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { readUpTo(it, max) } ?: ByteArray(0)
            Resp(code, body, conn.url.toString())
        } catch (e: Exception) {
            log("  ${Report.short(url)}${if (standard) " (standard networking)" else ""}: ${DeadHostText.sslReason(e) ?: e.javaClass.simpleName}")
            onError?.invoke(e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun readUpTo(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
