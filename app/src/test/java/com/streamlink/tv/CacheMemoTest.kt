package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CacheMemoTest {
    @Test fun loadsOnceAndReturnsTheSameObject() {
        val m = CacheMemo<List<Int>>()
        var loads = 0
        val a = m.get("x") { loads++; listOf(1) }
        val b = m.get("x") { loads++; listOf(2) }
        assertSame(a, b)
        assertEquals(1, loads)
    }

    @Test fun missingValueIsRememberedToo() {
        val m = CacheMemo<String>()
        var loads = 0
        assertNull(m.get("x") { loads++; null })
        assertNull(m.get("x") { loads++; "late" })
        assertEquals(1, loads)
    }

    @Test fun invalidateLoadsAgain() {
        val m = CacheMemo<List<Int>>()
        val a = m.get("x") { listOf(1) }
        m.invalidate("x")
        val b = m.get("x") { listOf(1) }
        assertNotSame(a, b)
    }

    @Test fun putReplacesAndIdsAreIndependent() {
        val m = CacheMemo<String>()
        m.get("a") { "one" }
        m.get("b") { "two" }
        m.put("a", "three")
        assertEquals("three", m.get("a") { "never" })
        assertEquals("two", m.get("b") { "never" })
        m.invalidate("a")
        assertEquals("two", m.get("b") { "never" })
    }
}
