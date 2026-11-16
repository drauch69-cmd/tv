package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class DisguiseStripTest {
    private fun ts(packets: Int) = ByteArray(packets * 188) { i -> if (i % 188 == 0) 0x47 else 1 }

    @Test fun plainTsUntouched() = assertEquals(0, DisguiseStripDataSource.tsStart(ts(10), 1880))

    @Test fun fakePngHeaderSkipped() {
        val fake = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(66) { 2 }
        val data = fake + ts(10)
        assertEquals(70, DisguiseStripDataSource.tsStart(data, data.size))
    }

    @Test fun nonTsUntouched() = assertEquals(0, DisguiseStripDataSource.tsStart(ByteArray(5000) { 3 }, 5000))
}
