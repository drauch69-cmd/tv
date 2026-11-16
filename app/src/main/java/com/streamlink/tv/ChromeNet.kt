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

    /** Start the engine in the background; call once at app start. */
    fun init(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        thread(isDaemon = true, name = "cronet-init") {
            engine = try {
                CronetUtil.buildCronetEngine(app, null, true)
            } catch (_: Throwable) {
                null
            }
        }
    }

    fun describe(): String =
        if (engine != null) "Chrome's network engine (Cronet)" else "Android's standard networking (Chrome's engine unavailable)"

    /** An HTTP connection through Chrome's engine when available. */
    fun open(url: String): HttpURLConnection =
        (engine?.openConnection(URL(url)) ?: URL(url).openConnection()) as HttpURLConnection
}
