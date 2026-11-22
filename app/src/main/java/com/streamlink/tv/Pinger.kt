package com.streamlink.tv

import android.os.Handler
import android.os.Looper
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Times a quick connection (TCP handshake, no data sent) to a fast public server every [EVERY_MS] while the player is
 * open, and hands each result to [onResult] on the main thread: ms, or null when neither server answered. Apps can't
 * send a true ping without root; a handshake measures the same round trip. Costs a few KB per hour.
 */
class Pinger(private val onResult: (Long?) -> Unit) {
    companion object {
        const val EVERY_MS = 3_000L
        const val TIMEOUT_MS = 2_000
        /** Cloudflare, then Google if the first doesn't answer (some networks block one). IP literals: no DNS lookup. */
        private val SERVERS = listOf("1.1.1.1", "8.8.8.8")

        /** One quick check, on the calling (background) thread: does either server answer at all? */
        fun reachable(timeoutMs: Int = 1_500): Boolean = SERVERS.any { probe(it, timeoutMs) != null }

        private fun probe(host: String, timeoutMs: Int): Long? = try {
            Socket().use { s ->
                val t0 = System.nanoTime()
                s.connect(InetSocketAddress(host, 443), timeoutMs)
                (System.nanoTime() - t0) / 1_000_000
            }
        } catch (_: Exception) { null }
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var thread: Thread? = null

    fun start() {
        if (thread != null) return
        val t = Thread({
            while (thread === Thread.currentThread()) {
                val ms = SERVERS.firstNotNullOfOrNull { once(it) }
                if (thread !== Thread.currentThread()) break
                main.post { onResult(ms) }
                try { Thread.sleep(EVERY_MS) } catch (_: InterruptedException) { break }
            }
        }, "pinger")
        t.isDaemon = true
        thread = t
        t.start()
    }

    fun stop() {
        val t = thread ?: return
        thread = null
        t.interrupt()
    }

    private fun once(host: String): Long? = probe(host, TIMEOUT_MS)
}
