package com.streamlink.tv

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * Lip-sync adjustment, for sound that reaches the ears late (a Bluetooth speaker adds 0.1-0.3 s the
 * TV doesn't always account for) or early. Positive = sound comes AFTER the picture: the picture is
 * held back by that much. Works by shifting the audio clock the video follows, so it applies
 * instantly. (In tunneled playback the video chip syncs by itself and this has no effect.)
 */
object AudioSync {
    const val STEP_MS = 50
    const val MAX_MS = 1000
    private const val KEY = "audio_sync_ms"

    /** Read on every clock query by the audio sink; changed live from the TV or phone. */
    @Volatile var offsetMs = 0
        private set

    fun load(ctx: Context) {
        offsetMs = clamp(ctx.getSharedPreferences("streamlink", Context.MODE_PRIVATE).getInt(KEY, 0))
        Session.audioSyncMs = offsetMs
    }

    fun set(ctx: Context, ms: Int) {
        offsetMs = clamp(ms)
        Session.audioSyncMs = offsetMs
        ctx.getSharedPreferences("streamlink", Context.MODE_PRIVATE).edit().putInt(KEY, offsetMs).apply()
    }

    internal fun clamp(ms: Int): Int = ms.coerceIn(-MAX_MS, MAX_MS) / STEP_MS * STEP_MS

    /** "in sync", "picture waits 150 ms for the sound", "sound waits 100 ms for the picture". */
    fun describe(ms: Int): String = when {
        ms > 0 -> "picture waits $ms ms for the sound"
        ms < 0 -> "sound waits ${-ms} ms for the picture"
        else -> "no adjustment"
    }

    /** The clock the video follows = where the audio really is, minus the offset. Pure; unit tested. */
    internal fun shift(positionUs: Long, offsetMs: Int): Long =
        if (positionUs == AudioSink.CURRENT_POSITION_NOT_SET) positionUs else positionUs - offsetMs * 1000L

    @OptIn(UnstableApi::class)
    class Sink(sink: AudioSink) : ForwardingAudioSink(sink) {
        override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
            shift(super.getCurrentPositionUs(sourceEnded), offsetMs)
    }
}
