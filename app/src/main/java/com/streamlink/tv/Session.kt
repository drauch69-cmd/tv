package com.streamlink.tv

import org.json.JSONArray
import org.json.JSONObject

/** What the built-in player is doing, published about once a second for the phone remote. */
data class PlayerSnapshot(
    val playing: Boolean,
    val buffering: Boolean,
    val live: Boolean,
    val qualities: List<String>,
    val quality: Int,
    val sources: List<String>,
    val source: Int,
)

/**
 * Shared state between the TV screens and the phone page.
 * mode: "idle" (QR screen), "finding", "player" (built-in player) or "site" (site's own player).
 */
object Session {
    @Volatile var mode = "idle"
    @Volatile var title = ""
    @Volatile var current: StreamSet? = null
    @Volatile var snapshot: PlayerSnapshot? = null

    /** The screen currently playing registers here to receive phone remote commands. */
    @Volatile var commandHandler: ((String, String?) -> Unit)? = null

    fun stateJson(): String {
        val o = JSONObject()
        o.put("mode", mode)
        o.put("title", title)
        o.put("status", AppStatus.text)
        val s = snapshot
        if (mode == "player" && s != null) {
            o.put("playing", s.playing)
            o.put("buffering", s.buffering)
            o.put("live", s.live)
            o.put("qualities", JSONArray(s.qualities))
            o.put("quality", s.quality)
            o.put("sources", JSONArray(s.sources))
            o.put("source", s.source)
        }
        return o.toString()
    }
}
