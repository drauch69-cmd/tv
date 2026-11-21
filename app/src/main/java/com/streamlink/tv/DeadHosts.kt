package com.streamlink.tv

import android.annotation.SuppressLint
import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/**
 * Stream servers (playlist or video-piece hosts) whose secure connection is broken: the TLS handshake fails
 * (Chrome's engine: ERR_SSL_PROTOCOL_ERROR; standard networking: SSLException). Many sites share the same piece
 * servers, so one dead server sinks every source behind it, on every site, and a page reload hands it back again.
 *
 * A dead server is remembered for [DEAD_MS] (also across app restarts). The stream check ([Verifier]) fails a
 * candidate at once when its playlist, first quality level or first video piece is on a dead server; the player
 * skips page reloads for it and switches to another source.
 *
 * Servers seen in streams in the last [SEEN_MS] (dead or not) are re-checked with one quick handshake when the app
 * starts ([recheckOnStart]): a dead one that answers again is cleared, a working one that now fails is marked.
 */
// Lint: holds only the application context, not a leak.
@SuppressLint("StaticFieldLeak")
object DeadHosts {
    const val DEAD_MS = 5 * 60_000L
    const val SEEN_MS = 30 * 60_000L
    const val MAX_SEEN = 30
    private const val PREFS = "dead_hosts"
    private const val KEY_DEAD = "dead"
    private const val KEY_SEEN = "seen"
    private const val CHECK_TIMEOUT_MS = 3000

    private val lock = Any()
    private var app: Context? = null
    private val dead = HashMap<String, Long>()
    private val seen = HashMap<String, Long>()
    private var rechecked = false

    fun init(ctx: Context) {
        synchronized(lock) {
            app = ctx.applicationContext
            val now = System.currentTimeMillis()
            dead.clear(); seen.clear()
            dead.putAll(DeadHostText.parse(prefs()?.getString(KEY_DEAD, null), now, DEAD_MS))
            seen.putAll(DeadHostText.parse(prefs()?.getString(KEY_SEEN, null), now, SEEN_MS))
        }
    }

    private fun prefs() = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun save() {
        prefs()?.edit()
            ?.putString(KEY_DEAD, DeadHostText.format(dead))
            ?.putString(KEY_SEEN, DeadHostText.format(seen))
            ?.apply()
    }

    /** [host]'s secure connection just failed. Returns true when it was not already marked (for one report line). */
    fun record(host: String?, now: Long = System.currentTimeMillis()): Boolean {
        val k = ServerMemory.key(host) ?: return false
        synchronized(lock) {
            val fresh = !alive(dead[k], now, DEAD_MS)
            dead[k] = now
            seen[k] = now
            DeadHostText.trim(seen, MAX_SEEN)
            save()
            return fresh
        }
    }

    /** [host] answered (a playlist or piece came back): no longer dead. */
    fun clear(host: String?) {
        val k = ServerMemory.key(host) ?: return
        synchronized(lock) { if (dead.remove(k) != null) save() }
    }

    /** [host] appeared in a stream (for the start-up re-check). */
    fun seen(host: String?, now: Long = System.currentTimeMillis()) {
        val k = ServerMemory.key(host) ?: return
        synchronized(lock) {
            val before = seen[k]
            seen[k] = now
            DeadHostText.trim(seen, MAX_SEEN)
            // Don't write preferences for every piece: only when new or a minute stale.
            if (before == null || now - before > 60_000L) save()
        }
    }

    fun isDead(host: String?, now: Long = System.currentTimeMillis()): Boolean {
        val k = ServerMemory.key(host) ?: return false
        synchronized(lock) {
            val at = dead[k] ?: return false
            if (alive(at, now, DEAD_MS)) return true
            dead.remove(k); save()
            return false
        }
    }

    private fun alive(at: Long?, now: Long, ttl: Long) = at != null && now - at in 0 until ttl

