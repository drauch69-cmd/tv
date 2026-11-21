package com.streamlink.tv

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * What the player's decoders and buffer say (moved out of [PlayerActivity] unchanged, 2026-11-20 cleanup). Readings
 * and verdicts only: the player decides what to do about them (tunneling off, software decoder, smooth delay...).
 */
@OptIn(UnstableApi::class)
class PlaybackSigns {
    private val emptyStallTimes = ArrayList<Long>()
    private val bufferedStallTimes = ArrayList<Long>()
    private val starveSamples = ArrayList<LongArray>()

    fun resetLowDelay() = emptyStallTimes.clear()
    fun resetStarve() = starveSamples.clear()

    /**
     * For the report: per decoder, how many frames came in, how many were shown/played, how many
     * were dropped as too late. "Received but none shown, many dropped late" = a timing problem.
     */
    fun decoderStats(player: ExoPlayer?): String {
        val p = player ?: return "Decoders: no player"
        val v = p.videoDecoderCounters
        val a = p.audioDecoderCounters
        v?.ensureUpdated()
        a?.ensureUpdated()
        val vs = if (v == null) "video decoder never started" else
            "video frames received ${v.queuedInputBufferCount}, shown ${v.renderedOutputBufferCount}, " +
                "dropped late ${v.droppedBufferCount}, skipped ${v.skippedOutputBufferCount + v.skippedInputBufferCount}"
        val aus = if (a == null) "audio decoder never started" else
            "audio frames received ${a.queuedInputBufferCount}, played ${a.renderedOutputBufferCount}, skipped ${a.skippedInputBufferCount}"
        return String.format(java.util.Locale.US, "Decoders: %s; %s; position %.1fs, buffered to %.1fs",
            vs, aus, p.currentPosition / 1000.0, p.bufferedPosition / 1000.0)
    }

    /** Frames go into the video decoder but no picture ever comes out. */
    fun decoderStuck(player: ExoPlayer?): Boolean {
        val v = player?.videoDecoderCounters ?: return false
        v.ensureUpdated()
        return v.queuedInputBufferCount >= 5 && v.renderedOutputBufferCount == 0
    }

    /**
     * Tunneled playback that keeps "buffering" with plenty of video buffered: on this box the audio clock
     * stalls (audio frames stop arriving while video keeps coming) and the picture waits for it.
     * Two such stalls within 30 s, each with >= 6 s buffered: returns the seconds buffered (it's tunneling, not the
     * network); else null.
     */
    fun bufferedStallRepeat(p: ExoPlayer, now: Long): Double? {
        val ahead = (p.bufferedPosition - p.currentPosition) / 1000.0
        if (ahead < 6.0) return null
        bufferedStallTimes.add(now)
        bufferedStallTimes.removeAll { now - it > 30_000 }
        if (bufferedStallTimes.size >= 2) {
            bufferedStallTimes.clear()
            return ahead
        }
        return null
    }

    /**
     * Tunneled playback's failure on this box, caught early: video keeps arriving but the audio stops
     * (and the picture then waits for the frozen audio clock). Sampled every 2 s; over the last 4 s,
     * 40+ new video frames and at most 2 audio frames means tunneling, not the stream.
     */
    fun audioStarved(p: ExoPlayer, now: Long): Boolean {
        val v = p.videoDecoderCounters
        val a = p.audioDecoderCounters
        if (v != null && a != null && p.playWhenReady) {
            v.ensureUpdated(); a.ensureUpdated()
            starveSamples.add(longArrayOf(now, v.queuedInputBufferCount.toLong(), a.queuedInputBufferCount.toLong()))
            starveSamples.removeAll { now - it[0] > 4_500 }
            val first = starveSamples.first()
            if (now - first[0] >= 3_500 && starveSamples.last()[1] - first[1] >= 40 && starveSamples.last()[2] - first[2] <= 2 &&
                p.isCurrentMediaItemLive
            ) {
                starveSamples.clear()
                return true
            }
        }
        return false
    }

    /** Low delay running dry: the buffer ran empty (1 s or less) twice within a minute. */
    fun lowDelayRanDry(p: ExoPlayer, now: Long): Boolean {
        if ((p.bufferedPosition - p.currentPosition) > 1_000) return false
        emptyStallTimes.add(now)
        emptyStallTimes.removeAll { now - it > 60_000 }
        if (emptyStallTimes.size >= 2) {
            emptyStallTimes.clear()
            return true
        }
        return false
    }
}
