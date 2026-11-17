package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PrefetchTest {
    private val t0 = 1_800_000_000_000L
    private fun set(page: String = "https://e.example/a") =
        StreamSet(page, listOf(Source("https://cdn.example/a.m3u8", emptyMap(), listOf(""), "cdn")))

    @Before fun reset() = Prefetch.clear()

    @Test fun takeReturnsAndClearsFreshMatch() {
        val s = set()
        Prefetch.put("https://e.example/a", s, t0)
        assertSame(s, Prefetch.take("https://e.example/a", t0 + 30_000))
        assertNull(Prefetch.take("https://e.example/a", t0 + 30_001))
    }

    @Test fun takeRejectsOtherUrlAndKeepsEntry() {
        val s = set()
        Prefetch.put("https://e.example/a", s, t0)
        assertNull(Prefetch.take("https://e.example/b", t0 + 1_000))
        assertSame(s, Prefetch.take("https://e.example/a", t0 + 2_000))
    }

    @Test fun takeRejectsAndDropsStale() {
        Prefetch.put("https://e.example/a", set(), t0)
        assertNull(Prefetch.take("https://e.example/a", t0 + 60_000))
        assertNull(Prefetch.take("https://e.example/a", t0 + 1_000))
    }

    @Test fun justUnderAMinuteIsFresh() {
        Prefetch.put("https://e.example/a", set(), t0)
        assertNotNull(Prefetch.take("https://e.example/a", t0 + 59_999))
    }

    @Test fun onlyOneResultIsHeld() {
        Prefetch.put("https://e.example/a", set(), t0)
        Prefetch.put("https://e.example/b", set("https://e.example/b"), t0 + 1)
        assertNull(Prefetch.take("https://e.example/a", t0 + 2))
        assertNotNull(Prefetch.take("https://e.example/b", t0 + 2))
    }

    @Test fun emptyResultIsNotKept() {
        Prefetch.put("https://e.example/a", StreamSet("https://e.example/a", emptyList()), t0)
        assertFalse(Prefetch.has("https://e.example/a", t0))
        assertNull(Prefetch.take("https://e.example/a", t0))
    }

    @Test fun clearDropsIt() {
        Prefetch.put("https://e.example/a", set(), t0)
        assertTrue(Prefetch.has("https://e.example/a", t0 + 1))
        Prefetch.clear()
        assertNull(Prefetch.take("https://e.example/a", t0 + 2))
    }

    @Test fun clockGoingBackwardsIsNotTrusted() {
        assertFalse(PrefetchLogic.fresh(t0, t0 - 1))
        assertTrue(PrefetchLogic.fresh(t0, t0))
    }

    @Test fun onlyLiveAndSoonAreWorthLookingFor() {
        assertTrue(PrefetchLogic.statusWorthLooking(ListStatus.LIVE))
        assertTrue(PrefetchLogic.statusWorthLooking(ListStatus.SOON))
        assertFalse(PrefetchLogic.statusWorthLooking(ListStatus.UPCOMING))
        assertFalse(PrefetchLogic.statusWorthLooking(ListStatus.UNKNOWN))
        assertFalse(PrefetchLogic.statusWorthLooking(ListStatus.FINISHED))
    }

    @Test fun eligibleNeedsEverythingToLineUp() {
        assertTrue(PrefetchLogic.eligible(ListStatus.LIVE, "idle", "idle", enabled = true, screenActive = true))
        assertTrue(PrefetchLogic.eligible(ListStatus.SOON, "list", "list", enabled = true, screenActive = true))
        assertFalse(PrefetchLogic.eligible(ListStatus.LIVE, "idle", "idle", enabled = false, screenActive = true))
        assertFalse(PrefetchLogic.eligible(ListStatus.LIVE, "idle", "idle", enabled = true, screenActive = false))
        assertFalse(PrefetchLogic.eligible(ListStatus.UPCOMING, "idle", "idle", enabled = true, screenActive = true))
        for (mode in listOf("player", "clean", "site", "waiting", "finding")) {
            assertFalse(mode, PrefetchLogic.eligible(ListStatus.LIVE, mode, "idle", enabled = true, screenActive = true))
        }
        // the list screen sets its own mode; "idle" there would mean it is not the screen in front
        assertFalse(PrefetchLogic.eligible(ListStatus.LIVE, "idle", "list", enabled = true, screenActive = true))
    }

    @Test fun focusDelayIsTwoSeconds() = assertEquals(2_000L, PrefetchLogic.FOCUS_DELAY_MS)

    @Test fun missBlocksRepeatsForFortyFiveSeconds() {
        val log = MissLog()
        assertFalse(log.recent("u", t0))
        log.record("u", t0)
        assertTrue(log.recent("u", t0 + 44_999))
        assertFalse(log.recent("u", t0 + 45_000))
        assertFalse(log.recent("other", t0 + 1))
    }

    @Test fun missLogStaysSmall() {
        val log = MissLog(max = 3)
        for (i in 1..5) log.record("u$i", t0 + i)
        assertFalse(log.recent("u1", t0 + 10))
        assertFalse(log.recent("u2", t0 + 10))
        assertTrue(log.recent("u5", t0 + 10))
    }

    @Test fun sharedMissHelpersWork() {
        Prefetch.noteMiss("https://e.example/miss", t0)
        assertTrue(Prefetch.recentlyMissed("https://e.example/miss", t0 + 10_000))
        assertFalse(Prefetch.recentlyMissed("https://e.example/miss", t0 + 46_000))
    }
}
