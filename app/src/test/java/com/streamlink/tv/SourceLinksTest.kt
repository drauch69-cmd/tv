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

    @Test fun gateWaitsTenSecondsThenNeedsBuffer() {
        assertEquals(LinkOrder.Gate.WAIT, LinkOrder.gate(5_000, 30_000))
        assertEquals(LinkOrder.Gate.START, LinkOrder.gate(10_000, 8_000))
        assertEquals(LinkOrder.Gate.WAIT, LinkOrder.gate(12_000, 7_999))
        assertEquals(LinkOrder.Gate.WAIT, LinkOrder.gate(69_000, 1_000))
        assertEquals(LinkOrder.Gate.GIVE_UP, LinkOrder.gate(70_000, 1_000))
        assertEquals(LinkOrder.Gate.START, LinkOrder.gate(70_000, 9_000))
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
}
