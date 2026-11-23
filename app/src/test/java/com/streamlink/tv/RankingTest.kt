package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class RankingTest {
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-11-19T15:00:00Z").toEpochMilli()
    private fun at(s: String) = Instant.parse(s).toEpochMilli()

    private fun ts(d: String, s: String, a: String, l: String, n: String) = TeamSide(d, s, a, l, n, "0", "")
    private val wsh = ts("Washington Commanders", "Commanders", "WSH", "Washington", "Commanders")
    private val nyg = ts("New York Giants", "Giants", "NYG", "New York", "Giants")
    private val gWsh = Game("1", "nfl", at("2026-11-19T20:00:00Z"), "pre", "", 0, "", nyg, wsh, "")

    private fun item(title: String, url: String, start: Long? = null) = ListItem(title, "", url, start, "", "", false, null)
    private fun list(id: String, rank: Int? = null) = SavedList(id, "List $id", "https://$id.example", setOf("nfl"), rank)
    private fun ranks(ls: List<SavedList>) = ls.map { it.rank }

    private fun lists() = listOf(
        list("a") to listOf(item("Commanders vs Giants", "https://a.example/1"), item("Mystery vs A", "https://a.example/m")),
        list("b") to listOf(item("Giants at Commanders", "https://b.example/1"), item("Mystery vs B", "https://b.example/m")),
        list("c") to listOf(item("Commanders vs Giants HD", "https://c.example/1")),
    )

    private fun build(order: List<String>?) = GameFolders.build("nfl", listOf(gWsh), lists(), now, zone, order)

    @Test fun noOrderKeepsSavedOrder() {
        val f = build(null)
        assertEquals(listOf("a", "b", "c"), f.games.single().streams.map { it.listId })
        assertEquals(listOf("a", "b"), f.other.map { it.listId })
        assertEquals(f, GameFolders.build("nfl", listOf(gWsh), lists(), now, zone))
        assertEquals(f, build(emptyList()))
    }

    @Test fun gameStreamsFollowTheRanking() {
        assertEquals(listOf("c", "a", "b"), build(listOf("c", "a", "b")).games.single().streams.map { it.listId })
        assertEquals(listOf("b", "c", "a"), build(listOf("b", "c", "a")).games.single().streams.map { it.listId })
        assertEquals(listOf("b", "a"), build(listOf("b", "c", "a")).other.map { it.listId })
        // lists missing from the order go last, in saved order
        assertEquals(listOf("c", "a", "b"), build(listOf("c")).games.single().streams.map { it.listId })
    }

    @Test fun betterRankWinsADuplicateAddress() {
        val ls = listOf(
            list("a") to listOf(item("Commanders vs Giants", "https://x.example/e")),
            list("b") to listOf(item("Commanders vs Giants", "https://x.example/e/")),
        )
        assertEquals(listOf("b"), GameFolders.build("nfl", listOf(gWsh), ls, now, zone, listOf("b", "a")).games.single().streams.map { it.listId })
    }

    @Test fun alwaysOnFollowsTheRanking() {
        val old = now - 3 * 24 * 3_600_000L
        val ls = listOf(
            list("a") to listOf(item("Channel A", "https://a.example/ch", old)),
            list("b") to listOf(item("Channel B", "https://b.example/ch", old), item("Live game", "https://b.example/g", now)),
        )
        assertEquals(listOf("a", "b"), AlwaysOn.collect(ls, now).map { it.listId })
        assertEquals(listOf("b", "a"), AlwaysOn.collect(ls, now, listOf("b", "a")).map { it.listId })
    }

    @Test fun orderIsRankedThenUnrankedInSavedOrder() {
        val ls = listOf(list("a"), list("b", 2), list("c"), list("d", 1))
        assertEquals(listOf("d", "b", "a", "c"), Ranking.order(ls))
        assertEquals(listOf("a", "b", "c"), Ranking.order(listOf(list("a"), list("b"), list("c"))))
    }

    @Test fun settingATakenRankMovesOthersDown() {
        val ls = listOf(list("a", 1), list("b", 2), list("c"))
        val cFirst = Ranking.set(ls, "c", 1)
        assertEquals(listOf(2, 3, 1), ranks(cFirst))
        // moving a ranked list down: the ones it passes move up
        assertEquals(listOf(2, 1, null), ranks(Ranking.set(ls, "a", 2)))
        // unranking closes the gap
        assertEquals(listOf(null, 1, null), ranks(Ranking.set(ls, "a", null)))
        // a rank past the end lands at the end
        assertEquals(listOf(1, 2, 3), ranks(Ranking.set(ls, "c", 9)))
        // saved order never changes
        assertEquals(listOf("a", "b", "c"), cFirst.map { it.id })
    }

    @Test fun unknownIdOrNoChangeGivesSameObject() {
        val ls = listOf(list("a", 1), list("b"))
        assertSame(ls, Ranking.set(ls, "nope", 1))
        assertSame(ls, Ranking.set(ls, "a", 1))
        assertSame(ls, Ranking.set(ls, "b", null))
    }

    @Test fun normalizeFixesGapsAndTies() {
        assertEquals(listOf(1, 2, null), ranks(Ranking.normalize(listOf(list("a", 3), list("b", 7), list("c")))))
        assertEquals(listOf(1, 2), ranks(Ranking.normalize(listOf(list("a", 1), list("b", 1)))))
        val clean = listOf(list("a", 1), list("b"))
        assertSame(clean, Ranking.normalize(clean))
    }

    @Test fun sortGroupsByListKeepingItemOrder() {
        val xs = listOf("a1", "b1", "a2", "c1")
        assertEquals(listOf("c1", "a1", "a2", "b1"), Ranking.sort(xs, listOf("c", "a", "b")) { it.take(1) })
        assertEquals(listOf("a1", "a2", "b1", "c1"), Ranking.sort(xs, listOf("a")) { it.take(1) })
        assertSame(xs, Ranking.sort(xs, null) { it.take(1) })
        assertTrue(Ranking.sort(emptyList<String>(), listOf("a")) { it }.isEmpty())
    }

    @Test fun jsonRoundTripAndOldJson() {
        val ls = listOf(list("a"), list("b", 1), list("c", 2))
        assertEquals(ls, SavedListsJson.parse(SavedListsJson.toJson(ls)))
        val old = SavedListsJson.parse("""[{"id":"x","name":"Old","url":"https://o.example","sports":["nfl"]}]""")
        assertNull(old.single().rank)
        // the old single "preferred" mark becomes rank 1
        val pref = SavedListsJson.parse("""[{"id":"x","url":"https://o.example"},{"id":"y","url":"https://p.example","preferred":true}]""")
        assertEquals(listOf(null, 1), ranks(pref))
        // gaps in stored ranks are closed on read
        val gap = SavedListsJson.parse("""[{"id":"x","url":"https://o.example","rank":3},{"id":"y","url":"https://p.example","rank":null}]""")
        assertEquals(listOf(1, null), ranks(gap))
    }

    @Test fun labels() {
        assertEquals("#2", Ranking.label(2))
        assertEquals("", Ranking.label(null))
    }

    private fun at(id: String, url: String, rank: Int? = null) = SavedList(id, "List $id", url, setOf("nfl"), rank)

    @Test fun listsOfOneSiteShareARank() {
        // TVAPP NFL #1, TVAPP CFB #2, NTV #3 -> thetvapp #1 (both lists), ntv #2
        val ls = listOf(at("nfl", "https://thetvapp.plus/nfl", 1), at("cfb", "https://thetvapp.plus/cfb", 2), at("ntv", "https://ntv.st/", 3))
        assertEquals(listOf(1, 1, 2), ranks(Ranking.normalize(ls)))
    }

    @Test fun rankingASiteMovesAllItsLists() {
        val ls = Ranking.normalize(listOf(at("nfl", "https://thetvapp.plus/nfl", 1), at("cfb", "https://thetvapp.plus/cfb", 1),
            at("ntv", "https://ntv.st/", 2), at("reed", "https://reedstreams.to/", null)))
        assertEquals(listOf(2, 2, 1, null), ranks(Ranking.set(ls, "ntv", 1)))
        assertEquals(listOf(3, 3, 1, 2), ranks(Ranking.set(Ranking.set(ls, "ntv", 1), "reed", 2)))
        assertEquals(listOf(null, null, 1, null), ranks(Ranking.set(ls, "cfb", null)))
    }

    @Test fun sameNameOnAnotherEndingIsTheSameSite() {
        assertEquals(Ranking.siteOf(at("a", "https://thetvapp.plus/x")), Ranking.siteOf(at("b", "https://www.thetvapp.to/y")))
    }

    @Test fun aSitesListsStayTogetherInOrder() {
        val ls = listOf(at("nfl", "https://thetvapp.plus/nfl"), at("ntv", "https://ntv.st/"), at("cfb", "https://thetvapp.plus/cfb"))
        assertEquals(listOf("nfl", "cfb", "ntv"), Ranking.order(ls))
    }
}
