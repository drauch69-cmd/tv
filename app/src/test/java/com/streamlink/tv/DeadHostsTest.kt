package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

class DeadHostsTest {
    private val now = 1_800_000_000_000L

    @Test fun chromeSslErrorIsBroken() {
        val e = IOException("Exception in CronetUrlRequest: net::ERR_SSL_PROTOCOL_ERROR, ErrorCode=11, InternalErrorCode=-107, Retryable=false")
        assertTrue(DeadHostText.brokenSecureLink(RuntimeException("wrapper", e)))
    }

    @Test fun standardSslExceptionIsBroken() {
        assertTrue(DeadHostText.brokenSecureLink(IOException("x", SSLException("Unable to parse TLS packet header"))))
    }

    @Test fun timeoutsAndRefusalsAreNotBroken() {
        assertFalse(DeadHostText.brokenSecureLink(SocketTimeoutException("timeout")))
        assertFalse(DeadHostText.brokenSecureLink(IOException("net::ERR_CONNECTION_TIMED_OUT")))
        assertFalse(DeadHostText.brokenSecureLink(null))
    }

    @Test fun sslReasonInPlainWords() {
        val cert = IOException("net::ERR_CERT_AUTHORITY_INVALID, ErrorCode=11")
        assertEquals("the TV doesn't trust its certificate [ERR_CERT_AUTHORITY_INVALID]", DeadHostText.sslReason(RuntimeException("w", cert)))
        assertTrue(DeadHostText.sslReason(IOException("net::ERR_SSL_PROTOCOL_ERROR"))!!.contains("handshake failed [ERR_SSL_PROTOCOL_ERROR]"))
        assertTrue(DeadHostText.sslReason(IOException("net::ERR_SSL_VERSION_OR_CIPHER_MISMATCH"))!!.startsWith("no security version"))
        val trust = javax.net.ssl.SSLHandshakeException("x").apply { initCause(java.security.cert.CertPathValidatorException("Trust anchor for certification path not found.")) }
        assertTrue(DeadHostText.sslReason(trust)!!.startsWith("the TV doesn't trust its certificate"))
        assertTrue(DeadHostText.sslReason(SSLException("Unable to parse TLS packet header"))!!.startsWith("the secure handshake failed [SSLException: Unable"))
        assertNull(DeadHostText.sslReason(SocketTimeoutException("timeout")))
    }

    @Test fun allRememberedNeedsEveryHost() {
        val dead = setOf("a", "b")
        assertTrue(DeadHostText.allRemembered(listOf("a", "b")) { it in dead })
        assertFalse(DeadHostText.allRemembered(listOf("a", "c")) { it in dead })
        assertFalse(DeadHostText.allRemembered(emptyList()) { it in dead })
    }

    @Test fun storedFormRoundTripsAndExpires() {
        val map = mapOf("a.example" to now - 1000, "old.example" to now - DeadHosts.DEAD_MS - 1)
        val back = DeadHostText.parse(DeadHostText.format(map) + "\nbad line", now, DeadHosts.DEAD_MS)
        assertEquals(setOf("a.example"), back.keys)
    }

    @Test fun recentIsNewestFirstAndTrimKeepsNewest() {
        val seen = hashMapOf("a" to now - 3000, "b" to now - 1000, "c" to now - DeadHosts.SEEN_MS - 5)
        assertEquals(listOf("b", "a"), DeadHostText.recent(seen, now, DeadHosts.SEEN_MS))
        DeadHostText.trim(seen, 2)
        assertEquals(setOf("a", "b"), seen.keys)
    }

    @Test fun firstPieceResolvesAgainstThePlaylist() {
        val text = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4.0,\npiece_1879.txt\n#EXTINF:4.0,\npiece_1880.txt\n"
        assertEquals("https://pl.example/a/piece_1879.txt", DeadNotes.firstPiece(text, "https://pl.example/a/caxi"))
        assertEquals("https://cdn.example/p.ts", DeadNotes.firstPiece("#EXTM3U\nhttps://cdn.example/p.ts", "https://pl.example/x"))
        assertNull(DeadNotes.firstPiece("#EXTM3U\n#EXT-X-ENDLIST", "https://pl.example/x"))
    }

    @Test fun downSourceIsRecognisedOnlyWhenUnverified() {
        val down = Source("https://x/a.m3u8", emptyMap(), emptyList(), "A", verified = false, note = DeadNotes.downstreamDown("cdn.example"))
        assertTrue(DeadNotes.isDownSource(down))
        assertTrue(DeadNotes.isDownSource(down.copy(note = DeadNotes.SERVER_DOWN)))
        assertFalse(DeadNotes.isDownSource(down.copy(verified = true)))
        assertFalse(DeadNotes.isDownSource(down.copy(note = "server said 403")))
    }
}
