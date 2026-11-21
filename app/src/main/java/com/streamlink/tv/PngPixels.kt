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

    /** Why the last [decodeOne] returned null (for the report). */
    @Volatile var lastFail: String = ""

    private class Header(val width: Int, val height: Int, val channels: Int, val idat: List<IntArray>)

    /** All packings this picture could hold, most likely first. Used by tests. */
    fun decode(b: ByteArray, n: Int): List<Decoded> =
        listOfNotNull(decodeOne(b, n, dropAlpha = false), decodeOne(b, n, dropAlpha = true))

    /** Decode the pixel bytes, optionally skipping the transparency channel. Null if not possible. */
    fun decodeOne(b: ByteArray, n: Int, dropAlpha: Boolean): Decoded? {
        val h = header(b, n) ?: run { lastFail = "unsupported picture format"; return null }
        val hasAlpha = h.channels == 2 || h.channels == 4
        if (dropAlpha && !hasAlpha) { lastFail = "no transparency channel to drop"; return null }

        val bpp = h.channels
        val stride = h.width * bpp
        val outPerRow = if (dropAlpha) h.width * (bpp - 1) else stride
        val outSize = outPerRow.toLong() * h.height
        if (outSize <= 0 || outSize > MAX_OUT) { lastFail = "picture too big to unpack ($outSize bytes)"; return null }
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
                if (!inflateExactly(filter, 1) || !inflateExactly(cur, stride)) {
                    lastFail = "image data ran out at row $y of ${h.height}"; return null
                }
                if (!unfilterRow(filter[0].toInt() and 0xFF, cur, prev, bpp)) {
                    lastFail = "unknown row filter ${filter[0].toInt() and 0xFF} at row $y"; return null
                }
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
        } catch (e: Exception) {
            lastFail = "unpacking failed (${e.javaClass.simpleName})"
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

    /**
     * One line describing the picture's layout for the report: size, bit depth, colour type,
     * interlacing and its chunk list (type and size; repeated chunks counted). Null if not a PNG.
     */
    fun describe(b: ByteArray, n: Int): String? {
        if (n < 33 || (b[0].toInt() and 0xFF) != 0x89 || b[1] != 'P'.code.toByte()) return null
        var info = ""
        val chunks = ArrayList<Pair<String, Long>>() // type, total bytes; consecutive repeats merged
        val counts = ArrayList<Int>()
        var pos = 8
        var tail = 0
        while (pos + 8 <= n) {
            val len = int32(b, pos)
            if (len < 0 || pos + 12L + len > n) { info += " (chunk at byte $pos runs past the end)"; break }
            val type = String(b, pos + 4, 4, Charsets.ISO_8859_1).replace(Regex("[^\\x20-\\x7E]"), "?")
            if (type == "IHDR" && len >= 13) {
                val ct = b[pos + 17].toInt() and 0xFF
                val ctName = when (ct) { 0 -> "grey"; 2 -> "RGB"; 3 -> "palette"; 4 -> "grey+alpha"; 6 -> "RGBA"; else -> "type $ct" }
                info = "${int32(b, pos + 8)}x${int32(b, pos + 12)}, ${b[pos + 16].toInt() and 0xFF}-bit $ctName, " +
                    "interlace ${b[pos + 20].toInt() and 0xFF}, compression ${b[pos + 18].toInt() and 0xFF}, filter ${b[pos + 19].toInt() and 0xFF}"
            }
            if (chunks.isNotEmpty() && chunks.last().first == type) {
                chunks[chunks.size - 1] = type to (chunks.last().second + len)
                counts[counts.size - 1]++
            } else {
                chunks.add(type to len.toLong()); counts.add(1)
            }
            pos += 12 + len
            if (type == "IEND") { tail = n - pos; break }
        }
        val list = chunks.indices.joinToString(", ") { i ->
            val (t, sz) = chunks[i]
            if (counts[i] > 1) "$t x${counts[i]} ($sz bytes)" else "$t ($sz bytes)"
        }
        return "PNG $info; chunks: $list; $tail bytes after the end"
    }

    private val STANDARD_CHUNKS = setOf(
        "IHDR", "PLTE", "IDAT", "IEND", "tRNS", "cHRM", "gAMA", "iCCP", "sBIT", "sRGB", "cICP", "mDCV", "cLLI",
        "tEXt", "zTXt", "iTXt", "bKGD", "hIST", "pHYs", "sPLT", "eXIf", "tIME", "acTL", "fcTL", "fdAT",
    )

    /** A non-standard section of the picture: its type, where its data starts and how long it is. */
    class Section(val type: String, val at: Int, val len: Int)

    /**
     * The picture's non-standard sections (a site's own chunk type, e.g. "tsDa" on streamfree Willow, 2026-11-21:
     * a 1x1 picture carrying a 3 MB section), largest first. These can hold the video as is.
     */
    fun privateSections(b: ByteArray, n: Int): List<Section> {
        if (n < 33 || (b[0].toInt() and 0xFF) != 0x89 || b[1] != 'P'.code.toByte()) return emptyList()
        val out = ArrayList<Section>()
        var pos = 8
        while (pos + 8 <= n) {
            val len = int32(b, pos)
            if (len < 0 || pos + 12L + len > n) break
            val type = String(b, pos + 4, 4, Charsets.ISO_8859_1)
            if (type == "IEND") break
            if (type !in STANDARD_CHUNKS && len > 0) out.add(Section(type, pos + 8, len))
            pos += 12 + len
        }
        return out.sortedByDescending { it.len }
    }

    /** Inflate [len] bytes at [at] if they are zlib data; null if not (or too big). */
    fun inflate(b: ByteArray, at: Int, len: Int): ByteArray? {
        if (len < 2 || (b[at].toInt() and 0x0F) != 8) return null
        val inf = Inflater()
        return try {
            inf.setInput(b, at, len)
            val out = java.io.ByteArrayOutputStream(len * 2)
            val buf = ByteArray(64 * 1024)
            while (!inf.finished()) {
                val r = inf.inflate(buf)
                if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, r)
                if (out.size() > MAX_OUT) return null
            }
            if (out.size() == 0) null else out.toByteArray()
        } catch (_: Exception) { null } finally { inf.end() }
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
