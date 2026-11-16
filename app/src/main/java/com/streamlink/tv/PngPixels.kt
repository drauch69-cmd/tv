package com.streamlink.tv

import java.util.zip.Inflater

/**
 * Some sites hide each video chunk *inside* a PNG picture: the picture's pixel bytes are the
 * video. Their web player decodes the image and reads the bytes back out. This does the same:
 * decompress the image data and undo PNG's row filters, one row at a time, writing the pixel
 * bytes straight into the result (one pass, no intermediate copies).
 *
 * Sites pack the video either in every channel, or in the colour channels only (skipping
 * transparency). [preferDropAlpha] remembers which one worked so later chunks decode once.
 *
 * Plain JVM code, so it can be unit tested.
 */
object PngPixels {

    class Decoded(val bytes: ByteArray, val layout: String)

    /** Which packing worked last time; tried first on the next chunk. */
    @Volatile var preferDropAlpha = false

    private const val MAX_OUT = 24 * 1024 * 1024

    private class Header(val width: Int, val height: Int, val channels: Int, val idat: List<IntArray>)

    /** All packings this picture could hold, most likely first. Used by tests. */
    fun decode(b: ByteArray, n: Int): List<Decoded> =
        listOfNotNull(decodeOne(b, n, dropAlpha = false), decodeOne(b, n, dropAlpha = true))

    /** Decode the pixel bytes, optionally skipping the transparency channel. Null if not possible. */
    fun decodeOne(b: ByteArray, n: Int, dropAlpha: Boolean): Decoded? {
        val h = header(b, n) ?: return null
        val hasAlpha = h.channels == 2 || h.channels == 4
        if (dropAlpha && !hasAlpha) return null

        val bpp = h.channels
        val stride = h.width * bpp
        val outPerRow = if (dropAlpha) h.width * (bpp - 1) else stride
        val outSize = outPerRow.toLong() * h.height
        if (outSize <= 0 || outSize > MAX_OUT) return null
        val out = ByteArray(outSize.toInt())

        var prev = ByteArray(stride) // the row above; all zeros for the first row, as PNG defines
        var cur = ByteArray(stride)
        val filter = ByteArray(1)
        val inflater = Inflater()
        var nextChunk = 0

        fun inflateExactly(dst: ByteArray, len: Int): Boolean {
            var got = 0
            while (got < len) {
                val r = inflater.inflate(dst, got, len - got)
                if (r > 0) {
                    got += r
                    continue
                }
                if (inflater.needsInput()) {
                    if (nextChunk >= h.idat.size) return false
                    val c = h.idat[nextChunk++]
                    inflater.setInput(b, c[0], c[1])
                } else {
                    return false // finished early, or needs a dictionary: not a picture we can read
                }
            }
            return true
        }

        try {
            for (y in 0 until h.height) {
                if (!inflateExactly(filter, 1) || !inflateExactly(cur, stride)) return null
                if (!unfilterRow(filter[0].toInt() and 0xFF, cur, prev, bpp)) return null
                val base = y * outPerRow
                if (!dropAlpha) {
                    System.arraycopy(cur, 0, out, base, stride)
                } else {
                    var j = base
                    for (x in 0 until stride) if (x % bpp != bpp - 1) out[j++] = cur[x]
                }
                val t = prev
                prev = cur
                cur = t
            }
        } catch (_: Exception) {
            return null
        } finally {
            inflater.end()
        }

        val layout = when {
            !dropAlpha -> "all $bpp channel(s)"
            bpp == 4 -> "RGB, alpha dropped"
            else -> "grey, alpha dropped"
        }
        return Decoded(out, layout)
    }

    /** Undo one row's PNG filter, in place. */
    private fun unfilterRow(filter: Int, cur: ByteArray, prev: ByteArray, bpp: Int): Boolean {
        when (filter) {
            0 -> {}
            1 -> for (x in bpp until cur.size) cur[x] = (cur[x] + cur[x - bpp]).toByte()
            2 -> for (x in cur.indices) cur[x] = (cur[x] + prev[x]).toByte()
            3 -> for (x in cur.indices) {
                val a = if (x >= bpp) cur[x - bpp].toInt() and 0xFF else 0
                val up = prev[x].toInt() and 0xFF
                cur[x] = (cur[x] + ((a + up) ushr 1)).toByte()
            }
            4 -> for (x in cur.indices) {
                val a = if (x >= bpp) cur[x - bpp].toInt() and 0xFF else 0
                val up = prev[x].toInt() and 0xFF
                val c = if (x >= bpp) prev[x - bpp].toInt() and 0xFF else 0
                cur[x] = (cur[x] + paeth(a, up, c)).toByte()
            }
            else -> return false
        }
        return true
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = Math.abs(p - a)
        val pb = Math.abs(p - b)
        val pc = Math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    /** Reads the picture's size/format and where its compressed image data sits, without copying it. */
    private fun header(b: ByteArray, n: Int): Header? {
        if (n < 33 || (b[0].toInt() and 0xFF) != 0x89 || b[1] != 'P'.code.toByte()) return null
        var width = 0
        var height = 0
        var bitDepth = 0
        var colorType = 0
        var interlace = 0
        val idat = ArrayList<IntArray>()
        var pos = 8
        while (pos + 8 <= n) {
            val len = int32(b, pos)
            if (len < 0 || pos + 12L + len > n) break
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
                "IDAT" -> idat.add(intArrayOf(data, len))
                "IEND" -> break
            }
            pos += 12 + len
        }
        if (width <= 0 || height <= 0 || bitDepth != 8 || interlace != 0 || idat.isEmpty()) return null
        val channels = when (colorType) {
            0, 3 -> 1   // grey, palette (indices)
            2 -> 3      // RGB
            4 -> 2      // grey + alpha
            6 -> 4      // RGBA
            else -> return null
        }
        return Header(width, height, channels, idat)
    }

    private fun int32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
}
