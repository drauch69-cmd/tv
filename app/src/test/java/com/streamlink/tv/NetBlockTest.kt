package com.streamlink.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetBlockTest {
    @Test fun blockedAfterThreeWithinTheWindow() {
        NetBlock.clear()
        val t = 1_000_000L
        NetBlock.record("chatgpt.hereisman.net", t)
        NetBlock.record("chatgpt.hereisman.net", t + 1000)
        assertFalse(NetBlock.isBlocked("chatgpt.hereisman.net", t + 2000))
        NetBlock.record("Chatgpt.Hereisman.net", t + 2000)
        assertTrue(NetBlock.isBlocked("chatgpt.hereisman.net", t + 3000))
        assertTrue(NetBlock.anyBlocked(t + 3000))
        assertFalse(NetBlock.isBlocked("other.net", t + 3000))
        assertFalse(NetBlock.isBlocked("chatgpt.hereisman.net", t + 2000 + NetBlock.WINDOW_MS + 1))
        NetBlock.clear()
    }
}
