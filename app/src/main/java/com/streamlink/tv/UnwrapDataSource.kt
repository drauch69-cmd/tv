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
    private var tap: SampleRecorder.ChunkTap? = null

    /** Sample recording must never affect playback: any failure in it is swallowed. */
    private inline fun sample(block: () -> Unit) {
        try { block() } catch (_: Throwable) {}
    }

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        buf = null
        bufPos = 0
        bufEnd = 0
        cachedUri = null
        bufOnly = false
        tap = null
        head = ByteArray(0)
        n = 0
        eof = false

        if (dataSpec.position == 0L) {
            val cached = PrefetchCache.take(dataSpec.uri.toString())
            if (cached != null) {
                val bytes = fixPlaylist(cached.first)
                buf = bytes
                bufEnd = bytes.size
                cachedUri = Uri.parse(cached.second)
                Report.logOnce("prefetch", "Player reused the checked playlist")
                logPlaylist(bytes, bytes.size)
                sample { SampleRecorder.playlist(dataSpec.uri.toString(), cached.first, cached.first.size, false, "(checked earlier)") }
                val len = bytes.size.toLong()
                return if (dataSpec.length == C.LENGTH_UNSET.toLong()) len else minOf(len, dataSpec.length)
            }
        }

        val length = upstream.open(dataSpec)
        upstreamOpen = true
        val path = dataSpec.uri.path?.lowercase() ?: ""
        if (dataSpec.position == 0L && (path.endsWith(".m3u8") || dataSpec.uri.toString().contains("m3u8"))) {
            // Playlists are small: read the whole thing so its structure can go in the report.
            head = ByteArray(16 * 1024)
            n = 0
            eof = false
            fill(512 * 1024)
            if (looksLikePlaylist(head, n)) {
                logPlaylist(head, n)
                sample { SampleRecorder.playlist(dataSpec.uri.toString(), head, n, !eof, contentTypeHeader()) }
                if (eof) {
                    // Whole playlist in hand: correct known typos before the player reads it.
                    val fixed = fixPlaylist(head.copyOf(n))
                    buf = fixed
                    bufPos = 0
                    bufEnd = fixed.size
                    bufOnly = true
                    return fixed.size.toLong()
                }
                buf = head
                bufPos = 0
                bufEnd = n
                return length
            }
            // Not a playlist after all (e.g. a video chunk with "m3u8" in its address): treat as video.
            buf = null
        }
        if (dataSpec.position != 0L || path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith(".key")) {
            if (buf == null && n > 0) {
                buf = head
                bufPos = 0
                bufEnd = n
            }
            return length
        }

        // Sample recording: only copies bytes already read; claims one of the first two chunk slots.
        val t = try { SampleRecorder.beginChunk(dataSpec.uri.toString()) } catch (_: Throwable) { null }
        tap = t
        if (t != null) sample { t.contentType = contentTypeHeader() }

        // (If the playlist check above already read some bytes, keep them and read on.)
        if (n == 0) head = ByteArray(PEEK)
        fill(PEEK)

        var skip = 0
        val image = imageEnd()
        if (image != null && image > 0) {
            skip = image
            // Usually the video starts right after the picture; if not, look a bit further.
            if (!looksLikeMedia(head, n, skip)) skip = searchFrom(skip) ?: skip
        } else if (!looksLikeMedia(head, n, 0)) {
            skip = searchFrom(1) ?: 0
        }

        // Nothing after the picture: the video may be hidden in the picture's text notes.
        if (image != null && !looksLikeMedia(head, n, skip)) {
            fill(MAX_PEEK)
            val d = PngText.decode(head, n) { bytes, len -> looksLikeMedia(bytes, len, 0) }
            if (d != null) {
                val end = if (d.bytes.isNotEmpty() && d.bytes[0] == SYNC) tsEnd(d.bytes, 0) else d.bytes.size
                Report.logOnce("pngtext", "Decoded video hidden in the picture's text notes " +
                    "(${d.notes} notes, ${d.encoding}, ${end} bytes of video)")
                buf = d.bytes
                bufPos = 0
                bufEnd = end
                bufOnly = true
                sample {
                    t?.decision = "video decoded from the picture's text notes (${d.notes} notes, ${d.encoding}, $end bytes)"
                    t?.received(head, 0, n)
                }
                return C.LENGTH_UNSET.toLong()
            }
        }

        // Still nothing: the video may be hidden in the picture's pixels.
        if (image != null && !looksLikeMedia(head, n, skip)) {
            fill(MAX_PEEK)
            // Try the packing that worked last time first, so most chunks decode exactly once.
            val first = PngPixels.preferDropAlpha
            for (drop in listOf(first, !first)) {
                val d = PngPixels.decodeOne(head, n, drop) ?: continue
                val at = if (looksLikeMedia(d.bytes, d.bytes.size, 0)) 0 else findMedia(d.bytes, d.bytes.size, 0)
                if (at != null) {
                    PngPixels.preferDropAlpha = drop
                    // The picture is a fixed size, so the video is followed by padding. Stop at the
                    // last whole MPEG-TS packet, or the player hits the padding and gives up.
                    val end = tsEnd(d.bytes, at)
                    Report.logOnce("pixels", "Decoded video hidden in the picture's pixels (${d.layout}, " +
                        "${end - at} bytes of video, ${d.bytes.size - end} bytes of padding dropped)")
                    buf = d.bytes
                    bufPos = at
                    bufEnd = end
                    bufOnly = true
                    sample {
                        t?.decision = "video decoded from the picture's pixels (${d.layout}, ${end - at} bytes of video, " +
                            "${d.bytes.size - end} bytes of padding dropped)"
                        t?.received(head, 0, n)
                    }
                    return C.LENGTH_UNSET.toLong()
                }
            }
            Report.logOnce("pixels-fail", "The picture's pixels didn't contain recognisable video either")
            undecodable.incrementAndGet()
        }

        Report.logOnce("chunk-head", describeChunk(dataSpec, head, n, skip))
        if (skip > 0) {
            if (looksLikeMedia(head, n, skip)) {
                Report.logOnce("unwrap", "Unwrapped disguised video chunks ($skip junk bytes removed)")
            } else {
                val hex = (skip until minOf(n, skip + 16)).joinToString(" ") { String.format("%02X", head[it]) }
                Report.logOnce("unwrap-fail", "A disguised chunk had no recognisable video after the picture " +
                    "(${n - skip} bytes left, starting [$hex])")
            }
        }
        buf = head
        bufPos = skip
        bufEnd = n
        sample {
            t?.decision = when {
                skip > 0 && looksLikeMedia(head, n, skip) -> "unwrapped: $skip junk bytes removed from the start"
                skip > 0 -> "unwrap failed: skipped $skip bytes but no recognisable video after them"
                looksLikeMedia(head, n, 0) -> "passed through unchanged (already normal video)"
                else -> "passed through unchanged (format not recognised)"
            }
            t?.received(head, 0, n)
        }
        return if (length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else length - skip
    }

    private var bufOnly = false
    private var head = ByteArray(0)
    private var n = 0
    private var eof = false

    /** Read ahead until [target] bytes are buffered (or the chunk ends). */
    private fun fill(target: Int) {
        val want = minOf(target, MAX_PEEK)
        if (want > head.size) head = head.copyOf(maxOf(want, minOf(head.size * 2, MAX_PEEK)))
        while (n < want && !eof) {
            val r = upstream.read(head, n, want - n)
            if (r == C.RESULT_END_OF_INPUT) eof = true else n += r
        }
    }

    /**
     * If the chunk starts with a real picture, where that picture ends. PNG is walked chunk by
     * chunk to its IEND marker; JPEG to its end-of-image marker; GIF to its trailer.
     */
    private fun imageEnd(): Int? {
        if (n < 8) return null
        // PNG: 89 50 4E 47 0D 0A 1A 0A, then [length][type][data][crc] ... IEND
        if ((head[0].toInt() and 0xFF) == 0x89 && head[1] == 'P'.code.toByte() && head[2] == 'N'.code.toByte() && head[3] == 'G'.code.toByte()) {
            var pos = 8
            while (true) {
                fill(pos + 8)
                if (n < pos + 8) return null
                val len = ((head[pos].toInt() and 0xFF) shl 24) or ((head[pos + 1].toInt() and 0xFF) shl 16) or
                    ((head[pos + 2].toInt() and 0xFF) shl 8) or (head[pos + 3].toInt() and 0xFF)
                if (len < 0 || len > MAX_PEEK) return null
                val type = String(head, pos + 4, 4, Charsets.ISO_8859_1)
                val next = pos + 12 + len
                if (next > MAX_PEEK) return null
                if (type == "IEND") {
                    fill(next + 1024)
                    return minOf(next, n)
                }
                fill(next)
                pos = next
            }
        }
        // JPEG: FF D8 ... FF D9
        if ((head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xFF) == 0xD8) {
            var i = 2
            while (true) {
                if (i + 1 >= n) {
                    if (eof || n >= MAX_PEEK) return null
                    fill(n * 2)
                    continue
                }
                if ((head[i].toInt() and 0xFF) == 0xFF && (head[i + 1].toInt() and 0xFF) == 0xD9) {
                    fill(i + 2 + 1024)
                    return i + 2
                }
                i++
            }
        }
        // GIF: "GIF8" ... trailer 0x3B. Rare; rely on the media search after the header.
        if (head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte() && head[3] == '8'.code.toByte()) {
            return 6
        }
        return null
    }

    /** Search for the start of real video at or after [from], reading up to [MAX_SEARCH] ahead. */
    private fun searchFrom(from: Int): Int? {
        var start = from
        while (true) {
            findMedia(head, n, start)?.let { return it }
            if (eof || n >= MAX_SEARCH) return null
            start = maxOf(from, n - TS * 5)
            fill(minOf(n * 2, MAX_SEARCH))
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val b = buf
        if (b != null) {
            if (bufPos < bufEnd) {
                val count = minOf(length, bufEnd - bufPos)
                System.arraycopy(b, bufPos, buffer, offset, count)
                bufPos += count
                tap?.let { t -> sample { t.delivered(count) } }
                return count
            }
            if (cachedUri != null || bufOnly) {
                tap?.let { t -> t.complete = true }
                return C.RESULT_END_OF_INPUT
            }
        }
        val r = upstream.read(buffer, offset, length)
        val t = tap
        if (t != null) {
            sample {
                if (r > 0) { t.received(buffer, offset, r); t.delivered(r) } else if (r == C.RESULT_END_OF_INPUT) t.complete = true
            }
        }
        return r
    }

    private fun contentTypeHeader(): String = try {
        upstream.responseHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.firstOrNull() ?: "?"
    } catch (_: Throwable) { "?" }

    override fun getUri(): Uri? = cachedUri ?: upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (cachedUri != null) emptyMap() else upstream.responseHeaders

    override fun close() {
        tap = null
        head = ByteArray(0)
        n = 0
        buf = null
        bufPos = 0
        bufEnd = 0
        cachedUri = null
        if (upstreamOpen) {
            upstreamOpen = false
            upstream.close()
        }
    }

    private fun looksLikePlaylist(b: ByteArray, n: Int): Boolean =
        String(b, 0, minOf(n, 64), Charsets.ISO_8859_1).trimStart('\uFEFF', ' ', '\r', '\n', '\t').startsWith("#EXTM3U")

    /**
     * For the report: the playlist's structure, with addresses and tokens replaced, once for the
     * main playlist and once for a video (segment) playlist.
     */
    private fun logPlaylist(b: ByteArray, n: Int) {
        val text = String(b, 0, minOf(n, 64 * 1024), Charsets.UTF_8)
        val isMedia = text.contains("#EXTINF")
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val shown = lines.take(40).map { line ->
            if (line.startsWith("#")) {
                line.replace(Regex("URI=\"[^\"]*\""), "URI=\"…\"").take(140)
            } else {
                if (isMedia) "(segment)" else "(variant playlist)"
            }
        }
        val more = if (lines.size > 40) "\n      … ${lines.size - 40} more lines" else ""
        Report.logOnce(
            if (isMedia) "playlist-media" else "playlist-main",
            (if (isMedia) "Video playlist" else "Main playlist") + ":\n      " + shown.joinToString("\n      ") + more,
        )
    }

    /** One line for the report: what the first video chunk really looks like. */
    private fun describeChunk(spec: DataSpec, head: ByteArray, n: Int, skip: Int): String {
        val type = upstream.responseHeaders.entries
            .firstOrNull { it.key.equals("Content-Type", true) }?.value?.firstOrNull() ?: "?"
        val hex = (0 until minOf(n, 16)).joinToString(" ") { String.format("%02X", head[it]) }
        val text = String(head, 0, minOf(n, 60), Charsets.ISO_8859_1).replace(Regex("[^\\x20-\\x7E]"), ".")
        val guess = when {
            n == 0 -> "empty"
            skip > 0 && looksLikeMedia(head, n, skip) -> "disguised video, real video found at byte $skip"
            skip > 0 -> "disguised, but no video found after the picture"
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
        /**
         * Picture-disguised chunks seen since the player started that could not be unpacked by any
         * known trick. The player switches to the site's own player when this reaches 2.
         */
        val undecodable = java.util.concurrent.atomic.AtomicInteger(0)

        /** Tags some sites write as "#TAG=value" instead of "#TAG:value". */
        private val MISSPELLED_TAG = Regex(
            "^(#EXT-X-(?:MEDIA-SEQUENCE|DISCONTINUITY-SEQUENCE|TARGETDURATION|VERSION|PLAYLIST-TYPE|PROGRAM-DATE-TIME))\\s*=\\s*",
            RegexOption.MULTILINE,
        )

        /**
         * Corrects playlist typos Android's player can't read but browsers tolerate. The one that
         * matters most: "#EXT-X-MEDIA-SEQUENCE=123" (should be ":"), without which the player
         * thinks a live stream never moves forward and never starts playing.
         */
        fun fixPlaylist(bytes: ByteArray): ByteArray {
            val text = String(bytes, Charsets.UTF_8)
            if (!text.contains("#EXT-X-") || !MISSPELLED_TAG.containsMatchIn(text)) return bytes
            val fixed = MISSPELLED_TAG.replace(text) { m -> m.groupValues[1] + ":" }
            val tags = MISSPELLED_TAG.findAll(text).map { it.groupValues[1] }.distinct().joinToString(", ")
            Report.logOnce("playlist-fix-$tags", "Fixed malformed playlist tag(s) the player can't read: $tags (\"=\" instead of \":\")")
            return fixed.toByteArray(Charsets.UTF_8)
        }

        private const val PEEK = 64 * 1024
        private const val MAX_SEARCH = 1024 * 1024
        private const val MAX_PEEK = 8 * 1024 * 1024
        private const val SYNC = 0x47.toByte()
        private const val TS = 188

        /**
         * End of the run of MPEG-TS packets starting at [at] (each 188 bytes, starting 0x47).
         * For anything that isn't MPEG-TS, the whole buffer.
         */
        fun tsEnd(b: ByteArray, at: Int): Int {
            if (at >= b.size || b[at] != SYNC) return b.size
            var end = at
            while (end + TS <= b.size && b[end] == SYNC) end += TS
            return end
        }

        /** Real video starts at [at]: MPEG-TS packets, an MP4 box, or an ID3 tag. */
        fun looksLikeMedia(b: ByteArray, n: Int, at: Int): Boolean {
            if (at < 0 || at + 8 > n) return false
            if (b[at] == SYNC && (n <= at + TS || b[at + TS] == SYNC)) return true
            if (isBox(b, at + 4)) return true
            return b[at] == 'I'.code.toByte() && b[at + 1] == 'D'.code.toByte() && b[at + 2] == '3'.code.toByte()
        }

        /** First offset >= [from] where MPEG-TS (5 packets in a row) or an MP4 box begins. */
        fun findMedia(b: ByteArray, n: Int, from: Int): Int? {
            var i = maxOf(from, 0)
            while (i + TS * 4 < n) {
                if (b[i] == SYNC && b[i + TS] == SYNC && b[i + TS * 2] == SYNC &&
                    b[i + TS * 3] == SYNC && b[i + TS * 4] == SYNC
                ) return i
                i++
            }
            i = maxOf(from + 4, 4)
            while (i + 4 <= n) {
                if (isBox(b, i, listOf("ftyp", "styp", "moof"))) return i - 4
                i++
            }
            return null
        }

        private fun isBox(b: ByteArray, at: Int, types: List<String> = listOf("ftyp", "styp", "moof", "sidx", "emsg")): Boolean {
            if (at + 4 > b.size) return false
            val t = String(b, at, 4, Charsets.ISO_8859_1)
            return t in types
        }
    }
}
