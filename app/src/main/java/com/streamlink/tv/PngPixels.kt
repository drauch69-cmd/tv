package com.streamlink.tv

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Some sites hide each video chunk *inside* a PNG picture: the picture's pixel bytes are the
 * video. Their web player decodes the image and reads the bytes back out. This does the same:
 * decompress the image data, undo PNG's row filters, and hand back the raw pixel bytes in the
 * ways a site might have packed them (all channels, or colour without the alpha channel).
 *
 * Plain JVM code, so it can be unit tested.
 */
object PngPixels {

    class Decoded(val bytes: ByteArray, val layout: String)

    /** Returns candidate byte layouts, or an empty list if this isn't a decodable 8-bit PNG. */
    fun decode(b: ByteArray, n: Int): List<Decoded> {
        if (n < 33 || (b[0].toInt() and 0xFF) != 0x89 || b[1] != 'P'.code.toByte()) return emptyList()
        var width = 0
        var height = 0
        var bitDepth = 0
        var colorType = 0
        var interlace = 0
        val idat = ByteArrayOutputStream()
        var pos = 8
        while (pos + 8 <= n) {
            val len = int32(b, pos)
            if (len < 0 || pos + 12 + len > n) break
            val type = String(b, pos + 4, 4, Charsets.ISO_8859_1)
            val data = pos + 8
            when (type) {
                "IHDR" -> {
                    width = int32(b, data)
                    height = int32(b, data + 4)
                    bitDepth = b[data + 8].toInt() and 0xFF
                    colorType = b[data + 9].toInt() and 0xFF
                    interlace = b[data + 12].toInt() and 0xFF
                }
                "IDAT" -> idat.write(b, data, len)
                "IEND" -> break
            }
            pos += 12 + len
        }
        if (width <= 0 || height <= 0 || bitDepth != 8 || interlace != 0) return emptyList()
        val channels = when (colorType) {
            0, 3 -> 1   // grey, palette (indices)
            2 -> 3      // RGB
            4 -> 2      // grey + alpha
            6 -> 4      // RGBA
            else -> return emptyList()
        }
        val stride = width * channels
        val rawSize = height.toLong() * (stride + 1)
        if (rawSize > 64L * 1024 * 1024) return emptyList()

        val raw = ByteArray(rawSize.toInt())
        val inflater = Inflater()
        try {
            inflater.setInput(idat.toByteArray())
            var got = 0
            while (got < raw.size && !inflater.finished()) {
                val r = inflater.inflate(raw, got, raw.size - got)
                if (r == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                got += r
            }
            if (got < raw.size) return emptyList()
        } catch (_: Exception) {
            return emptyList()
        } finally {
            inflater.end()
        }

        val pixels = unfilter(raw, width, height, channels) ?: return emptyList()
        val out = ArrayList<Decoded>()
        out.add(Decoded(pixels, "all $channels channel(s)"))
        if (channels == 4) out.add(Decoded(dropEvery(pixels, 4), "RGB, alpha dropped"))
        if (channels == 2) out.add(Decoded(dropEvery(pixels, 2), "grey, alpha dropped"))
        return out
    }

    private fun unfilter(raw: ByteArray, width: Int, height: Int, bpp: Int): ByteArray? {
        val stride = width * bpp
        val out = ByteArray(stride * height)
        var src = 0
        for (y in 0 until height) {
            val filter = raw[src].toInt() and 0xFF
            src++
            val row = y * stride
            val prev = row - stride
            for (x in 0 until stride) {
                val v = raw[src + x].toInt() and 0xFF
                val a = if (x >= bpp) out[row + x - bpp].toInt() and 0xFF else 0
                val up = if (y > 0) out[prev + x].toInt() and 0xFF else 0
                val c = if (y > 0 && x >= bpp) out[prev + x - bpp].toInt() and 0xFF else 0
                val res = when (filter) {
                    0 -> v
                    1 -> v + a
                    2 -> v + up
                    3 -> v + ((a + up) ushr 1)
                    4 -> v + paeth(a, up, c)
                    else -> return null
                }
                out[row + x] = res.toByte()
            }
            src += stride
        }
        return out
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = Math.abs(p - a)
        val pb = Math.abs(p - b)
        val pc = Math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun dropEvery(src: ByteArray, every: Int): ByteArray {
        val out = ByteArray(src.size - src.size / every)
        var j = 0
        for (i in src.indices) if (i % every != every - 1) out[j++] = src[i]
        return out.copyOf(j)
    }

    private fun int32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
}
