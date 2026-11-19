package com.streamlink.tv

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cronet.CronetDataSource
import java.io.IOException

/**
 * The player's downloader, split by where a request goes: playlist-host requests keep the captured headers;
 * requests to other hosts (video pieces from a CDN) get only the headers a browser would send cross-site
 * ([NetHeaders.forOtherHost]); each host uses the engine [NetRoute] says; pieces get a longer read timeout.
 * Reports every request's outcome to [NetRoute.watch] and writes one short line per failed piece.
 */
@OptIn(UnstableApi::class)
class SplitDataSourceFactory(
    private val route: NetRoute,
    private val headers: Map<String, String>,
    private val userAgent: String?,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = SplitDataSource(route, headers, userAgent)
}

@OptIn(UnstableApi::class)
class SplitDataSource(
    private val route: NetRoute,
    private val headers: Map<String, String>,
    private val userAgent: String?,
) : DataSource {

    private data class Key(val chrome: Boolean, val sameHost: Boolean, val piece: Boolean)

    private val sources = HashMap<Key, DataSource>()
    private val listeners = ArrayList<TransferListener>()
    private var current: DataSource? = null

    // Per request state, for the failure line.
    private var host = ""
    private var piece = false
    private var chrome = false
    private var startedAt = 0L
    private var firstByteAt = 0L
    private var bytes = 0L
    private var failed = false
    private var finished = false

    override fun addTransferListener(transferListener: TransferListener) {
        listeners.add(transferListener)
        sources.values.forEach { it.addTransferListener(transferListener) }
    }

    private fun build(k: Key): DataSource {
        val h = if (k.sameHost) headers else NetHeaders.forOtherHost(headers)
        val connect = if (k.piece) NetHeaders.PIECE_CONNECT_MS else NetHeaders.PLAYLIST_CONNECT_MS
        val read = if (k.piece) NetHeaders.PIECE_READ_MS else NetHeaders.PLAYLIST_READ_MS
        val engine = ChromeNet.engine
        val f: HttpDataSource.Factory = if (k.chrome && engine != null) {
            CronetDataSource.Factory(engine, ChromeNet.executor)
                .setConnectionTimeoutMs(connect)
                .setReadTimeoutMs(read)
                .setDefaultRequestProperties(h)
                .apply { userAgent?.let { setUserAgent(it) } }
        } else {
            DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(connect)
                .setReadTimeoutMs(read)
                .setDefaultRequestProperties(h)
                .apply { userAgent?.let { setUserAgent(it) } }
        }
        return f.createDataSource().also { s -> listeners.forEach { s.addTransferListener(it) } }
    }

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        host = ServerMemory.key(uri.host) ?: ""
        piece = !NetHeaders.isPlaylist(uri.toString(), route.sourceUrl)
        chrome = route.usesChrome(host) && ChromeNet.engine != null
        startedAt = System.currentTimeMillis()
        firstByteAt = 0L
        bytes = 0L
        failed = false
        finished = false
        val k = Key(chrome, route.isPlaylistHost(host), piece)
        val src = sources.getOrPut(k) { build(k) }
        current = src
        try {
            return src.open(dataSpec)
        } catch (e: IOException) {
            fail(e)
            throw e
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val src = current ?: return C.RESULT_END_OF_INPUT
        val r = try {
            src.read(buffer, offset, length)
        } catch (e: IOException) {
            fail(e)
            throw e
        }
        if (r > 0) {
            val now = System.currentTimeMillis()
            if (firstByteAt == 0L) firstByteAt = now
            bytes += r
            if (piece) route.watch.pieceBytes(now, r)
        } else if (r == C.RESULT_END_OF_INPUT) {
            succeed()
        }
        return r
    }

    private fun succeed() {
        if (finished || failed) return
        finished = true
        if (piece) route.watch.pieceOk(host) else route.watch.playlistOk(System.currentTimeMillis())
    }

    private fun fail(e: IOException) {
        if (failed) return
        failed = true
        val now = System.currentTimeMillis()
        if (!piece) {
            route.watch.playlistFailed(now)
            return
        }
        route.watch.pieceFailed(host, startedAt)
        if (!route.watch.takeDiag()) return
        val status = (e as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        val engine = if (chrome) "Chrome's engine" else "standard networking"
        val timing = if (bytes == 0L) "no bytes after %.1fs".format(java.util.Locale.US, (now - startedAt) / 1000.0)
        else "%,d bytes, first after %.1fs, failed at %.1fs".format(
            java.util.Locale.US, bytes, (firstByteAt - startedAt) / 1000.0, (now - startedAt) / 1000.0)
        Report.log(
            "  Piece from $host failed ($engine): $timing" +
                (if (status != null) ", server said $status" else "") + ", " + e.javaClass.simpleName
        )
    }

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        val c = current ?: return
        // Closed with data received and no error: it worked (the reader may stop before end of input).
        if (!failed && bytes > 0) succeed()
        current = null
        c.close()
    }
}
