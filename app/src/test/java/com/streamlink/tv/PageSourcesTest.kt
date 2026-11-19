package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSourcesTest {
    private val raw = """{"done":true,"urls":[{"url":"https://s.example/g?source=1","label":"Source 2","index":1},{"url":"https://s.example/g?source=2","label":"","index":2},{"url":"javascript:x","label":"bad","index":3}]}"""

    @Test fun parsesDoneAnswerAndWaitsOtherwise() {
        val f = PageSources.parse(raw)!!
        assertEquals(2, f.size)
        assertEquals("Source 2", f[0].label)
        assertNull(PageSources.parse("""{"done":false}"""))
        assertNull(PageSources.parse(null))
        assertNull(PageSources.parse("null"))
        assertNull(PageSources.parse("garbage"))
        assertEquals(0, PageSources.parse(""" "{\"done\":true,\"urls\":[]}" """.trim())!!.size)
    }

    @Test fun entriesAreOpenPageWithThePageAsReferer() {
        val s = PageSources.toSources("https://s.example/g", PageSources.parse(raw)!!)
        assertEquals(2, s.size)
        assertEquals("Source 2 · this site", s[0].label)
        assertTrue(s.all { it.openPage && !it.verified && it.referers == listOf("https://s.example/g") && it.playerUrl == it.url })
    }

    @Test fun neverThePlayingPageOrARepeatAndAtMostEight() {
        val f = (0..12).map { PageSources.Found("https://s.example/g?source=$it", "L$it", it) } + PageSources.Found("https://s.example/g?source=0/", "dup", 0)
        val s = PageSources.toSources("https://s.example/g?source=3", f, setOf("https://s.example/g?source=4"))
        assertEquals(8, s.size)
        assertTrue(s.none { it.url.endsWith("source=3") || it.url.endsWith("source=4") })
        assertEquals(s.size, s.map { it.url }.toSet().size)
    }
}
