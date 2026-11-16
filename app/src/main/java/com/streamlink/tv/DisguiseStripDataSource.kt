package com.streamlink.tv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Some stream sites disguise their video pieces as images (a fake PNG/JPEG header in front of
 * the real MPEG-TS video) so they're harder to grab. The player can't read those, so this looks
 * at the start of each piece and, if the real video starts a little later, skips the fake part.
 * Anything that isn't disguised passes through untouched.
 */
@UnstableApi
class DisguiseStripDataSource(private val upstream: DataSource) : DataSource {

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = DisguiseStripDataSource(upstream.createDataSource())
    }

    private var head: ByteArray? = null
    private var headPos = 0
    private var headLen = 0
    private var upstreamEnded = false

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        head = null
        upstreamEnded = false
        val length = upstream.open(dataSpec)
        val path = dataSpec.uri.path?.lowercase() ?: ""
        val isPlaylist = path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith(".m3u")
        if (isPlaylist || dataSpec.position != 0L) return length

        // Peek at the start of the piece.
        val buf = ByteArray(PEEK)
        var n = 0
        while (n < buf.size) {
            val r = upstream.read(buf, n, buf.size - n)
            if (r == C.RESULT_END_OF_INPUT) { upstreamEnded = true; break }
            n += r
        }
        val start = tsStart(buf, n)
        head = buf
        headPos = start
        headLen = n
        return if (start > 0 || length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val h = head
        if (h != null && headPos < headLen) {
            val count = minOf(length, headLen - headPos)
            System.arraycopy(h, headPos, buffer, offset, count)
            headPos += count
            return count
        }
        if (upstreamEnded) return C.RESULT_END_OF_INPUT
        return upstream.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        head = null
        upstream.close()
    }

    companion object {
        private const val PEEK = 64 * 1024
        private const val TS_PACKET = 188
        private const val SYNC: Byte = 0x47

        /** Where real MPEG-TS video starts in [buf], or 0 if it already starts at 0 or isn't TS at all. */
        fun tsStart(buf: ByteArray, n: Int): Int {
            if (n > 0 && buf[0] == SYNC && (n <= TS_PACKET || buf[TS_PACKET] == SYNC)) return 0
            val limit = n - TS_PACKET * 3
            for (i in 0 until limit) {
                if (buf[i] == SYNC && buf[i + TS_PACKET] == SYNC &&
                    buf[i + TS_PACKET * 2] == SYNC && buf[i + TS_PACKET * 3] == SYNC
                ) return i
            }
            return 0
        }
    }
}
