package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PreferredTest {
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-11-19T15:00:00Z").toEpochMilli()
    private fun at(s: String) = Instant.parse(s).toEpochMilli()

    private fun ts(d: String, s: String, a: String, l: String, n: String) = TeamSide(d, s, a, l, n, "0", "")
    private val wsh = ts("Washington Commanders", "Commanders", "WSH", "Washington", "Commanders")
    private val nyg = ts("New York Giants", "Giants", "NYG", "New York", "Giants")
    private val gWsh = Game("1", "nfl", at("2026-11-19T20:00:00Z"), "pre", "", 0, "", nyg, wsh, "")

    private fun item(title: String, url: String, start: Long? = null) = ListItem(title, "", url, start, "", "", false, null)
    private fun list(id: String, pref: Boolean = false) = SavedList(id, "List $id", "https://$id.example", setOf("nfl"), pref)

    private fun lists() = listOf(
        list("a") to listOf(item("Commanders vs Giants", "https://a.example/1"), item("Mystery A", "https://a.example/m")),
        list("b") to listOf(item("Giants at Commanders", "https://b.example/1"), item("Mystery B", "https://b.example/m")),
        list("c") to listOf(item("Commanders vs Giants HD", "https://c.example/1")),
    )

    private fun build(pref: String?) = GameFolders.build("nfl", listOf(gWsh), lists(), now, zone, pref)

    @Test fun nullPreferredKeepsListOrder() {
        val f = build(null)
        assertEquals(listOf("a", "b", "c"), f.games.single().streams.map { it.listId })
        assertEquals(listOf("a", "b"), f.other.map { it.listId })
        assertEquals(f, GameFolders.build("nfl", listOf(gWsh), lists(), now, zone))
    }

    @Test fun preferredListsStreamComesFirstOthersKeepOrder() {
        val f = build("c")
        assertEquals(listOf("c", "a", "b"), f.games.single().streams.map { it.listId })
        assertEquals(listOf("b", "a"), build("b").other.map { it.listId })
        assertEquals(listOf("b", "a", "c"), build("b").games.single().streams.map { it.listId })
    }

    @Test fun unknownPreferredIdChangesNothing() {
        assertEquals(build(null), build("zzz"))
    }

    @Test fun preferredWinsADuplicateAddress() {
        val ls = listOf(
            list("a") to listOf(item("Commanders vs Giants", "https://x.example/e")),
            list("b") to listOf(item("Commanders vs Giants", "https://x.example/e/")),
        )
        val f = GameFolders.build("nfl", listOf(gWsh), ls, now, zone, "b")
        assertEquals(listOf("b"), f.games.single().streams.map { it.listId })
    }

    @Test fun alwaysOnPreferredFirst() {
        val old = now - 3 * 24 * 3_600_000L
        val ls = listOf(
            list("a") to listOf(item("Channel A", "https://a.example/ch", old)),
            list("b") to listOf(item("Channel B", "https://b.example/ch", old), item("Live game", "https://b.example/g", now)),
        )
        assertEquals(listOf("a", "b"), AlwaysOn.collect(ls, now).map { it.listId })
        assertEquals(listOf("a", "b"), AlwaysOn.collect(ls, now, null).map { it.listId })
        assertEquals(listOf("b", "a"), AlwaysOn.collect(ls, now, "b").map { it.listId })
    }

    @Test fun jsonRoundTripAndOldJson() {
        val ls = listOf(list("a"), list("b", pref = true))
        val back = SavedListsJson.parse(SavedListsJson.toJson(ls))
        assertEquals(ls, back)
        assertEquals("b", Preferred.idOf(back))
        val old = SavedListsJson.parse("""[{"id":"x","name":"Old","url":"https://o.example","sports":["nfl"]}]""")
        assertFalse(old.single().preferred)
        assertNull(Preferred.idOf(old))
    }

    @Test fun onlyOnePreferred() {
        val ls = listOf(list("a", true), list("b"), list("c"))
        val toB = Preferred.set(ls, "b", true)
        assertEquals(listOf(false, true, false), toB.map { it.preferred })
        assertEquals("b", Preferred.idOf(toB))
        assertEquals(listOf(false, false, false), Preferred.set(toB, "b", false).map { it.preferred })
        // clearing a list that is not the preferred one leaves the preferred one alone
        assertEquals("b", Preferred.idOf(Preferred.set(toB, "c", false)))
        assertEquals(listOf(false, false, false), Preferred.set(toB, null, false).map { it.preferred })
        // unknown id: unchanged (same instance)
        assertSame(ls, Preferred.set(ls, "nope", true))
        // damaged data with two marks: setting one fixes it
        val two = listOf(list("a", true), list("b", true))
        assertEquals(listOf(false, true), Preferred.set(two, "b", true).map { it.preferred })
    }

    @Test fun firstKeepsOrderOtherwise() {
        val xs = listOf("a1", "b1", "a2", "c1")
        assertEquals(listOf("a1", "a2", "b1", "c1"), Preferred.first(xs, "a") { it.take(1) })
        assertSame(xs, Preferred.first(xs, null) { it.take(1) })
        assertSame(xs, Preferred.first(xs, "z") { it.take(1) })
        assertTrue(Preferred.first(emptyList<String>(), "a") { it }.isEmpty())
    }
}
