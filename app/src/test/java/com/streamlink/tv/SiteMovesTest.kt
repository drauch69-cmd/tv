package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteMovesTest {
    @Test fun siteNamesAndEndings() {
        assertEquals("crackstreams.mx", SiteKey.of("WWW.Crackstreams.MX."))
        assertEquals("crackstreams", SiteKey.name("crackstreams.mx"))
        assertEquals("reedstreams", SiteKey.name("api.reedstreams.link"))
        assertEquals("site", SiteKey.name("www.site.co.uk"))
        assertNull(SiteKey.name("10.0.0.2"))
        assertNull(SiteKey.name("localhost"))
        assertEquals("site.example", SiteKey.ofUrl("https://www.site.example/nfl?x=1"))
    }

    @Test fun sameNameNeedsALongEnoughName() {
        assertTrue(SiteKey.sameName("crackstreams.mx", "crackstreams.to"))
        assertTrue(SiteKey.sameName("reedstreams.to", "api.reedstreams.link"))
        assertFalse(SiteKey.sameName("ntv.st", "ntv.to")) // "ntv" is too short to trust
        assertFalse(SiteKey.sameName("crackstreams.mx", "buffstreams.mx"))
    }

    @Test fun candidatesSkipOwnEnding() {
        val c = SiteKey.candidates("crackstreams.mx")
        assertEquals("crackstreams.to", c.first())
        assertFalse("crackstreams.mx" in c)
        assertTrue("crackstreams.is" in c)
        assertTrue(SiteKey.candidates("ntv.st").isEmpty())
    }

    @Test fun withHostKeepsPathAndQuery() {
        assertEquals("https://new.example/nfl/live?d=1#top", SiteKey.withHost("https://old.example/nfl/live?d=1#top", "new.example"))
        assertEquals("https://new.example/", SiteKey.withHost("https://old.example", "new.example"))
        assertNull(SiteKey.withHost("not a url", "new.example"))
    }

    private val lists = listOf(
        SavedList("a", "NFL", "https://www.crackstreams.mx/nfl", setOf("nfl"), 1),
        SavedList("b", "crackstreams.mx", "https://crackstreams.mx/cfb", setOf("cfb")),
        SavedList("c", "Reed", "https://reedstreams.to/"),
    )

    @Test fun moveTakesEveryListOfTheSite() {
        val r = SiteMove.apply(lists, "crackstreams.mx", "crackstreams.to")
        assertEquals(2, r.moved)
        assertEquals(SavedList("a", "NFL", "https://crackstreams.to/nfl", setOf("nfl"), 1), r.lists[0])
        assertEquals(SavedList("b", "crackstreams.to", "https://crackstreams.to/cfb", setOf("cfb")), r.lists[1])
        assertSame(lists[2], r.lists[2])
    }

    @Test fun moveNeverMakesADuplicate() {
        val have = lists + SavedList("d", "Already", "https://crackstreams.to/nfl")
        val r = SiteMove.apply(have, "crackstreams.mx", "crackstreams.to")
        assertEquals(1, r.moved)
        assertEquals("https://www.crackstreams.mx/nfl", r.lists[0].url)
        assertEquals("https://crackstreams.to/cfb", r.lists[1].url)
    }

    @Test fun nothingToMoveGivesTheSameLists() {
        val r = SiteMove.apply(lists, "other.example", "new.example")
        assertEquals(0, r.moved)
        assertSame(lists, r.lists)
    }

    @Test fun groupsFollowTheBestList() {
        val g = SiteMove.groups(listOf(lists[2], lists[0], lists[1]))
        assertEquals(listOf("reedstreams.to", "crackstreams.mx"), g.map { it.first })
        assertEquals(listOf("a", "b"), g[1].second.map { it.id })
    }

    // ---- site health ----

    private val h = 60 * 60_000L
    private val t = 1_700_000_000_000L // a real clock time (0 means "never" in the entries)

    @Test fun notWorkingAfterADayOfFailedLoads() {
        var e: SiteHealth.Entry? = null
        e = SiteHealth.after(e, SiteHealth.Read.FAILED, t + 0)
        e = SiteHealth.after(e, SiteHealth.Read.FAILED, t + 2 * h)
        e = SiteHealth.after(e, SiteHealth.Read.FAILED, t + 4 * h)
        assertFalse(SiteHealth.notWorking(e, t + 4 * h)) // one bad evening is not enough
        assertTrue(SiteHealth.notWorking(e, t + 25 * h))
        assertFalse(SiteHealth.notWorking(SiteHealth.after(e, SiteHealth.Read.OK, t + 26 * h), t + 26 * h))
    }

    @Test fun emptyReadsNeedThreeDays() {
        var e: SiteHealth.Entry? = null
        repeat(4) { e = SiteHealth.after(e, SiteHealth.Read.EMPTY, t + it * 10 * h) }
        assertFalse(SiteHealth.notWorking(e, t + 30 * h))
        assertTrue(SiteHealth.notWorking(e, t + 73 * h))
    }

    @Test fun moveCheckAfterAFailOrTwoEmptiesAndNotTooOften() {
        val one = SiteHealth.after(null, SiteHealth.Read.FAILED, t + 7 * h)
        assertTrue(SiteHealth.wantsMoveCheck(one, SiteHealth.Read.FAILED, t + 7 * h))
        val empty1 = SiteHealth.after(null, SiteHealth.Read.EMPTY, t + 7 * h)
        assertFalse(SiteHealth.wantsMoveCheck(empty1, SiteHealth.Read.EMPTY, t + 7 * h))
        val empty2 = SiteHealth.after(empty1, SiteHealth.Read.EMPTY, t + 8 * h)
        assertTrue(SiteHealth.wantsMoveCheck(empty2, SiteHealth.Read.EMPTY, t + 8 * h))
        val checked = one.copy(moveCheckAt = t + 7 * h)
        assertFalse(SiteHealth.wantsMoveCheck(SiteHealth.after(checked, SiteHealth.Read.FAILED, t + 8 * h), SiteHealth.Read.FAILED, t + 8 * h))
        assertTrue(SiteHealth.wantsMoveCheck(SiteHealth.after(checked, SiteHealth.Read.FAILED, t + 14 * h), SiteHealth.Read.FAILED, t + 14 * h))
    }

    @Test fun readKinds() {
        val item = ListItem("A v B", "", "https://x.example/1", null, "", "", false, null)
        assertEquals(SiteHealth.Read.OK, SiteHealth.kind(ListResult("u", "", listOf(item), "automatic", null)))
        assertEquals(SiteHealth.Read.FAILED, SiteHealth.kind(ListResult("u", "", emptyList(), "automatic", "timed out")))
        assertEquals(SiteHealth.Read.EMPTY, SiteHealth.kind(ListResult("u", "", emptyList(), "automatic", ListResult.NO_EVENTS, noEvents = true)))
    }

    @Test fun movedNoteFades() {
        val e = SiteHealth.Entry(movedFrom = "old.example", movedAt = t)
        assertEquals("old.example", SiteHealth.movedNote(e, t + 10 * h))
        assertNull(SiteHealth.movedNote(e, t + 4 * 24 * h))
    }

    // ---- memory carried over ----

    @Test fun hostMemoryRename() {
        assertEquals("{\"new.example\":{\"fails\":1}}", HostMemory.renamed("{\"old.example\":{\"fails\":1}}", "old.example", "new.example"))
        val both = "{\"old.example\":1,\"new.example\":2}"
        assertEquals(both, HostMemory.renamed(both, "old.example", "new.example"))
        assertEquals("{\"a\":1}", HostMemory.renamed("{\"a\":1}", "old.example", "new.example"))
    }

    // ---- recipes by site name ----

    @Test fun recipeMatchesTheSameNameOnAnotherEnding() {
        val r = ListRecipes.parse(
            """{"sites":[{"host":"crackstreams.mx","zone":"UTC"},{"host":"ntv.st","zone":"UTC"}]}"""
        )
        assertEquals("crackstreams.mx", ListRecipes.match(r, "crackstreams.to")?.host)
        assertEquals("crackstreams.mx", ListRecipes.match(r, "www.crackstreams.mx")?.host)
        assertNull(ListRecipes.match(r, "ntv.to")) // short names never match by name
        assertNull(ListRecipes.match(r, "other.example"))
    }

    @Test fun feedAddressFollowsAMove() {
        assertEquals("https://streamfree.to/streams", ListRecipes.followMove("https://streamfree.top/streams", "streamfree.top", "streamfree.to"))
        assertEquals("https://streamfree.top/streams", ListRecipes.followMove("https://streamfree.top/streams", "streamfree.top", "streamfree.top"))
        assertEquals("https://api.reedstreams.link/x", ListRecipes.followMove("https://api.reedstreams.link/x", "reedstreams.to", "reedstreams.is"))
    }

    // ---- failure tally ----

    @Test fun tallyCountsTheLastWeek() {
        val d = 24 * h
        val ev = listOf(
            FailureTally.Event(0, "stalled", "a.example", "cdn.example"),
            FailureTally.Event(8 * d, "stalled", "a.example", "cdn.example"),
            FailureTally.Event(8 * d, "server refused", "b.example", "cdn.example"),
            FailureTally.Event(9 * d, "stalled", "a.example", ""),
        )
        val lines = FailureTally.lines(ev, 9 * d)
        assertEquals("Playback failures, last 7 days: 3", lines[0])
        assertEquals("  by kind: stalled ×2, server refused ×1", lines[1])
        assertEquals("  by site: a.example ×2, b.example ×1", lines[2])
        assertEquals("  by stream server: cdn.example ×2", lines[3])
        assertTrue(FailureTally.lines(ev, 30 * d).isEmpty())
    }
}
