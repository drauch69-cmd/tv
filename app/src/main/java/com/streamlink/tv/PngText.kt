package com.streamlink.tv

import java.util.Base64
import java.util.zip.Inflater

/**
 * Video hidden in a picture's TEXT notes: a tiny real PNG plus many iTXt / zTXt / tEXt chunks whose
 * text, joined in order, is the video (usually base64, sometimes compressed per chunk). Seen on a site
 * whose 6 s video pieces were a 64x64 picture with ~70 numbered notes, each 32 KB of base64.
 *
 * Works by structure only: whatever the notes are called, they are ordered by the number at the end
 * of their keyword (when every keyword has one) or by position, and the joined text must decode to
 * something that starts like real video. Pure Kotlin; unit tested.
 */
object PngText {
    class Decoded(val bytes: ByteArray, val notes: Int, val encoding: String)

    private const val MAX_TEXT = 48 * 1024 * 1024

    /** Returns the video hidden in [b]'s text notes, or null when there is none. */
    fun decode(b: ByteArray, n: Int, looksLikeMedia: (ByteArray, Int) -> Boolean): Decoded? {
        if (n < 33 || (b[0].toInt() and 0xFF) != 0x89 || b[1] != 'P'.code.toByte()) return null
        val notes = ArrayList<Pair<String, ByteArray>>()
        var total = 0L
        var pos = 8
        while (pos + 12 <= n) {
            val len = int32(b, pos)
            if (len < 0 || pos + 12L + len > n) return null // cut off: wait for the whole file
            val type = String(b, pos + 4, 4, Charsets.ISO_8859_1)
            val data = pos + 8
            val note = when (type) {
                "tEXt" -> textChunk(b, data, len)
                "zTXt" -> zTextChunk(b, data, len)
                "iTXt" -> iTextChunk(b, data, len)
                else -> null
            }
            if (note != null) {
                total += note.second.size
                if (total > MAX_TEXT) return null
                notes.add(note)
            }
            if (type == "IEND") break
            pos += 12 + len
        }
        if (notes.size < 2) return null // one ordinary caption is not a hidden video

        val ordered = order(notes)
        val joined = joinAll(ordered.map { it.second })

        // 1) The notes may hold the raw bytes themselves.
        if (looksLikeMedia(joined, joined.size)) return Decoded(joined, notes.size, "raw")
        // 2) Usually base64 (whitespace allowed between notes).
        val b64 = base64(joined)
        if (b64 != null && looksLikeMedia(b64, b64.size)) return Decoded(b64, notes.size, "base64")
        // 3) Each note base64 on its own (padding inside the joined text breaks one big decode).
        val each = try {
            joinAll(ordered.map { base64(it.second) ?: return null })
        } catch (_: Exception) {
            null
        }
        if (each != null && looksLikeMedia(each, each.size)) return Decoded(each, notes.size, "base64 per note")
        return null
    }

    /** By the number at the end of the keyword when every note has one (payload-0001, ...), else as found. */
    internal fun order(notes: List<Pair<String, ByteArray>>): List<Pair<String, ByteArray>> {
        val nums = notes.map { Regex("(\\d+)\\D*$").find(it.first)?.groupValues?.get(1)?.toLongOrNull() }
        if (nums.any { it == null } || nums.toSet().size != nums.size) return notes
        return notes.indices.sortedBy { nums[it]!! }.map { notes[it] }
    }

    private fun joinAll(parts: List<ByteArray>): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, at, p.size)
            at += p.size
        }
        return out
    }

    /** Standard or URL-safe base64, whitespace ignored; null when it isn't base64. */
    internal fun base64(text: ByteArray): ByteArray? {
        val sb = StringBuilder(text.size)
        for (x in text) {
            val c = (x.toInt() and 0xFF).toChar()
            when {
                c.isWhitespace() -> continue
                c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/' || c == '=' || c == '-' || c == '_' -> sb.append(c)
                else -> return null
            }
        }
        if (sb.isEmpty()) return null
        val s = sb.toString()
        return try {
            if (s.indexOf('-') >= 0 || s.indexOf('_') >= 0) Base64.getUrlDecoder().decode(s) else Base64.getDecoder().decode(s)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun nul(b: ByteArray, from: Int, end: Int): Int {
        var i = from
        while (i < end) {
            if (b[i].toInt() == 0) return i
            i++
        }
        return -1
    }

    private fun textChunk(b: ByteArray, at: Int, len: Int): Pair<String, ByteArray>? {
        val end = at + len
        val z = nul(b, at, end)
        if (z < 0) return null
        return String(b, at, z - at, Charsets.ISO_8859_1) to b.copyOfRange(z + 1, end)
    }

    private fun zTextChunk(b: ByteArray, at: Int, len: Int): Pair<String, ByteArray>? {
        val end = at + len
        val z = nul(b, at, end)
        if (z < 0 || z + 2 > end) return null
        val text = inflate(b, z + 2, end - z - 2) ?: return null
        return String(b, at, z - at, Charsets.ISO_8859_1) to text
    }

    private fun iTextChunk(b: ByteArray, at: Int, len: Int): Pair<String, ByteArray>? {
        val end = at + len
        val z = nul(b, at, end)
        if (z < 0 || z + 3 > end) return null
        val compressed = b[z + 1].toInt() != 0
        val lang = nul(b, z + 3, end)
        if (lang < 0) return null
        val translated = nul(b, lang + 1, end)
        if (translated < 0) return null
        val start = translated + 1
        val text = if (compressed) inflate(b, start, end - start) ?: return null else b.copyOfRange(start, end)
        return String(b, at, z - at, Charsets.ISO_8859_1) to text
    }

    private fun inflate(b: ByteArray, at: Int, len: Int): ByteArray? {
        val inf = Inflater()
        return try {
            inf.setInput(b, at, len)
            val out = java.io.ByteArrayOutputStream(len * 2)
            val buf = ByteArray(64 * 1024)
            while (!inf.finished()) {
                val r = inf.inflate(buf)
                if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, r)
                if (out.size() > MAX_TEXT) return null
            }
            if (!inf.finished()) null else out.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            inf.end()
        }
    }

    private fun int32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
}
