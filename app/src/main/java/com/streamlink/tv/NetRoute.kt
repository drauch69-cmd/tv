package com.streamlink.tv

import java.util.concurrent.ConcurrentHashMap

/**
 * Pure decision logic for the player's network use (no Android types, unit tested in NetRouteTest).
 * The player's requests go to the playlist's own host and, often, to a different host for the video pieces.
 * The two kinds are handled separately: headers, network engine, timeouts, and failure tracking.
 */
object NetHeaders {
    /** Piece requests get a longer read patience than playlists (big pieces); connect stays short so a dead host shows up before the stall check. */
    const val PLAYLIST_CONNECT_MS = 15_000
    const val PLAYLIST_READ_MS = 15_000
    const val PIECE_CONNECT_MS = 15_000
    const val PIECE_READ_MS = 30_000

    private val keepNames = setOf("referer", "origin", "accept-language", "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform")

    /**
     * The headers to send to a host other than the playlist's own. A browser sends only the page's Referer/Origin
     * and its own language / low-detail client hints cross-site; Cookie, Authorization and anything else captured
     * from the playlist request belong to the playlist host and are dropped. A captured Sec-Fetch-Site (which said
     * "same-origin" for the playlist) becomes the cross-site set a browser's request to another host carries.
     */
    fun forOtherHost(all: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var fetch = false
        for ((k, v) in all) {
            val l = k.lowercase()
            when {
                l in keepNames -> out[k] = v
                l == "sec-fetch-site" -> fetch = true
            }
        }
        if (fetch) {
            out["Sec-Fetch-Site"] = "cross-site"
            out["Sec-Fetch-Mode"] = "cors"
            out["Sec-Fetch-Dest"] = "empty"
        }
        return out
    }

    /** A request for a playlist (the source's own address, or one that says so); everything else counts as a video piece. */
    fun isPlaylist(uri: String, sourceUrl: String): Boolean {
        if (uri == sourceUrl) return true
        val l = uri.substringBefore('#').lowercase()
        val path = l.substringBefore('?')
        return path.endsWith(".m3u8") || path.endsWith(".mpd") || path.contains(".m3u8") && !path.endsWith(".ts") && !path.endsWith(".m4s")
    }
}

/** Which engine (Chrome's or standard) each host's requests use; the playlist host follows [playlistChrome]. */
class NetRoute(
    val sourceUrl: String,
    /** Normalized host ([ServerMemory.key]) of the playlist. */
    val playlistHost: String,
    private val refusesStandard: (String) -> Boolean = { false },
) {
    @Volatile var playlistChrome: Boolean = true
    private val overrides = ConcurrentHashMap<String, Boolean>()
    val watch = PieceWatch()

    fun isPlaylistHost(host: String) = host == playlistHost

    /** The engine this host's requests use right now (true = Chrome's). A host known to refuse standard networking never gets it. */
    fun usesChrome(host: String): Boolean {
        val want = if (host == playlistHost) playlistChrome else overrides[host] ?: playlistChrome
        return want || refusesStandard(host)
    }

    fun setPieceEngine(host: String, chrome: Boolean) {
        if (host != playlistHost) overrides[host] = chrome
    }
}

/** What to do about video pieces that fail while the playlist is fine. */
enum class PieceStep { RETRY_OTHER_ENGINE, SWITCH_SOURCE }

object PieceRecovery {
    /** First try the other engine for those pieces (once, if it is allowed); then go to another source. */
    fun next(alreadyRetried: Boolean, otherEngineAllowed: Boolean): PieceStep =
        if (!alreadyRetried && otherEngineAllowed) PieceStep.RETRY_OTHER_ENGINE else PieceStep.SWITCH_SOURCE
}

/**
 * Tracks playlist and piece requests of one source. Piece-only failure = for one host, pieces failed
 * ([minFails] or more), none from it ever completed, and a playlist refresh completed after the first failed
 * piece began (so the playlist host answered while the pieces hung) with no playlist failure since.
 * Thread safe (reports come from loader threads).
 */
class PieceWatch {
    private class Host {
        var fails = 0
        /** Failures the server answered with a refusal (401/403/451); see [Refusal]. */
        var refused = 0
        /** Failures the server answered with any HTTP status (not a timeout / I/O error). */
        var answered = 0
        var ok = 0
        var firstFailStart = 0L
        var retried = false
        var restarted = false
        var bytes = 0L
    }

