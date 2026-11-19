package com.streamlink.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListReadGateTest {
    @Test fun pausedWhileFindingOrPlaying() {
        for (m in listOf("waiting", "finding", "player", "clean", "site")) assertTrue(m, ListReadGate.pausedFor(m))
    }

    @Test fun notPausedOtherwise() {
        for (m in listOf("idle", "list")) assertFalse(m, ListReadGate.pausedFor(m))
    }
}
