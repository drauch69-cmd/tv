package com.streamlink.tv

import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * The player's retry rule: as Media3's default, except that a broken secure connection (see [DeadHostText.brokenSecureLink])
 * is not retried. The server can't speak TLS to us, so the default 3 retries over ~3 s only delay the recovery; the error
 * reaches the player at once and it moves on (see PlayerActivity.handleError).
 */
class NoRetryOnBrokenTls : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (DeadHostText.brokenSecureLink(loadErrorInfo.exception)) C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)
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
}
