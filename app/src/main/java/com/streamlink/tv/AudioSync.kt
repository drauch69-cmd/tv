package com.streamlink.tv

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * Lip-sync adjustment, kept per site: some re-streams mux in sound from another feed that runs
 * seconds ahead of (or behind) the picture, and a speaker can add a little delay too. Positive =
 * sound comes AFTER the picture: the picture is held back. Negative = sound comes BEFORE the picture:
 * the picture is shown that much further along (the player keeps that much extra video buffered).
 * Works by shifting the audio clock the video follows. (No effect in tunneled playback.)
 */
object AudioSync {
    const val STEP_MS = 50
    const val BIG_STEP_MS = 1000
    const val MAX_MS = 30_000
    private const val KEY = "audio_sync_sites"

    /** Read on every clock query by the audio sink; changed live from the TV or phone. */
    @Volatile var offsetMs = 0
        private set

    @Volatile private var host = ""

    /** The adjustment saved for [siteHost] (the page's site), 0 if none. */
    fun load(ctx: Context, siteHost: String) {
        host = siteHost.removePrefix("www.")
        offsetMs = try {
            clamp(org.json.JSONObject(prefs(ctx).getString(KEY, "{}") ?: "{}").optInt(host, 0))
        } catch (_: Exception) { 0 }
        Session.audioSyncMs = offsetMs
    }

    /** Sets and saves the adjustment for the current site. */
    fun set(ctx: Context, ms: Int) {
        offsetMs = clamp(ms)
        Session.audioSyncMs = offsetMs
        if (host.isEmpty()) return
        val o = try { org.json.JSONObject(prefs(ctx).getString(KEY, "{}") ?: "{}") } catch (_: Exception) { org.json.JSONObject() }
        if (offsetMs == 0) o.remove(host) else o.put(host, offsetMs)
        prefs(ctx).edit().putString(KEY, o.toString()).apply()
    }

    /** Extra video to keep buffered so the picture can run ahead to sound that is early. */
    fun extraBufferMs(ms: Int): Int = if (ms < 0) -ms else 0

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("streamlink", Context.MODE_PRIVATE)

    internal fun clamp(ms: Int): Int = ms.coerceIn(-MAX_MS, MAX_MS) / STEP_MS * STEP_MS

    /** "in sync", "picture waits 150 ms for the sound", "sound waits 100 ms for the picture". */
    fun describe(ms: Int): String = when {
        ms > 0 -> "picture waits ${amount(ms)} for the sound"
        ms < 0 -> "sound waits ${amount(-ms)} for the picture"
        else -> "no adjustment"
    }

    /** "150 ms", "17 s", "17.3 s". */
    internal fun amount(ms: Int): String = when {
        ms < 1000 -> "$ms ms"
        ms % 1000 == 0 -> "${ms / 1000} s"
        else -> String.format(java.util.Locale.US, "%.2f", ms / 1000.0).trimEnd('0').trimEnd('.') + " s"
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
