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
    RELOAD_ONCE("reload the page once"),
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

    /** Video pieces keep failing while the playlist is fine: reloading the page would hand back the same piece server. */
    val PIECES_FAILING = listOf(RescueStep.READY_BACKUP, RescueStep.OTHER_SITE, RescueStep.NEXT_SOURCE, RescueStep.RELOAD_PAGE, RescueStep.LAST_RESORT)

    /**
     * The pieces were refused (401/403/451) before the picture: another source of the page, another site, one reload only
     * when the finder hadn't already seen that host refuse ([corroborated]), else the site's player (replaces Refusal.afterRefusal).
     */
    fun afterRefusedPieces(corroborated: Boolean): List<RescueStep> =
        listOf(RescueStep.NEXT_SOURCE, RescueStep.OTHER_SITE) + (if (corroborated) emptyList() else listOf(RescueStep.RELOAD_ONCE)) +
            RescueStep.LAST_RESORT

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

    /**
     * Wait before re-ask number [tryNo] (1-based): 1 s, 2 s, then 3 s. Build 303 asked every 0.2 s and the site handed
     * back the same blocked server 4 times in a row; it seems to change its answer only every few seconds.
     */
    fun reaskDelayMs(tryNo: Int): Long = (tryNo.coerceIn(1, 3) * 1_000).toLong()

    /** Failures that a dead internet connection also produces: check the connection before running the order. */
    fun checksNetwork(f: Failure?): Boolean =
        f == null || f == Failure.NETWORK || f == Failure.STALLED || f == Failure.OTHER || f == Failure.SERVER_ERROR

    /** How long the player waits for the internet to come back before running the order anyway, and how often it looks. */
    const val NET_WAIT_MS = 120_000L
    const val NET_POLL_MS = 3_000L

    /** Blocked servers (broken secure connection) in one watch before the TV suggests the VPN. */
    const val BLOCKS_BEFORE_NOTE = 3
}
