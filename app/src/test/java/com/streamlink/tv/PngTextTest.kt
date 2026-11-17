package com.streamlink.tv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater

class PngTextTest {
    private val media = { b: ByteArray, n: Int -> UnwrapDataSource.looksLikeMedia(b, n, 0) }

    /** 20 MPEG-TS packets with distinct payloads. */
    private fun ts(): ByteArray = ByteArray(188 * 20) { i -> if (i % 188 == 0) 0x47 else (i * 7 % 251).toByte() }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val len = data.size
        out.write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
        val t = type.toByteArray(Charsets.ISO_8859_1)
        out.write(t); out.write(data)
        val crc = CRC32().apply { update(t); update(data) }.value
        out.write(byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte()))
    }

    private fun deflate(b: ByteArray): ByteArray {
        val d = Deflater(); d.setInput(b); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end(); return out.toByteArray()
    }

    private fun png(notes: List<Pair<String, ByteArray>>, kind: String = "iTXt", compress: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        chunk(out, "IHDR", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0))
        chunk(out, "IDAT", deflate(byteArrayOf(0, 1, 2, 3, 4)))
        for ((k, text) in notes) {
            val body = ByteArrayOutputStream()
            body.write(k.toByteArray(Charsets.ISO_8859_1)); body.write(0)
            when (kind) {
                "iTXt" -> { body.write(if (compress) 1 else 0); body.write(0); body.write(0); body.write(0); body.write(if (compress) deflate(text) else text) }
                "zTXt" -> { body.write(0); body.write(deflate(text)) }
                else -> body.write(text)
            }
            chunk(out, kind, body.toByteArray())
        }
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun split(s: String, parts: Int): List<String> {
        val size = (s.length + parts - 1) / parts
        return s.chunked(size)
    }

    @Test fun numberedCompressedBase64NotesOutOfOrder() {
        val video = ts()
        val pieces = split(Base64.getEncoder().encodeToString(video), 4).mapIndexed { i, p -> "payload-%04d".format(i + 1) to p.toByteArray() }
        val shuffled = listOf(pieces[2], pieces[0], pieces[3], pieces[1])
        val file = png(shuffled)
        val d = PngText.decode(file, file.size, media)
        assertNotNull(d)
        assertArrayEquals(video, d!!.bytes)
        assertEquals(4, d.notes)
    }

    @Test fun eachNotePaddedSeparately() {
        val video = ts()
        val a = Base64.getEncoder().encodeToString(video.copyOfRange(0, 1001))
        val b = Base64.getEncoder().encodeToString(video.copyOfRange(1001, video.size))
        val file = png(listOf("x1" to a.toByteArray(), "x2" to b.toByteArray()), kind = "tEXt")
        assertArrayEquals(video, PngText.decode(file, file.size, media)!!.bytes)
    }

    @Test fun zTXtRawBytes() {
        val video = ts()
        val file = png(listOf("a" to video.copyOfRange(0, 2000), "b" to video.copyOfRange(2000, video.size)), kind = "zTXt")
        assertArrayEquals(video, PngText.decode(file, file.size, media)!!.bytes)
    }

    @Test fun ordinaryCaptionsAreNotVideo() {
        val file = png(listOf("Title" to "Hello".toByteArray(), "Author" to "Someone".toByteArray()), compress = false)
        assertNull(PngText.decode(file, file.size, media))
    }

    @Test fun cutOffFileGivesNothing() {
        val pieces = split(Base64.getEncoder().encodeToString(ts()), 3).mapIndexed { i, p -> "p$i" to p.toByteArray() }
        val file = png(pieces)
        assertNull(PngText.decode(file, file.size - 40, media))
    }
}
