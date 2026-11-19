package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AutoSwitchTest {
    private val t0 = 1_000_000L
    @Before fun clean() = AutoSwitch.resetAllForTest()

    private fun play(url: String, verified: Boolean = true) = Source(url, emptyMap(), listOf(""), "p", verified = verified)
    private fun page(url: String) = Source(url, emptyMap(), emptyList(), "o", verified = false, playerUrl = url, openPage = true)

    @Test fun orderIsPlayableThenPagesSkippingCurrentUnverifiedAndDead() {
        val list = listOf(play("https://a/1.m3u8"), page("https://s/?source=1"), play("https://a/2.m3u8"),
            play("https://a/3.m3u8", verified = false), page("https://o/x"), play("https://a/4.m3u8"))
        assertEquals(listOf(2, 5, 1, 4), AutoSwitch.order(list, 0, t0))
        AutoSwitch.markDead("https://a/2.m3u8", t0)
        AutoSwitch.markDead("https://s/?source=1/", t0)
        assertEquals(listOf(5, 4), AutoSwitch.order(list, 0, t0 + 1))
    }

    @Test fun deadPagesExpireAfterFiveMinutes() {
        AutoSwitch.markDead("https://d.example/p", t0)
        assertTrue(AutoSwitch.isDead("https://d.example/p/", t0 + AutoSwitch.DEAD_MS - 1))
        assertFalse(AutoSwitch.isDead("https://d.example/p", t0 + AutoSwitch.DEAD_MS))
    }

    @Test fun atMostThreeSwitchesPerWatchAndResetStartsANewOne() {
        assertTrue(AutoSwitch.canSwitch())
        AutoSwitch.begin(listOf(page("https://o/1"), page("https://o/2"), page("https://o/3")))
        assertEquals("https://o/1", AutoSwitch.takeNext(t0)!!.url)
        assertEquals("https://o/2", AutoSwitch.takeNext(t0)!!.url)
        assertFalse(AutoSwitch.canSwitch())
        assertNull(AutoSwitch.takeNext(t0))
        AutoSwitch.reset()
        assertTrue(AutoSwitch.canSwitch())
        assertEquals(0, AutoSwitch.switchesUsed())
    }

    @Test fun takeNextSkipsDeadCandidates() {
        AutoSwitch.begin(listOf(page("https://o/1"), page("https://o/2")))
        AutoSwitch.markDead("https://o/1", t0)
        assertEquals("https://o/2", AutoSwitch.takeNext(t0 + 10)!!.url)
        assertNull(AutoSwitch.takeNext(t0 + 10))
    }
}
