package com.streamlink.tv

import android.webkit.WebResourceResponse
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cronet.CronetDataSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Fetches what the clean page asks for (playlists, chunks, keys) natively, exactly the way the
 * built-in player would: Chrome's network engine when available, else standard networking, the
 * source's headers, and [UnwrapDataSourceFactory] on top so disguised chunks and playlist fixes
 * still apply. Always returns a response (error statuses instead of exceptions, so hls.js retries).
 */
@OptIn(UnstableApi::class)
class CleanFetcher(private val src: Source) {

    class Result(val response: WebResourceResponse, val status: Int, val bytes: Int, val millis: Long, val error: String?)

    private val upstream = CleanProxy.upstream(src)

    private class Fetched(val status: Int, val body: ByteArray, val contentType: String?)

    private fun dataSource(useChrome: Boolean): DataSource {
        val engine = if (useChrome) ChromeNet.engine else null
        val http: HttpDataSource.Factory = if (engine != null) {
            CronetDataSource.Factory(engine, ChromeNet.executor)
                .setConnectionTimeoutMs(15_000)
                .setReadTimeoutMs(15_000)
                .setDefaultRequestProperties(upstream.headers)
                .apply { upstream.userAgent?.let { setUserAgent(it) } }
        } else {
            DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(15_000)
                .setDefaultRequestProperties(upstream.headers)
                .apply { upstream.userAgent?.let { setUserAgent(it) } }
        }
        return UnwrapDataSourceFactory(http).createDataSource()
    }

    private fun attempt(url: String, range: Pair<Long, Long?>?, useChrome: Boolean): Fetched {
        val ds = dataSource(useChrome)
        try {
            val start = range?.first ?: 0L
            val length = if (range?.second != null) range.second!! - start + 1 else C.LENGTH_UNSET.toLong()
            val spec = DataSpec.Builder().setUri(url).setPosition(start).setLength(length).build()
            ds.open(spec)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = ds.read(buf, 0, buf.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buf, 0, n)
                if (out.size() > CleanProxy.MAX_BODY_BYTES) throw IOException("response too large")
            }
            val type = ds.responseHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.firstOrNull()
            return Fetched(if (range != null) 206 else 200, out.toByteArray(), type)
        } finally {
            try { ds.close() } catch (_: Exception) {}
        }
    }

    private fun error(status: Int, cors: MutableMap<String, String>): WebResourceResponse {
        val code = CleanProxy.safeStatus(status)
        return WebResourceResponse("text/plain", "utf-8", code, CleanProxy.reason(code), cors, ByteArrayInputStream(ByteArray(0)))
    }

    /** [rangeHeader]: the page's Range header, if any. [headOnly]: answer without a body. */
    fun fetch(url: String, rangeHeader: String?, headOnly: Boolean): Result {
        val t0 = System.currentTimeMillis()
        val cors = CleanProxy.corsHeaders()
        val range = CleanProxy.parseRange(rangeHeader)
        val usedChrome = ChromeNet.engine != null
        var fetched: Fetched? = null
        var err: Throwable? = null
        try {
            fetched = try {
                attempt(url, range, usedChrome)
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                throw e
            } catch (e: IOException) {
                // Chrome's engine failed to connect at all: try plain Android networking once.
                if (usedChrome) attempt(url, range, false) else throw e
            }
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            val ms = System.currentTimeMillis() - t0
            return Result(error(e.responseCode, cors), CleanProxy.safeStatus(e.responseCode), 0, ms, "server said ${e.responseCode}")
        } catch (e: Throwable) {
            err = e
        }
        val ms = System.currentTimeMillis() - t0
        if (fetched == null) {
            val msg = (err?.message ?: err?.javaClass?.simpleName ?: "failed").take(120)
            return Result(error(502, cors), 502, 0, ms, msg)
        }

        val body = fetched.body
        val mime = CleanProxy.mimeFor(url, fetched.contentType, body.copyOf(minOf(body.size, 512)))
        if (mime == "application/vnd.apple.mpegurl" || mime == "application/dash+xml") cors["Cache-Control"] = "no-store"
        cors["Content-Length"] = (if (headOnly) 0 else body.size).toString()
        if (range != null) {
            val end = range.first + body.size - 1
            cors["Content-Range"] = "bytes ${range.first}-${maxOf(end, range.first)}/*"
        }
        val encoding = if (mime.startsWith("text/") || mime.contains("mpegurl") || mime.contains("xml")) "utf-8" else null
        val data = ByteArrayInputStream(if (headOnly) ByteArray(0) else body)
        val resp = WebResourceResponse(mime, encoding, fetched.status, CleanProxy.reason(fetched.status), cors, data)
        return Result(resp, fetched.status, body.size, ms, null)
    }
}
