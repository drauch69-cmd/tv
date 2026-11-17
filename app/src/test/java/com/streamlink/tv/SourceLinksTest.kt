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
}
