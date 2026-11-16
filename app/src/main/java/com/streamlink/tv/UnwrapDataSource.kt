package com.streamlink.tv

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

@OptIn(UnstableApi::class)
class UnwrapDataSourceFactory(private val upstream: DataSource.Factory) : DataSource.Factory {
    override fun createDataSource(): DataSource = UnwrapDataSource(upstream.createDataSource())
}

/**
 * Wraps the player's downloader and does two things:
 *  1. Serves playlists that were already downloaded while checking streams (see [PrefetchCache]).
 *  2. Some sites disguise each video chunk as an image by putting a fake picture header in front
 *     of it. This finds where the real video starts and skips the junk before the player sees it.
 */
@OptIn(UnstableApi::class)
class UnwrapDataSource(private val upstream: DataSource) : DataSource {

    private var buf: ByteArray? = null
    private var bufPos = 0
    private var bufEnd = 0
    private var cachedUri: Uri? = null
    private var upstreamOpen = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        buf = null
        bufPos = 0
        bufEnd = 0
        cachedUri = null

        if (dataSpec.position == 0L) {
            val cached = PrefetchCache.take(dataSpec.uri.toString())
            if (cached != null) {
                val bytes = cached.first
                buf = bytes
                bufEnd = bytes.size
                cachedUri = Uri.parse(cached.second)
                Report.logOnce("prefetch", "Player reused the checked playlist")
                val len = bytes.size.toLong()
                return if (dataSpec.length == C.LENGTH_UNSET.toLong()) len else minOf(len, dataSpec.length)
            }
        }

        val length = upstream.open(dataSpec)
        upstreamOpen = true
        val path = dataSpec.uri.path?.lowercase() ?: ""
        if (dataSpec.position != 0L || path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith(".key")) {
            return length
        }

        val head = ByteArray(PEEK)
        var n = 0
        while (n < PEEK) {
            val r = upstream.read(head, n, PEEK - n)
            if (r == C.RESULT_END_OF_INPUT) break
            n += r
        }
        val skip = junkPrefix(head, n)
        Report.logOnce("chunk-head", describeChunk(dataSpec, head, n, skip))
        if (skip > 0) Report.logOnce("unwrap", "Unwrapped disguised video chunks ($skip junk bytes removed)")
        buf = head
        bufPos = skip
        bufEnd = n
        return if (length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else length - skip
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val b = buf
        if (b != null) {
            if (bufPos < bufEnd) {
                val count = minOf(length, bufEnd - bufPos)
                System.arraycopy(b, bufPos, buffer, offset, count)
                bufPos += count
                return count
            }
            if (cachedUri != null) return C.RESULT_END_OF_INPUT
        }
        return upstream.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = cachedUri ?: upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (cachedUri != null) emptyMap() else upstream.responseHeaders

    override fun close() {
        buf = null
        bufPos = 0
        bufEnd = 0
        cachedUri = null
        if (upstreamOpen) {
            upstreamOpen = false
            upstream.close()
        }
    }

    /** One line for the report: what the first video chunk really looks like. */
    private fun describeChunk(spec: DataSpec, head: ByteArray, n: Int, skip: Int): String {
        val type = upstream.responseHeaders.entries
            .firstOrNull { it.key.equals("Content-Type", true) }?.value?.firstOrNull() ?: "?"
        val hex = (0 until minOf(n, 16)).joinToString(" ") { String.format("%02X", head[it]) }
        val text = String(head, 0, minOf(n, 60), Charsets.ISO_8859_1).replace(Regex("[^\\x20-\\x7E]"), ".")
        val guess = when {
            n == 0 -> "empty"
            skip > 0 -> "disguised video, unwrapped"
            head[0] == SYNC -> "normal MPEG-TS video"
            isBox(head, 4) -> "normal MP4 video"
            text.startsWith("<") || text.contains("<html", true) -> "a web page, not video"
            text.startsWith("#EXTM3U") -> "a playlist, not video"
            else -> "unknown format, possibly encrypted"
        }
        return "First video chunk ${Report.short(spec.uri.toString())}: $n bytes, type $type, " +
            "starts [$hex] \"$text\" -> $guess"
    }

    companion object {
        private const val PEEK = 64 * 1024
        private const val SYNC = 0x47.toByte()
        private const val TS = 188

        /** How many junk bytes come before the real video, or 0 if it already looks normal. */
        fun junkPrefix(b: ByteArray, n: Int): Int {
            if (n < 8) return 0
            if (b[0] == SYNC && (n <= TS || b[TS] == SYNC)) return 0 // already MPEG-TS
            if (isBox(b, 4)) return 0 // already fragmented MP4
            if (b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()) return 0

            // MPEG-TS: five sync bytes exactly one packet apart.
            var i = 1
            while (i + TS * 4 < n) {
                if (b[i] == SYNC && b[i + TS] == SYNC && b[i + TS * 2] == SYNC &&
                    b[i + TS * 3] == SYNC && b[i + TS * 4] == SYNC
                ) return i
                i++
            }
            // Fragmented MP4: a box header (size + type) further in.
            i = 8
            while (i + 4 <= n) {
                if (isBox(b, i, listOf("ftyp", "styp", "moof"))) return i - 4
                i++
            }
            return 0
        }

        private fun isBox(b: ByteArray, at: Int, types: List<String> = listOf("ftyp", "styp", "moof", "sidx", "emsg")): Boolean {
            if (at + 4 > b.size) return false
            val t = String(b, at, 4, Charsets.ISO_8859_1)
            return t in types
        }
    }
}
