package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

class PngPixelsTest {
    private val video = ByteArray(188 * 40) { if (it % 188 == 0) 0x47 else (it * 7).toByte() }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        fun int(v: Int) { out.write(v ushr 24); out.write(v ushr 16); out.write(v ushr 8); out.write(v) }
        int(data.size)
        val td = type.toByteArray(Charsets.ISO_8859_1) + data
        out.write(td)
        val crc = CRC32().apply { update(td) }
        int(crc.value.toInt())
    }

    /** A real PNG whose pixels carry [video] in R,G,B (plus opaque alpha when [rgba]), using Sub filter rows. */
    private fun png(rgba: Boolean): ByteArray {
        val ch = if (rgba) 4 else 3
        val w = 64
        val h = (video.size / 3 + w) / w + 1
        val raw = ByteArrayOutputStream()
        var i = 0
        for (y in 0 until h) {
            raw.write(1) // Sub filter, to exercise unfiltering
            val row = ByteArray(w * ch)
            for (x in 0 until w) {
                for (c in 0 until 3) row[x * ch + c] = if (i < video.size) video[i++] else 0
                if (rgba) row[x * ch + 3] = 0xFF.toByte()
            }
            for (k in row.indices) {
                val left = if (k >= ch) row[k - ch].toInt() else 0
                raw.write((row[k].toInt() - left) and 0xFF)
            }
        }
        val def = Deflater().apply { setInput(raw.toByteArray()); finish() }
        val z = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!def.finished()) z.write(buf, 0, def.deflate(buf))

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArrayOutputStream().apply {
            fun int(v: Int) { write(v ushr 24); write(v ushr 16); write(v ushr 8); write(v) }
            int(w); int(h); write(8); write(if (rgba) 6 else 2); write(0); write(0); write(0)
        }.toByteArray()
        chunk(out, "IHDR", ihdr)
        chunk(out, "IDAT", z.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun check(bytes: ByteArray, layout: String) {
        val hit = PngPixels.decode(bytes, bytes.size).firstOrNull { d ->
            UnwrapDataSource.looksLikeMedia(d.bytes, d.bytes.size, 0)
        }
        assertTrue("no layout found video", hit != null)
        assertEquals(layout, hit!!.layout)
        assertTrue(hit.bytes.copyOf(video.size).contentEquals(video))
    }

    @Test fun rgbPng() = check(png(rgba = false), "all 3 channel(s)")

    @Test fun rgbaPng() = check(png(rgba = true), "RGB, alpha dropped")

    @Test fun notPng() = assertTrue(PngPixels.decode(ByteArray(100), 100).isEmpty())

    /** streamfree Willow (2026-11-21): a 1x1 picture whose own "tsDa" section holds the video as is. */
    @Test
    fun privateSectionHoldsVideo() {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        chunk(out, "IHDR", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0))
        chunk(out, "IDAT", byteArrayOf(0x78, 0x01, 0x63, 0x60, 0, 0, 0, 5, 0, 1, 0))
        chunk(out, "tEXt", "a\u0000b".toByteArray())
        chunk(out, "tsDa", video)
        chunk(out, "IEND", ByteArray(0))
        val b = out.toByteArray()
        val secs = PngPixels.privateSections(b, b.size)
        assertEquals(listOf("tsDa"), secs.map { it.type })
        val s = secs[0]
        assertEquals(video.size, s.len)
        assertTrue(UnwrapDataSource.looksLikeMedia(b.copyOfRange(s.at, s.at + s.len), s.len, 0))
        assertTrue(PngPixels.describe(b, b.size)!!.contains("tsDa"))
    }

    @Test
    fun inflateUnzipsSection() {
        val def = Deflater().apply { setInput(video); finish() }
        val z = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!def.finished()) z.write(buf, 0, def.deflate(buf))
        val zb = z.toByteArray()
        assertTrue(PngPixels.inflate(zb, 0, zb.size)!!.contentEquals(video))
    }
}
