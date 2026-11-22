package com.streamlink.tv

import android.content.Context

/**
 * Stream servers on which low delay ran dry (2026-11-21: West Virginia vs Houston stalled 6 times at low delay, fell back to
 * smooth, then stalled the same way on every reopen). Remembered for [KEEP_MS], so the next play of that server starts on
 * the smooth delay right away. Keyed per stream ([streamKey]: server + path of the main playlist, no query), not per
 * server: on 2026-11-21 one slow Michigan stream put every thetvapp game (all behind the same main server) on smooth.
 */
object LowDelayMemory {
    const val KEEP_MS = 6 * 60 * 60 * 1000L
    const val MAX_HOSTS = 40
    private const val PREF = "low_delay_dry"

    /** "host=millis;host=millis" -> map; bad parts are skipped. Pure. */
    internal fun parse(raw: String?): LinkedHashMap<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (part in (raw ?: "").split(';')) {
            val i = part.lastIndexOf('=')
            if (i <= 0) continue
            val t = part.substring(i + 1).toLongOrNull() ?: continue
            out[part.substring(0, i)] = t
        }
        return out
    }

    internal fun format(m: Map<String, Long>): String = m.entries.joinToString(";") { "${it.key}=${it.value}" }

    /** True: [host] ran dry within [KEEP_MS] before [now]. Pure. */
    internal fun wants(m: Map<String, Long>, host: String?, now: Long): Boolean {
        val t = m[host ?: return false] ?: return false
        return now - t in 0 until KEEP_MS
    }

    /** [m] with [host] marked at [now], expired ones dropped, newest [MAX_HOSTS] kept. Pure. */
    internal fun marked(m: Map<String, Long>, host: String, now: Long): LinkedHashMap<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for ((h, t) in m) if (h != host && now - t in 0 until KEEP_MS) out[h] = t
        out[host] = now
        while (out.size > MAX_HOSTS) out.remove(out.keys.first())
        return out
    }

    /** The stream's key: [ServerMemory.key] of its host plus its path (query dropped), without ";" or "=". Pure. */
    internal fun streamKey(url: String?): String? {
        val u = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val host = ServerMemory.key(Report.host(u)) ?: return null
        val path = try { java.net.URI(u).rawPath ?: "" } catch (_: Exception) { "" }
        return (host + path).replace(';', '_').replace('=', '_')
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("streamlink", Context.MODE_PRIVATE)

    fun wantsSmooth(ctx: Context, streamUrl: String?): Boolean =
        wants(parse(prefs(ctx).getString(PREF, null)), streamKey(streamUrl), System.currentTimeMillis())

    fun markDry(ctx: Context, streamUrl: String?) {
        val k = streamKey(streamUrl) ?: return
        val next = marked(parse(prefs(ctx).getString(PREF, null)), k, System.currentTimeMillis())
        prefs(ctx).edit().putString(PREF, format(next)).apply()
    }
}
