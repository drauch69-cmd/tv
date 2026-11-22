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
}
