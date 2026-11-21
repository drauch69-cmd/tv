package com.streamlink.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RowEdgesTest {
    @Test fun endsAreBlockedMiddleIsNot() {
        assertTrue(RowEdges.blocked(0, 5, left = true))
        assertFalse(RowEdges.blocked(0, 5, left = false))
        assertTrue(RowEdges.blocked(4, 5, left = false))
        assertFalse(RowEdges.blocked(2, 5, left = true))
        assertTrue(RowEdges.blocked(0, 1, left = true) && RowEdges.blocked(0, 1, left = false))
        assertFalse(RowEdges.blocked(-1, 5, left = true))
    }
}
