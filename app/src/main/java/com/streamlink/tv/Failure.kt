package com.streamlink.tv

import androidx.media3.common.PlaybackException

/**
 * Lab step 1a (2026-11-21): one name for what went wrong while playing, logged as "Failure: <name> (<detail>)" at
 * every place the player starts a rescue. Log only for now: the existing rescue code still decides what happens.
 * Step 1b will hand the name to one fixed fallback order. Pure, unit tested.
 */
enum class Failure(val label: String) {
    BEHIND_LIVE("fell behind live"),
    TUNNEL_AUDIO("tunneled audio problem"),
    BROKEN_SECURE("broken secure connection"),
    EXPIRED_LINK("link expired"),
    REFUSED("server refused"),
    GONE("stream gone"),
    SERVER_ERROR("server error"),
    NETWORK("network trouble"),
    BAD_DATA("unreadable stream data"),
    DECODER("decoder problem"),
    STALLED("stalled"),
    PIECES_FAILING("video pieces failing"),
    OTHER("other");

    companion object {
        /** Names a player error from what handleError already knows. */
        fun of(
            errorCode: Int, http: Int?, brokenSecure: Boolean, tunnelSuspect: Boolean,
            playing: Boolean, signedAddress: Boolean,
        ): Failure = when {
            errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> BEHIND_LIVE
            tunnelSuspect -> TUNNEL_AUDIO
            brokenSecure -> BROKEN_SECURE
            (http == 401 || http == 403) && playing && signedAddress -> EXPIRED_LINK
            http == 401 || http == 403 || http == 451 -> REFUSED
            http == 404 || http == 410 -> GONE
            http != null && http >= 500 -> SERVER_ERROR
            errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                errorCode == PlaybackException.ERROR_CODE_TIMEOUT -> NETWORK
            errorCode in 3000..3999 -> BAD_DATA
            errorCode in 4000..4999 -> DECODER
            errorCode in 5000..5999 -> TUNNEL_AUDIO
            else -> OTHER
        }

        fun line(f: Failure, detail: String, playing: Boolean): String =
            "Failure: ${f.label} (" + (if (playing) "while playing" else "before the picture") +
                (if (detail.isBlank()) "" else "; $detail") + ")"
    }
}
