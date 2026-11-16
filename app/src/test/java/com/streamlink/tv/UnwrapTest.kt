package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnwrapTest {
    private fun ts(packets: Int) = ByteArray(packets * 188) { if (it % 188 == 0) 0x47 else 0x11 }

    @Test
    fun findsTsAfterJunk() {
        val data = ByteArray(300) { 0x55 } + ts(10)
        assertEquals(300, UnwrapDataSource.findMedia(data, data.size, 1))
        assertTrue(UnwrapDataSource.looksLikeMedia(data, data.size, 300))
    }

    @Test
    fun plainTsIsMedia() {
        val data = ts(10)
        assertTrue(UnwrapDataSource.looksLikeMedia(data, data.size, 0))
    }

    @Test
    fun randomBytesAreNotMedia() {
        val r = java.util.Random(1)
        val data = ByteArray(200_000).also { r.nextBytes(it) }
        assertNull(UnwrapDataSource.findMedia(data, data.size, 1))
    }
}
