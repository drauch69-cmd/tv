package com.streamlink.tv

import java.net.URI

/**
 * Request-header rules for stream and segment requests, shared by the verifier, the built-in player,
 * the clean player's proxy and the sniffer. Pure Kotlin, so it is unit-tested on the JVM.
 */
object Headers {

    /**
     * The origin of a referer URL as a browser sends it in Origin: scheme://host[:port], no path and
     * no trailing slash. Default ports (443 for https, 80 for http) are left out. Null for blank input,
     * garbage, or anything that isn't an http(s) URL with a host.
     */
    fun originOf(referer: String?): String? {
        if (referer.isNullOrBlank()) return null
        return try {
            val u = URI(referer.trim())
            val scheme = u.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = u.host?.lowercase() ?: return null
            val defaultPort = if (scheme == "https") 443 else 80
            val port = if (u.port > 0 && u.port != defaultPort) ":${u.port}" else ""
            "$scheme://$host$port"
        } catch (_: Exception) {
            null
        }
    }

    /** The Origin captured with a request (canonical form), or null when it had none, or it was "null" or garbage. */
    fun capturedOrigin(headers: Map<String, String>): String? =
        headers.entries.firstOrNull { it.key.equals("Origin", true) }?.value?.let { originOf(it) }

    /**
     * The Origin to send when a request uses [referer]: the captured request's own Origin when it had
     * one, otherwise the referer's origin. Null when no referer is used (the "no referer" attempt
     * sends no Origin either).
     */
    fun originFor(referer: String, captured: String?): String? {
        if (referer.isEmpty()) return null
        // Always the origin of the referer being tried: a captured Origin from another request (an
        // analytics carrier, say) or from a different referer would be a mismatch strict servers refuse.
        return originOf(referer) ?: captured
    }

    /**
     * Each referer followed by its bare origin with a trailing slash ("https://host/"), which is what a
     * browser sends under strict referrer policies; de-duplicated and capped at [max]. The cap cuts the
     * tail, so the first referers are never lost to the extra origin forms.
     */
    fun withOriginForms(referers: List<String>, max: Int): List<String> {
        val out = LinkedHashSet<String>()
        for (r in referers) {
            out.add(r)
            originOf(r)?.let { out.add("$it/") }
        }
        return out.toList().take(max)
    }

    /** A referer as the report shows it: "host/…" for a page address, "host/" for a bare origin, "none" for no referer. */
    fun describeReferer(referer: String): String {
        if (referer.isEmpty()) return "none"
        val origin = originOf(referer) ?: return referer.take(30)
        val host = origin.substringAfter("://")
        val bare = referer.trim().removePrefix(origin).let { it.isEmpty() || it == "/" }
        return if (bare) "$host/" else "$host/…"
    }

    /**
     * Referers to try for a stream found inside another request: the carrier request's Referer first,
     * then the player frames (given newest first), leaving out ad hosts; each also as a bare origin right
     * after its full form; de-duplicated and capped at [max]. Only http(s) URLs qualify, so about: and
     * javascript: never appear.
     */
    fun embeddedReferers(carrier: String?, framesNewestFirst: List<String>, isAdHost: (String) -> Boolean, max: Int = 4): List<String> {
        val fromCarrier = listOfNotNull(carrier).filter { originOf(it) != null }
        val frames = framesNewestFirst.filter { originOf(it) != null && !isAdHost(it) }
        return withOriginForms((fromCarrier + frames).distinct(), max)
    }
}
