package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ListAddressTest {
    private val host: (String) -> String = { it.substringAfter("://").substringBefore('/') }
    private val lists = listOf(
        SavedList("a1", "Sunday NFL", "https://one.example/", setOf("nfl"), 1),
        SavedList("b2", "two.example", "https://two.example/live"),
    )

    @Test
    fun keepsNameSportsAndRank() {
        val ch = ListAddress.change(lists, "a1", "https://one.example/sport/football", host)
        assertNull(ch.problem)
        assertEquals(SavedList("a1", "Sunday NFL", "https://one.example/sport/football", setOf("nfl"), 1), ch.lists!![0])
        assertSame(lists[1], ch.lists!![1])
    }

    @Test
    fun defaultNameFollowsTheNewSite() {
        val ch = ListAddress.change(lists, "b2", "https://three.example/", host)
        assertEquals("three.example", ch.lists!![1].name)
    }

    @Test
    fun refusals() {
        assertEquals("No such list", ListAddress.change(lists, "zz", "https://x.example/", host).problem)
        assertEquals("Another list already uses that address", ListAddress.change(lists, "a1", "https://two.example/live", host).problem)
        assertNull(ListAddress.change(lists, "a1", "https://two.example/live", host).lists)
    }

    @Test
    fun sameAddressIsNoChange() {
        val ch = ListAddress.change(lists, "a1", "https://one.example/", host)
        assertNull(ch.problem)
        assertSame(lists, ch.lists)
    }
}
