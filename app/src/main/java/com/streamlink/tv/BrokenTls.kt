package com.streamlink.tv

import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * The player's retry rule: as Media3's default, except that a broken secure connection (see [DeadHostText.brokenSecureLink])
 * is not retried. The server can't speak TLS to us, so the default 3 retries over ~3 s only delay the recovery; the error
 * reaches the player at once and it moves on (see PlayerActivity.handleError). While [DeadHosts.ENFORCE] is off, the
 * default retries apply to every error.
 */
class NoRetryOnBrokenTls : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (DeadHosts.ENFORCE && DeadHostText.brokenSecureLink(loadErrorInfo.exception)) C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)
}

object BrokenTls {
    /** The host of the request that failed, from the first network exception in the cause chain. */
    fun failingHost(t: Throwable?): String? {
        var e = t
        var depth = 0
        while (e != null && depth < 10) {
            if (e is HttpDataSource.HttpDataSourceException) return e.dataSpec.uri.host
            e = e.cause
            depth++
        }
        return null
    }

    /** Quick re-fetches of the main playlist per play, before the usual page reload. */
    const val MAX_MAIN_REFETCHES = 2

    /**
     * A server named inside the main playlist (an inner playlist or video host) has a broken secure connection, while the
     * main playlist's own server works: ask the main playlist again, since it often names a different server each time
     * (2026-11-21 report: pl.playlist4 / pl.playlist3 broken, while the same main playlist also handed out red.malek5,
     * pl.playlist5, red.halombarat5…). A page reload cost ~15 s per try. Pure.
     */
    fun refetchMain(failHost: String?, mainHost: String?, tries: Int): Boolean =
        failHost != null && mainHost != null && !failHost.equals(mainHost, true) && tries < MAX_MAIN_REFETCHES
}
