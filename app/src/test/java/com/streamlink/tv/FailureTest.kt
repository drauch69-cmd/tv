package com.streamlink.tv

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Test

class FailureTest {
    private fun of(code: Int = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, http: Int? = null, broken: Boolean = false,
                   tunnel: Boolean = false, playing: Boolean = false, signed: Boolean = false) =
        Failure.of(code, http, broken, tunnel, playing, signed)

    @Test fun names() {
        assertEquals(Failure.BEHIND_LIVE, of(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))
        assertEquals(Failure.TUNNEL_AUDIO, of(tunnel = true, http = 403))
        assertEquals(Failure.BROKEN_SECURE, of(broken = true))
        assertEquals(Failure.EXPIRED_LINK, of(http = 403, playing = true, signed = true))
        assertEquals(Failure.REFUSED, of(http = 403))
        assertEquals(Failure.GONE, of(http = 404))
        assertEquals(Failure.SERVER_ERROR, of(http = 502))
        assertEquals(Failure.NETWORK, of(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals(Failure.BAD_DATA, of(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
        assertEquals(Failure.DECODER, of(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertEquals(Failure.OTHER, of(PlaybackException.ERROR_CODE_UNSPECIFIED))
    }

    @Test fun line() {
        assertEquals("Failure: stalled (while playing)", Failure.line(Failure.STALLED, "", true))
        assertEquals("Failure: server refused (before the picture; server said 403)", Failure.line(Failure.REFUSED, "server said 403", false))
    }
}
