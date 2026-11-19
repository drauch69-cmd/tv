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

    private fun page(url: String, label: String = "p") =
        Source(url, emptyMap(), emptyList(), label, verified = false, playerUrl = url, openPage = true)

    @Test fun automaticFallbackSkipsOpenPageEntries() {
        val e = page("https://p2.example/embed/2")
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
