package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceMergeTest {
    private fun s(u: String, open: Boolean = false) = Source(u, emptyMap(), listOf(""), u, openPage = open)

    @Test fun goesBeforeFirstOpenPageEntry() {
        val r = SourceMerge.insertExtras(listOf(s("p1"), s("p2"), s("alt", true)), listOf(s("n2", true), s("n3", true)))
        assertEquals(listOf("p1", "p2", "n2", "n3", "alt"), r.map { it.url })
    }

    @Test fun goesLastWithNoOpenPageEntry() {
        assertEquals(listOf("p1", "n2"), SourceMerge.insertExtras(listOf(s("p1")), listOf(s("n2", true))).map { it.url })
    }

    @Test fun knownAddressesAreSkipped() {
        val base = listOf(s("p1"), s("n2", true))
        assertEquals(base, SourceMerge.insertExtras(base, listOf(s("n2", true))))
    }
}
