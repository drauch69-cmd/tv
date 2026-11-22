package com.streamlink.tv

/**
 * Lab step 1b (2026-11-21): one fixed rescue order for the player. The failure name ([Failure]) picks the first steps
 * that fit it; every failure then falls through the same tail. The player tries the steps in order and runs the first
 * one that applies right now (a step that doesn't apply, e.g. "ready backup" with none ready, is skipped), logging
 * "Rescue: <step>". This replaces the separate rescue routines that each decided on their own. Pure, unit tested.
 */
enum class RescueStep(val text: String) {
    JUMP_TO_LIVE("jump back to live"),
    NO_TUNNEL("play again without tunneling"),
    REASK_MAIN("ask the main playlist for another server"),
    PIECE_ENGINE("use Chrome's network engine for that server's pieces"),
    CHROME_ENGINE("go back to Chrome's network engine"),
    RENEW_LINK("get a fresh link without leaving the player"),
    NEXT_REFERER("try the next referer"),
    REFUSED_PIECES("this stream's pieces are refused; move on"),
    ENGINE_RESTART("restart Chrome's network engine"),
    PIECE_FIX("fix the failing video pieces"),
    NEXT_SOURCE("next source on this page"),
    READY_BACKUP("switch to a ready backup"),
    RELOAD_PAGE("reload the page"),
    OTHER_SITE("another site's stream of this game"),
    LAST_RESORT("browser player"),
}

object Rescue {
    /** What every failure falls through to, in this order. */
    val TAIL = listOf(
        RescueStep.ENGINE_RESTART, RescueStep.PIECE_FIX,
        RescueStep.NEXT_SOURCE, RescueStep.READY_BACKUP, RescueStep.RELOAD_PAGE, RescueStep.OTHER_SITE, RescueStep.LAST_RESORT,
    )

    /** The tail for a stall, a stopped live stream or a gone playlist (no player error to look at). */
    val AFTER_STALL = TAIL.drop(2)

    /** The page was just searched (an empty quiet renewal): reloading it again is the last thing to try. */
    val AFTER_EMPTY_RENEWAL = listOf(RescueStep.READY_BACKUP, RescueStep.OTHER_SITE, RescueStep.RELOAD_PAGE, RescueStep.LAST_RESORT)

    /** The order for a player error named [f]. [behindLiveAgain]: the 2nd fall behind live within 2 minutes while playing. */
    fun order(f: Failure, behindLiveAgain: Boolean = false): List<RescueStep> = when (f) {
        Failure.BEHIND_LIVE ->
            if (behindLiveAgain) listOf(RescueStep.READY_BACKUP, RescueStep.OTHER_SITE, RescueStep.JUMP_TO_LIVE)
            else listOf(RescueStep.JUMP_TO_LIVE)
        Failure.TUNNEL_AUDIO -> listOf(RescueStep.NO_TUNNEL)
        Failure.BROKEN_SECURE -> listOf(RescueStep.REASK_MAIN) + TAIL
        Failure.REFUSED, Failure.EXPIRED_LINK -> listOf(
            RescueStep.PIECE_ENGINE, RescueStep.CHROME_ENGINE, RescueStep.RENEW_LINK, RescueStep.NEXT_REFERER, RescueStep.REFUSED_PIECES,
        ) + TAIL
        else -> TAIL
    }

    /** Lab: quick re-asks of the main playlist before a page reload (was 2; each costs ~0.2 s, a reload ~9 s). */
    const val MAX_MAIN_REFETCHES = 4
}
