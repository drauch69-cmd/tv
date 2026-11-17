package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LearnedShapesTest {
    @Test fun newestFirstNoDuplicatesCapped() {
        assertEquals(listOf("a"), LearnedShapes.merge(emptyList(), "a"))
        assertEquals(listOf("b", "a"), LearnedShapes.merge(listOf("a"), "b"))
        assertEquals(listOf("a", "b"), LearnedShapes.merge(listOf("b", "a"), "a"))
        assertEquals(listOf("e", "a", "b", "c"), LearnedShapes.merge(listOf("a", "b", "c", "d"), "e"))
    }

    @Test fun hostNormalised() {
        assertEquals("example.com", LearnedShapes.norm(" WWW.Example.com. "))
        assertNull(LearnedShapes.norm(""))
        assertNull(LearnedShapes.norm(null))
    }
}
