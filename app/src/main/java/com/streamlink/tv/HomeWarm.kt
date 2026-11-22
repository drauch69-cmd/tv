package com.streamlink.tv

/**
 * Keeping home current while a stream plays (user, 2026-11-21): home is paused under the player, but it still
 * refreshes scores about once a minute and redoes its rows (low priority, drawn only when they changed, plus a forced
 * redo every few minutes for clock text like "starts in"), so Back shows current rows at once. No list re-reads while
 * playing: those use the hidden browser and could make playback stutter on the Onn.
 */
object HomeWarm {
    const val SCORE_MS = 60_000L
    const val FORCE_MS = 5 * 60_000L

    /** A stream is actually playing (not being found, not waiting): the light background work is allowed. */
    fun playing(mode: String?): Boolean = mode == "player" || mode == "clean" || mode == "site"

    /** What the paused home should do on a tick, given when it last did each. */
    fun due(mode: String?, now: Long, lastScores: Long, lastForce: Long): Due {
        if (!playing(mode)) return Due.NONE
        return when {
            now - lastForce >= FORCE_MS -> Due.SCORES_AND_FORCE
            now - lastScores >= SCORE_MS -> Due.SCORES
            else -> Due.NONE
        }
    }

    enum class Due { NONE, SCORES, SCORES_AND_FORCE }
}
