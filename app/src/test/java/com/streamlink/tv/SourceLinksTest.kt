package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceLinksTest {
    private val json = """{"found":true,"count":3,"active":1,"labels":["A-1","  A-2 ","A-3"],"xs":[10,20,30],"ys":[5,5,5],"iw":1280}"""

    @Test fun parsesFindAnswer() {
        val s = LinkOrder.parseFind(json)!!
        assertEquals(3, s.count)
        assertEquals(1, s.active)
        assertEquals(listOf("A-1", "A-2", "A-3"), s.labels)
        assertEquals(20f, s.xs[1], 0f)
        assertEquals(1280f, s.iw, 0f)
    }

    @Test fun parsesDoubleQuotedAnswerFromEvaluateJavascript() {
        val quoted = "\"" + json.replace("\"", "\\\"") + "\""
        assertEquals(3, LinkOrder.parseFind(quoted)!!.count)
    }

    @Test fun rejectsNoSwitcherAndGarbage() {
        assertNull(LinkOrder.parseFind("""{"found":false,"why":"x"}"""))
        assertNull(LinkOrder.parseFind(null))
        assertNull(LinkOrder.parseFind("null"))
        assertNull(LinkOrder.parseFind("not json"))
        assertNull(LinkOrder.parseFind("""{"found":true,"labels":["only one"]}"""))
    }

    @Test fun noLinksLineSaysWhetherPlayerBoxWasSeen() {
        assertEquals("no link buttons found next to the player (player box: no; no player box)",
            LinkOrder.noLinksLine("""{"found":false,"why":"no player box"}"""))
        assertEquals("no link buttons found next to the player (player box: yes; fewer than 2 clickable elements near the player)",
            LinkOrder.noLinksLine("""{"found":false,"why":"fewer than 2 clickable elements near the player"}"""))
        assertEquals("no link buttons found next to the player (player box: yes; 1 items, need 2 to 8)",
            LinkOrder.noLinksLine("""{"found":true,"labels":["only"]}"""))
        assertEquals("no link buttons found next to the player (player box: unknown; no answer from the page)",
            LinkOrder.noLinksLine(null))
    }

    @Test fun unknownActiveIsMinusOne() {
        val s = LinkOrder.parseFind("""{"found":true,"active":-1,"labels":["a","b"]}""")!!
        assertEquals(-1, s.active)
        assertEquals(2, s.xs.size)
    }

    @Test fun nextToTrySkipsTried() {
        assertEquals(0, LinkOrder.nextToTry(4, emptySet()))
        assertEquals(2, LinkOrder.nextToTry(4, setOf(0, 1, 3)))
        assertNull(LinkOrder.nextToTry(3, setOf(0, 1, 2)))
    }

    @Test fun labelsShowLinkTextOrHost() {
        assertEquals("A-2 · adaptive", LinkOrder.sourceLabel("A-2", "cdn.example", "adaptive", true))
        assertEquals("cdn.example · single quality", LinkOrder.sourceLabel("", "cdn.example", "single quality", true))
        assertEquals("A-2 · adaptive · didn't respond", LinkOrder.sourceLabel("A-2", "h", "adaptive", false))
    }

    @Test fun cleanLabelCollapsesAndShortens() {
        assertEquals("a b", LinkOrder.cleanLabel("  a \n  b "))
        assertEquals(24, LinkOrder.cleanLabel("x".repeat(60)).length)
    }

    @Test fun defaultLinkGoesFirstOnlyWhenGood() {
        val ranked = listOf("b", "c", "a")
        assertEquals(listOf("a", "b", "c"), LinkOrder.defaultFirst(ranked) { it == "a" })
        assertEquals(ranked, LinkOrder.defaultFirst(ranked) { false })
        assertEquals(ranked, LinkOrder.defaultFirst(ranked) { it == "b" })
    }

    @Test fun duplicateLabelsGetNumbers() {
        assertEquals(listOf("A", "A (2)", "B", "A (3)"), LinkOrder.uniqueLabels(listOf("A", "A", "B", "A")))
        assertEquals(listOf("A", "B"), LinkOrder.uniqueLabels(listOf("A", "B")))
    }

    private fun src(url: String, label: String = "x", def: Boolean = false) = Source(url, emptyMap(), listOf(""), label, def)

    @Test fun mergeAppendsNewAndKeepsIndexes() {
        val have = listOf(src("https://a.example/p/1.m3u8?t=1", "cur"))
        val extra = listOf(
            src("https://a.example/p/1.m3u8?t=2", "A-1", def = true),
            src("https://b.example/q/2.m3u8", "A-2"),
            src("https://c.example/r/3.m3u8", "A-3"),
        )
        val m = LinkOrder.mergeExtra(have, extra)
        assertEquals(listOf("cur", "A-2", "A-3"), m.map { it.label })
        assertEquals(have[0], m[0])
    }

    @Test fun mergeDropsSameUrlOrHostPathAndDuplicatesWithinExtra() {
        val have = listOf(src("https://a.example/p/1.m3u8?t=1"))
        val extra = listOf(src("https://a.example/p/1.m3u8?t=9", "same path"), src("https://B.example/z.m3u8", "n1"), src("https://b.example/z.m3u8?k=2", "n2"))
        assertEquals(listOf("x", "n1"), LinkOrder.mergeExtra(have, extra).map { it.label })
    }

    @Test fun mergeRespectsMax() {
        val extra = (1..20).map { src("https://h$it.example/a.m3u8", "L$it") }
        assertEquals(8, LinkOrder.mergeExtra(listOf(src("https://z.example/a.m3u8")), extra).size)
    }

    @Test fun gateWaitsTenSecondsThenNeedsNoRecentStall() {
        assertEquals(LinkOrder.Gate.WAIT, LinkOrder.gate(5_000, null))
        assertEquals(LinkOrder.Gate.START, LinkOrder.gate(10_000, null))
        assertEquals(LinkOrder.Gate.WAIT, LinkOrder.gate(12_000, 9_999))
        assertEquals(LinkOrder.Gate.START, LinkOrder.gate(12_000, 10_000))
        assertEquals(LinkOrder.Gate.WAIT, LinkOrder.gate(89_000, 1_000))
        assertEquals(LinkOrder.Gate.GIVE_UP, LinkOrder.gate(90_000, 1_000))
        assertEquals(LinkOrder.Gate.START, LinkOrder.gate(90_000, 20_000))
    }

    @Test fun onlyPageStreamsWithoutAlternativesGetARun() {
        assertEquals(true, LinkOrder.wantsLinkRun("https://e.example/a", 1))
        assertEquals(false, LinkOrder.wantsLinkRun("https://e.example/a", 2))
        assertEquals(false, LinkOrder.wantsLinkRun("file:///x", 1))
        assertEquals(false, LinkOrder.wantsLinkRun("", 1))
    }

    @Test fun segmentsAreBlockedButPlaylistsAreNot() {
        assertEquals(true, StreamSniffer.isSegmentUrl("https://c.example/v/seg1.ts?x=1"))
        assertEquals(true, StreamSniffer.isSegmentUrl("https://c.example/v/a.M4S"))
        assertEquals(false, StreamSniffer.isSegmentUrl("https://c.example/v/index.m3u8"))
        assertEquals(false, StreamSniffer.isSegmentUrl("https://c.example/v/manifest.mpd?a=seg.ts"))
        assertEquals(false, StreamSniffer.isSegmentUrl("https://c.example/page.html"))
    }

    @Test fun newPlayerFrameNeedsRealNonAdPageDifferentFromPrevious() {
        assertEquals(true, LinkOrder.isNewPlayerFrame("https://p2.example/embed/2", "https://p1.example/embed/1"))
        assertEquals(false, LinkOrder.isNewPlayerFrame("https://p1.example/embed/1#x", "https://p1.example/embed/1"))
        assertEquals(false, LinkOrder.isNewPlayerFrame("about:blank", ""))
        assertEquals(false, LinkOrder.isNewPlayerFrame("javascript:void(0)", ""))
        assertEquals(false, LinkOrder.isNewPlayerFrame("https://ads.doubleclick.net/x", ""))
        assertEquals(false, LinkOrder.isNewPlayerFrame("https://sub.exoclick.com/x", "https://p1.example/a"))
        assertEquals(true, LinkOrder.isNewPlayerFrame("https://p1.example/a", ""))
    }

    @Test fun playersToOpenSkipsAdsDuplicatesAndTheDefault() {
        val players = listOf(
            "A-1" to "https://p1.example/embed/1",
            "A-2" to "https://p2.example/embed/2",
            "A-3" to "https://p2.example/embed/2#again",
            "A-4" to "https://ads.exoclick.com/f",
            "A-5" to "about:blank",
            "A-6" to "https://p3.example/embed/3",
        )
        assertEquals(listOf("A-2", "A-6"), LinkOrder.playersToOpen("https://p1.example/embed/1", players).map { it.first })
        assertEquals(listOf("A-1", "A-2", "A-6"), LinkOrder.playersToOpen("", players).map { it.first })
        assertEquals(1, LinkOrder.playersToOpen("", players, max = 1).size)
    }

    @Test fun openPageEntryOnlyWhenNothingVerified() {
        assertNull(LinkOrder.openPageEntry("Link-2", "https://p2.example/embed/2", 1, emptyList()))
        val e = LinkOrder.openPageEntry("Link-2", "https://p2.example/embed/2", 0, listOf("cur"))!!
        assertEquals("Link-2 · site player", e.label)
        assertEquals(true, e.openPage)
        assertEquals("https://p2.example/embed/2", e.url)
        assertEquals("https://p2.example/embed/2", e.playerUrl)
        assertEquals(false, e.verified)
        assertEquals(false, e.isDefaultLink)
        assertNull(LinkOrder.openPageEntry("L", "about:blank", 0, emptyList()))
        assertNull(LinkOrder.openPageEntry("L", "javascript:void(0)", 0, emptyList()))
    }

    @Test fun openPageLabelFallsBackToHostAndStaysDistinct() {
        assertEquals("p2.example · site player", LinkOrder.openPageEntry(" ", "https://p2.example/e/2", 0, emptyList())!!.label)
        val first = "A · site player"
        assertEquals("A · site player (2)", LinkOrder.openPageEntry("A", "https://p.example/1", 0, listOf(first))!!.label)
        assertEquals("A · site player (3)", LinkOrder.openPageEntry("A", "https://p.example/1", 0, listOf(first, "$first (2)"))!!.label)
    }

    @Test fun openPageEntriesSurviveMergeAndAreNotDuplicated() {
        val have = listOf(src("https://a.example/p/1.m3u8", "cur"))
        val e = LinkOrder.openPageEntry("B", "https://p2.example/embed/2", 0, emptyList())!!
        val m = LinkOrder.mergeExtra(LinkOrder.mergeExtra(have, listOf(e)), listOf(e))
        assertEquals(listOf("cur", "B · site player"), m.map { it.label })
        assertEquals(true, m[1].openPage)
    }

    @Test fun automaticFallbackSkipsOpenPageEntries() {
        val e = LinkOrder.openPageEntry("B", "https://p2.example/embed/2", 0, emptyList())!!
        val list = listOf(src("https://a.example/1.m3u8"), e, src("https://c.example/3.m3u8"), e.copy(url = "https://p3.example/e"))
        assertEquals(2, LinkOrder.nextPlayable(list, 0))
        assertNull(LinkOrder.nextPlayable(list, 2))
        assertEquals(0, LinkOrder.firstPlayable(list))
        assertEquals(1, LinkOrder.firstPlayable(listOf(e, src("https://z.example/z.m3u8"))))
        assertNull(LinkOrder.firstPlayable(listOf(e)))
        assertEquals(false, LinkOrder.canRenew(e))
        assertEquals(true, LinkOrder.canRenew(list[0]))
    }
}
