package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RescueTest {
    @Test fun everyErrorEndsInTheSameTail() {
        for (f in Failure.values()) {
            val o = Rescue.order(f)
            if (f == Failure.BEHIND_LIVE || f == Failure.TUNNEL_AUDIO) continue
            assertEquals(Rescue.TAIL, o.takeLast(Rescue.TAIL.size))
        }
    }

    @Test fun specificStepsComeFirst() {
        assertEquals(RescueStep.REASK_MAIN, Rescue.order(Failure.BROKEN_SECURE).first())
        assertEquals(listOf(RescueStep.NO_TUNNEL), Rescue.order(Failure.TUNNEL_AUDIO))
        assertEquals(listOf(RescueStep.JUMP_TO_LIVE), Rescue.order(Failure.BEHIND_LIVE))
        assertEquals(listOf(RescueStep.READY_BACKUP, RescueStep.OTHER_SITE, RescueStep.JUMP_TO_LIVE), Rescue.order(Failure.BEHIND_LIVE, true))
        val refused = Rescue.order(Failure.REFUSED)
        assertTrue(refused.indexOf(RescueStep.RENEW_LINK) < refused.indexOf(RescueStep.RELOAD_PAGE))
        assertEquals(refused, Rescue.order(Failure.EXPIRED_LINK))
    }

    @Test fun reloadComesBeforeOtherSitesAndLastResortIsLast() {
        assertTrue(Rescue.TAIL.indexOf(RescueStep.READY_BACKUP) < Rescue.TAIL.indexOf(RescueStep.RELOAD_PAGE))
        assertTrue(Rescue.TAIL.indexOf(RescueStep.RELOAD_PAGE) < Rescue.TAIL.indexOf(RescueStep.OTHER_SITE))
        assertEquals(RescueStep.LAST_RESORT, Rescue.TAIL.last())
        assertEquals(RescueStep.NEXT_SOURCE, Rescue.AFTER_STALL.first())
        assertEquals(RescueStep.READY_BACKUP, Rescue.AFTER_EMPTY_RENEWAL.first())
    }

    @Test fun reasksAreSpacedOut() {
        assertEquals(1_000L, Rescue.reaskDelayMs(1))
        assertEquals(2_000L, Rescue.reaskDelayMs(2))
        assertEquals(3_000L, Rescue.reaskDelayMs(3))
        assertEquals(3_000L, Rescue.reaskDelayMs(4))
    }

    @Test fun onlyConnectionLikeFailuresCheckTheInternet() {
        assertTrue(Rescue.checksNetwork(Failure.NETWORK))
        assertTrue(Rescue.checksNetwork(Failure.STALLED))
        assertTrue(Rescue.checksNetwork(null))
        assertTrue(!Rescue.checksNetwork(Failure.BROKEN_SECURE))
        assertTrue(!Rescue.checksNetwork(Failure.REFUSED))
        assertTrue(!Rescue.checksNetwork(Failure.BEHIND_LIVE))
    }

    @Test fun refusedPiecesReloadOnlyWhenNotCorroborated() {
        assertTrue(RescueStep.RELOAD_ONCE in Rescue.afterRefusedPieces(false))
        assertTrue(RescueStep.RELOAD_ONCE !in Rescue.afterRefusedPieces(true))
        assertEquals(RescueStep.NEXT_SOURCE, Rescue.afterRefusedPieces(true).first())
        assertEquals(RescueStep.LAST_RESORT, Rescue.afterRefusedPieces(false).last())
        assertTrue(RescueStep.RELOAD_PAGE !in Rescue.PIECES_FAILING.take(2))
    }
}
