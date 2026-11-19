package com.streamlink.tv

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cronet.CronetUtil
import org.chromium.net.CronetEngine
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Chrome's own network engine (Cronet, provided by Google Play services on the TV).
 *
 * Some stream servers sit behind bot protection that refuses (403) anything that doesn't connect
 * like a real browser. Fetching through Chrome's engine makes the app's requests look like the
 * browser's. If it isn't available, everything falls back to Android's standard networking.
 */
@OptIn(UnstableApi::class)
object ChromeNet {
    @Volatile var engine: CronetEngine? = null
        private set

    val executor: ExecutorService = Executors.newFixedThreadPool(4)

    @Volatile private var started = false
    @Volatile private var app: Context? = null
    @Volatile private var lastResetAt = 0L
    /** Whether the current engine was built by [reset] (QUIC off); for the report. */
    @Volatile var quicOff = false
        private set

    private const val RESET_GAP_MS = 60_000L

    /** Start the engine in the background; call once at app start. */
    fun init(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        this.app = app
        thread(isDaemon = true, name = "cronet-init") {
            engine = try {
                CronetUtil.buildCronetEngine(app, null, true)
            } catch (_: Throwable) {
                null
            }
        }
    }

    /** A restart is allowed (engine present, and none in the last minute). */
    fun canReset(now: Long = System.currentTimeMillis()): Boolean = engine != null && app != null && now - lastResetAt >= RESET_GAP_MS

    /**
     * Replaces the shared engine with a fresh one (a dead pooled connection to one host can wedge every request to it
     * until the app restarts). Everything reads [engine] per request, so new requests use the fresh one at once; the old
     * one is shut down a minute later (requests still running on it finish or fail first; if it refuses, it is dropped).
     * The fresh engine is built with QUIC off (HTTP/2 over TCP only). Blocking: call off the main thread.
     * Returns a short description of what was built, or null when nothing changed.
     */
    fun reset(): String? {
        val ctx = app ?: return null
        val old = engine ?: return null
        lastResetAt = System.currentTimeMillis()
        var how = "provider builder, QUIC off"
        var fresh: CronetEngine? = try {
            val provider = org.chromium.net.CronetProvider.getAllProviders(ctx)
                .filter { it.isEnabled }
                .let { l -> l.firstOrNull { it.name.contains("Play-Services", true) } ?: l.firstOrNull() }
            provider?.createBuilder()?.enableQuic(false)?.enableHttp2(true)?.build()
        } catch (_: Throwable) { null }
        if (fresh == null) {
            how = "default builder (QUIC setting not changed)"
            fresh = try { CronetUtil.buildCronetEngine(ctx, null, true) } catch (_: Throwable) { null }
        }
        if (fresh == null) return null
        quicOff = how.contains("QUIC off")
        engine = fresh
        thread(isDaemon = true, name = "cronet-old") {
            try {
                Thread.sleep(RESET_GAP_MS)
                old.shutdown()
            } catch (_: Throwable) {}
        }
        return how
    }

    fun describe(): String =
        if (engine != null) "Chrome's network engine (Cronet)" else "Android's standard networking (Chrome's engine unavailable)"

    /** An HTTP connection through Chrome's engine when available. */
    fun open(url: String): HttpURLConnection =
        (engine?.openConnection(URL(url)) ?: URL(url).openConnection()) as HttpURLConnection
}