    private val hosts = HashMap<String, Host>()
    private var playlistOkAt = 0L
    private var playlistFailAt = 0L
    private var lastPieceByteAt = 0L
    private var lastPieceBytes = 0L
    private var diagLeft = MAX_DIAG

    companion object {
        const val MAX_DIAG = 4
        /** Bytes arrived this recently = a piece is still coming. */
        const val PROGRESS_MS = 8_000L
    }

    private fun host(h: String) = hosts.getOrPut(h) { Host() }

    private var goneRun = 0

    private var silentRun = 0
    private var playlistRestarted = false

    @Synchronized fun playlistOk(now: Long) { playlistOkAt = now; goneRun = 0; silentRun = 0 }

    /** A playlist request failed; [status] is the HTTP status when the server answered (404/410 = gone). */
    @Synchronized fun playlistFailed(now: Long, status: Int = 0) {
        playlistFailAt = now
        goneRun = if (status == 404 || status == 410) goneRun + 1 else 0
        silentRun = if (status == 0) silentRun + 1 else 0
    }

    /** The latest playlist request(s) got no HTTP answer at all (timeout / I/O error) and none succeeded since. */
    @Synchronized fun playlistSilent(): Boolean = silentRun >= 1

    @Synchronized fun playlistRestarted(): Boolean = playlistRestarted

    /** Chrome's engine was rebuilt because the playlist got no answer: once per source; forget the silence so the next verdict is about the fresh engine. */
    @Synchronized fun markPlaylistRestarted() { playlistRestarted = true; silentRun = 0 }

    /** The playlist answered "not found / gone" on [times] consecutive requests with no success between. */
    @Synchronized fun playlistGone(times: Int = 2): Boolean = goneRun >= times

    @Synchronized fun clearGone() { goneRun = 0 }
    @Synchronized fun pieceOk(h: String) { host(h).ok++ }

    /** A piece failed; [status] is the HTTP status when the server answered (a refusal is not a timeout), 0 otherwise. */
    @Synchronized fun pieceFailed(h: String, startedAt: Long, status: Int = 0) {
        val e = host(h)
        if (e.fails == 0) e.firstFailStart = startedAt
        e.fails++
        if (status != 0) e.answered++
        if (Refusal.isRefusal(status)) e.refused++
    }

    /** Some failed piece of [h] got an HTTP answer: restarting the network engine would not change it. */
    @Synchronized fun pieceAnswered(h: String): Boolean = (hosts[h]?.answered ?: 0) > 0

    /** A host whose pieces were refused (not timed out) and none of its pieces ever completed. */
    @Synchronized fun pieceRefusedHost(): String? {
        for ((name, e) in hosts) if (e.refused >= 1 && e.ok == 0) return name
        return null
    }

    @Synchronized fun pieceRefused(h: String): Boolean = hosts[h]?.let { it.refused >= 1 && it.ok == 0 } == true

    /** Bytes of a piece arrived at [now]. */
    @Synchronized fun pieceBytes(now: Long, n: Int) {
        lastPieceByteAt = now
        lastPieceBytes += n
    }

    @Synchronized fun progressing(now: Long): Boolean = lastPieceByteAt != 0L && now - lastPieceByteAt in 0..PROGRESS_MS

    @Synchronized fun bytesSoFar(): Long = lastPieceBytes

    @Synchronized fun pieceOnlyHost(minFails: Int): String? {
        for ((name, e) in hosts) {
            if (e.fails >= minFails && e.ok == 0 && playlistOkAt >= e.firstFailStart && playlistOkAt != 0L && playlistFailAt <= playlistOkAt) return name
        }
        return null
    }

    @Synchronized fun retried(h: String) = hosts[h]?.retried == true
    @Synchronized fun restarted(h: String) = hosts[h]?.restarted == true

    /** Chrome's network engine was rebuilt for [h]'s pieces: forget their failures so the next verdict is about the fresh engine. */
    @Synchronized fun markRestarted(h: String) {
        val e = host(h)
        e.restarted = true
        e.fails = 0
        e.firstFailStart = 0L
    }

    /** The pieces of [h] got a new engine: forget their failures so the next verdict is about the new engine. */
    @Synchronized fun markRetried(h: String) {
        val e = host(h)
        e.retried = true
        e.fails = 0
        e.firstFailStart = 0L
    }

    /** True while a failure line may still be written (a few per watch). */
    @Synchronized fun takeDiag(): Boolean {
        if (diagLeft <= 0) return false
        diagLeft--
        return true
    }
}
