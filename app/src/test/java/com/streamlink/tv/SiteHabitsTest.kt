package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteHabitsTest {
    private val day = 24 * 60 * 60 * 1000L

    private fun habit(at: Long = 0L, misses: Int = 0, taps: Int = 1, si: Int = -1, sc: Int = 0, secs: Float = 3f) =
        SiteHabits.Habit(SiteHabits.KIND_PLAYER, 0.5f, 0.4f, taps, si, sc, secs, misses, at)

    @Test fun keyIsLowerCaseWithoutWww() {
        assertEquals("example.com", SiteHabits.key(" WWW.Example.com "))
        assertNull(SiteHabits.key(""))
        assertNull(SiteHabits.key(null))
    }

    @Test fun fractionMaths() {
        assertEquals(0.5f, SiteHabits.fraction(640f, 1280)!!, 0.0001f)
        assertEquals(0.25f, SiteHabits.fraction(180f, 720)!!, 0.0001f)
        assertNull(SiteHabits.fraction(0f, 1280))
        assertNull(SiteHabits.fraction(1280f, 1280))
        assertNull(SiteHabits.fraction(-3f, 1280))
        assertNull(SiteHabits.fraction(10f, 0))
        // round trip onto a differently sized view
        val f = SiteHabits.fraction(320f, 1280)!!
        assertEquals(480f, SiteHabits.toPixels(f, 1920), 0.01f)
    }

    @Test fun recordsTapOnly() {
        val h = SiteHabits.fromRun(1, SiteHabits.KIND_PAGE, 640f, 360f, 1280, 720, -1, 0, 3.4f, 100L)!!
        assertEquals(SiteHabits.KIND_PAGE, h.tapKind)
        assertEquals(0.5f, h.fx, 0.0001f)
        assertEquals(0.5f, h.fy, 0.0001f)
        assertEquals(1, h.taps)
        assertFalse(h.hasSource)
        assertTrue(h.hasTap)
        assertEquals(0, h.misses)
    }

    @Test fun twoOrMoreTapsAreStoredAsTwo() {
        assertEquals(2, SiteHabits.fromRun(2, SiteHabits.KIND_PLAYER, 600f, 300f, 1280, 720, -1, 0, 5f, 0L)!!.taps)
        assertEquals(2, SiteHabits.fromRun(5, SiteHabits.KIND_PLAYER, 600f, 300f, 1280, 720, -1, 0, 5f, 0L)!!.taps)
    }

    @Test fun recordsSourceOnlyAndBoth() {
        val s = SiteHabits.fromRun(0, "", 0f, 0f, 1280, 720, 1, 3, 6f, 0L)!!
        assertFalse(s.hasTap)
        assertTrue(s.hasSource)
        assertEquals(1, s.srcIndex)
        assertEquals(3, s.srcCount)
        val b = SiteHabits.fromRun(2, SiteHabits.KIND_PLAYER, 600f, 300f, 1280, 720, 0, 2, 6f, 0L)!!
        assertTrue(b.hasTap && b.hasSource)
    }

    @Test fun nothingToRememberGivesNull() {
        assertNull(SiteHabits.fromRun(0, "", 0f, 0f, 1280, 720, -1, 0, 2f, 0L))
        // a tap with an impossible position or no kind does not count
        assertNull(SiteHabits.fromRun(1, SiteHabits.KIND_PAGE, 0f, 360f, 1280, 720, -1, 0, 2f, 0L))
        assertNull(SiteHabits.fromRun(1, "", 600f, 300f, 1280, 720, -1, 0, 2f, 0L))
        assertNull(SiteHabits.fromRun(1, SiteHabits.KIND_PAGE, 600f, 300f, 0, 0, -1, 0, 2f, 0L))
        // source index outside its count
        assertNull(SiteHabits.fromRun(0, "", 0f, 0f, 1280, 720, 3, 3, 2f, 0L))
    }

    @Test fun badTapStillKeepsGoodSource() {
        val h = SiteHabits.fromRun(1, SiteHabits.KIND_PAGE, 0f, 0f, 1280, 720, 0, 2, 2f, 0L)!!
        assertFalse(h.hasTap)
        assertTrue(h.hasSource)
    }

    @Test fun secondsAreClamped() {
        assertEquals(120f, SiteHabits.fromRun(1, SiteHabits.KIND_PAGE, 5f, 5f, 10, 10, -1, 0, 900f, 0L)!!.secs, 0f)
        assertEquals(0f, SiteHabits.fromRun(1, SiteHabits.KIND_PAGE, 5f, 5f, 10, 10, -1, 0, -4f, 0L)!!.secs, 0f)
    }

    @Test fun sameButtonCountOnly() {
        val h = habit(si = 1, sc = 3)
        assertTrue(SiteHabits.sameCount(h, 3))
        assertFalse(SiteHabits.sameCount(h, 2))
        assertFalse(SiteHabits.sameCount(h, 4))
        assertFalse(SiteHabits.sameCount(habit(), 3)) // no source in the habit
    }

    @Test fun deadlineIsUsualSecondsPlusFive() {
        assertEquals(8_000L, SiteHabits.deadlineMs(habit(secs = 3f)))
        assertEquals(5_000L, SiteHabits.deadlineMs(habit(secs = 0f)))
    }

    @Test fun twoMissesInARowDropIt() {
        val h = habit()
        val once = SiteHabits.afterMiss(h, 10L)!!
        assertEquals(1, once.misses)
        assertEquals(10L, once.at)
        assertNull(SiteHabits.afterMiss(once, 20L))
    }

    @Test fun successClearsMisses() {
        val once = SiteHabits.afterMiss(habit(), 10L)!!
        val ok = SiteHabits.afterWorked(once, 30L)
        assertEquals(0, ok.misses)
        assertEquals(30L, ok.at)
        assertNotNull(SiteHabits.afterMiss(ok, 40L)) // needs two in a row again
    }

    @Test fun expiryAfterFourteenDays() {
        val h = habit(at = 0L)
        assertTrue(SiteHabits.usable(h, 14 * day - 1))
        assertFalse(SiteHabits.usable(h, 14 * day))
        assertFalse(SiteHabits.usable(null, 0L))
        assertFalse(SiteHabits.usable(habit().copy(tapKind = "", taps = 0), 0L)) // empty habit
    }

    @Test fun lruKeepsNewestAndCapsSize() {
        var all: Map<String, SiteHabits.Habit> = emptyMap()
        for (i in 1..5) all = SiteHabits.insert(all, "s$i", habit(at = i.toLong()), i.toLong(), max = 3)
        assertEquals(listOf("s3", "s4", "s5"), all.keys.toList())
        // saving an existing site moves it to the end instead of adding a second
        all = SiteHabits.insert(all, "s3", habit(at = 9L), 9L, max = 3)
        assertEquals(listOf("s4", "s5", "s3"), all.keys.toList())
    }

    @Test fun insertDropsExpiredEntries() {
        val all = mapOf("old" to habit(at = 0L), "new" to habit(at = 20 * day))
        val out = SiteHabits.insert(all, "x", habit(at = 20 * day), 20 * day)
        assertEquals(listOf("new", "x"), out.keys.toList())
    }

    @Test fun describeText() {
        val h = habit(taps = 2, si = 1, sc = 3)
        assertEquals("tap at player spot, 2 taps", SiteHabits.describe(h, true, false))
        assertEquals("source button 2 of 3", SiteHabits.describe(h, false, true))
        assertEquals("tap at player spot, 2 taps + source button 2 of 3", SiteHabits.describe(h, true, true))
        assertEquals("tap at middle of the page, 1 tap", SiteHabits.describe(h.copy(tapKind = SiteHabits.KIND_PAGE, taps = 1), true, false))
    }
}
