package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlternativesTest {
    private fun ts(d: String, s: String, a: String, l: String, n: String) = TeamSide(d, s, a, l, n, "0", "")
    private val game = Game("1", "nfl", 0L, "in", "", 0, "", ts("A Home", "Home", "AH", "A", "Home"), ts("B Away", "Away", "BA", "B", "Away"), "")
    private val game2 = game.copy(id = "2")

    private fun gs(listId: String, url: String, title: String = "t", name: String = "List $listId") =
        GameStream(listId, name, ListItem(title, "", url, null, "", "", false, null))

    private fun folders(vararg streams: List<GameStream>) =
        listOf(SportFolders("nfl", streams.mapIndexed { i, s -> GameFolder(if (i == 0) game else game2, s) }, emptyList()))

    private val a1 = gs("a", "https://a.example/1")
    private val b1 = gs("b", "https://b.example/1")
    private val c1 = gs("c", "https://c.example/1/")
    private val d1 = gs("d", "https://d.example/1")

    @Test fun otherStreamsOfTheSameGameInFolderOrderWithoutThePlayingOne() {
        val r = Alternatives.pick(folders(listOf(a1, b1, c1)), listOf(null, "https://b.example/1"), null)
        assertEquals(listOf("https://a.example/1", "https://c.example/1/"), r.map { it.item.url })
    }

    @Test fun matchesByListItemOrPageAndIgnoresTrailingSlash() {
        val r = Alternatives.pick(folders(listOf(a1, b1, c1)), listOf("https://c.example/1", null), null)
        assertEquals(2, r.size)
        assertEquals(2, Alternatives.pick(folders(listOf(a1, b1, c1)), listOf("https://zzz.example/", "https://a.example/1/"), null).size)
    }

    @Test fun preferredListFirst() {
        val r = Alternatives.pick(folders(listOf(a1, b1, c1, d1)), listOf("https://a.example/1"), "d")
        assertEquals(listOf("d", "b", "c"), r.map { it.listId })
    }

    @Test fun finishedAndRepeatedAndNonWebItemsAreLeftOut() {
        val rep = gs("e", "https://b.example/1/")
        val bad = gs("f", "about:blank")
        val r = Alternatives.pick(folders(listOf(a1, b1, rep, bad, c1)), listOf("https://a.example/1"), null) { it.url.contains("c.example") }
        assertEquals(listOf("https://b.example/1"), r.map { it.item.url })
    }

    @Test fun atMostSixAndOtherGamesNeverMix() {
        val many = (1..9).map { gs("l$it", "https://s$it.example/x") }
        assertEquals(6, Alternatives.pick(folders(many, listOf(d1)), listOf("https://s1.example/x"), null).size)
        assertTrue(Alternatives.pick(folders(many, listOf(d1)), listOf("https://d.example/1"), null).isEmpty())
    }

    @Test fun noFolderHoldsThePageGivesNothing() {
        assertTrue(Alternatives.pick(folders(listOf(a1, b1)), listOf("https://nope.example/"), null).isEmpty())
        assertTrue(Alternatives.pick(folders(listOf(a1, b1)), listOf(null, ""), null).isEmpty())
    }

    @Test fun entriesAreOpenPageWithNoReferer() {
        val s = Alternatives.toSources(listOf(a1, gs("b", "https://b.example/1", name = "List a")))
        assertEquals(listOf("List a · other site", "List a · other site (2)"), s.map { it.label })
        assertTrue(s.all { it.openPage && !it.verified && it.referers.isEmpty() && it.playerUrl == it.url })
    }

    @Test fun appendKeepsOrderAndSkipsKnownAddresses() {
        val have = listOf(Source("https://x.example/a.m3u8", emptyMap(), listOf(""), "cur"))
        val extra = Alternatives.toSources(listOf(a1, b1))
        val m = Alternatives.append(have, extra)
        assertEquals(3, m.size)
        assertEquals("cur", m[0].label)
        assertEquals(3, Alternatives.append(m, extra).size)
        assertNull(m.firstOrNull { it.url == "nope" })
    }
}