    /**
     * Off the main thread: one quick secure connection to every server seen in the last [SEEN_MS]. Any HTTP answer
     * (even an error code) means the server is up; a broken handshake marks it dead; a timeout or unknown name
     * changes nothing (it says nothing about the server).
     */
    fun recheckOnStart() {
        val hosts = synchronized(lock) {
            if (rechecked) return
            rechecked = true
            DeadHostText.recent(seen, System.currentTimeMillis(), SEEN_MS)
        }
        if (hosts.isEmpty()) return
        Thread({
            // Use Chrome's engine like the player does (it starts in the background at app open): wait briefly for it.
            var waited = 0
            while (ChromeNet.engine == null && waited < 4000) { Thread.sleep(200); waited += 200 }
            var down = 0
            var back = 0
            for (h in hosts) {
                when (handshake(h)) {
                    true -> if (isDead(h)) { clear(h); back++ }
                    false -> { if (record(h)) down++ }
                    null -> {}
                }
            }
            if (down > 0 || back > 0) {
                Report.log("Start-up server check: $down down, $back back up (of ${hosts.size} seen recently)")
            }
        }, "dead-host-check").apply { isDaemon = true }.start()
    }

    /** True: answered; false: the secure connection is broken; null: no verdict (timeout, no such name…). */
    private fun handshake(host: String): Boolean? {
        var conn: HttpURLConnection? = null
        return try {
            conn = ChromeNet.open("https://$host/")
            conn.requestMethod = "HEAD"
            conn.connectTimeout = CHECK_TIMEOUT_MS
            conn.readTimeout = CHECK_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.responseCode
            true
        } catch (e: Exception) {
            if (DeadHostText.brokenSecureLink(e)) false else null
        } finally {
            conn?.disconnect()
        }
    }
}

/** Pure helpers for [DeadHosts], unit tested. */
object DeadHostText {
    /**
     * The failure is a broken secure connection to the server (it cannot speak TLS to us), not a timeout or a refusal:
     * an SSLException anywhere in the cause chain (standard networking), or Chrome's engine's ERR_SSL_* code.
     */
    fun brokenSecureLink(t: Throwable?): Boolean {
        var e = t
        var depth = 0
        while (e != null && depth < 10) {
            if (e is javax.net.ssl.SSLException) return true
            val m = e.message
            if (m != null && m.contains("net::ERR_SSL_")) return true
            e = e.cause
            depth++
        }
        return false
    }

    fun format(map: Map<String, Long>): String = map.entries.joinToString("\n") { "${it.key}\t${it.value}" }

    /** Entries still inside [ttl] at [now]; bad lines are skipped. */
    fun parse(text: String?, now: Long, ttl: Long): Map<String, Long> {
        if (text.isNullOrEmpty()) return emptyMap()
        val out = HashMap<String, Long>()
        for (line in text.lines()) {
            val parts = line.split('\t')
            if (parts.size != 2 || parts[0].isBlank()) continue
            val t = parts[1].toLongOrNull() ?: continue
            if (now - t in 0 until ttl) out[parts[0]] = t
        }
        return out
    }

    /** Hosts seen inside [ttl] at [now], newest first. */
    fun recent(seen: Map<String, Long>, now: Long, ttl: Long): List<String> =
        seen.entries.filter { now - it.value in 0 until ttl }.sortedByDescending { it.value }.map { it.key }

    /** Keep only the [max] newest entries. */
    fun trim(map: MutableMap<String, Long>, max: Int) {
        if (map.size <= max) return
        map.entries.sortedBy { it.value }.take(map.size - max).map { it.key }.forEach { map.remove(it) }
    }
}

/** Report notes and playlist helpers for the dead-server check in [Verifier]. Pure, unit tested. */
object DeadNotes {
    private const val MARK = "(secure connection broken)"
    const val SERVER_DOWN = "its server is down $MARK"

    /** A source the check failed because a server on its way is down. */
    fun isDownSource(s: Source): Boolean = !s.verified && s.note.contains(MARK)

    fun downstreamDown(host: String) = "its video comes from $host, which is down $MARK"

    /** The first piece (or nested playlist) address of a media playlist, resolved against [base]; null if none. */
    fun firstPiece(text: String, base: String): String? {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
        return try { java.net.URI(base).resolve(line).toString() } catch (_: Exception) { line }
    }
}
