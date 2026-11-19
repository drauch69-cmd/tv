package com.streamlink.tv

/**
 * Pure rule for ending a primary search early. Each direct stream is checked the moment it is found; once one of them
 * passed (a working, non-ad playlist) the search does not wait out the rest of the sibling window. Streams whose
 * check already finished and passed are kept; the ones still being checked (or failed) are left out.
 */
object EarlyFinish {
    enum class State { PENDING, GOOD, BAD }

    data class Item(val url: String, val embedded: Boolean, val possible: Boolean, val state: State)

    /**
     * The addresses to finish with, or null to keep waiting. A copy found only inside another request, or a possible
     * playlist, never triggers an early finish (the player's own request, with its access code, may still arrive).
     */
    fun keep(items: List<Item>): Set<String>? {
        if (items.none { !it.embedded && !it.possible && it.state == State.GOOD }) return null
        return items.filter { it.state == State.GOOD }.map { it.url }.toSet()
    }
}
