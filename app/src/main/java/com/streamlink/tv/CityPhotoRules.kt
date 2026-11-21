package com.streamlink.tv

import org.json.JSONObject

/**
 * The rules behind city photos on the featured card (user, 2026-11-20): the photo comes from the city the game is
 * played in (ESPN's venue), searched on Unsplash as "<city>" (user, 2026-11-21; was "<city> skyline"), then
 * "<state or country> landscape".
 * Pure (no Android), unit tested; the loader is [CityPhotos].
 */
object CityPhotoRules {
    /** One search result. [raw] is Unsplash's resizable image address; [download] is its "photo used" address. */
    data class Candidate(
        val id: String, val raw: String, val width: Int, val height: Int,
        val name: String, val profile: String, val download: String,
    ) {
        val aspect: Double get() = if (height > 0) width.toDouble() / height else 0.0
    }

    /** Results kept per search (Unsplash's default page size). */
    const val PER_SEARCH = 10

    /** Default framing: a little below the middle, since skylines have sky on top and buildings lower down. */
    const val DEFAULT_X = 0.5f
    const val DEFAULT_Y = 0.6f

    /** Where a game is played, as the key its photo is saved under ("chicago|il"); null without a city. */
    fun placeKey(g: Game): String? {
        val city = g.venueCity.trim()
        if (city.isEmpty()) return null
        val region = g.venueState.trim().ifEmpty { g.venueCountry.trim() }
        return (city + "|" + region).lowercase().replace(Regex("""\s+"""), " ")
    }

    /** "Chicago, IL", "London, England", "Laramie". */
    fun placeLabel(g: Game): String {
        val region = g.venueState.trim().ifEmpty { g.venueCountry.trim() }
        return if (region.isEmpty()) g.venueCity.trim() else "${g.venueCity.trim()}, $region"
    }

    /** Bumped when the searches change, so cities saved under the old search are searched again. */
    const val SEARCH_VERSION = 2

    /** The searches, best first: the city by name, then a landscape of its state (full name) or country. */
    fun queries(g: Game): List<String> {
        val city = g.venueCity.trim()
        if (city.isEmpty()) return emptyList()
        val out = mutableListOf(city)
        val st = g.venueState.trim()
        val region = STATES[st.uppercase()] ?: st.takeIf { it.length > 2 } ?: g.venueCountry.trim()
        if (region.isNotEmpty() && !region.equals(city, ignoreCase = true)) out.add("$region landscape")
        return out
    }

    /** Unsplash's search answer, read into candidates; anything malformed or not on Unsplash's own hosts is dropped. */
    fun parseSearch(json: String): List<Candidate> {
        val arr = try { JSONObject(json).optJSONArray("results") } catch (_: Exception) { null } ?: return emptyList()
        val out = ArrayList<Candidate>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            val raw = o.optJSONObject("urls")?.optString("raw", "") ?: ""
            val w = o.optInt("width", 0)
            val h = o.optInt("height", 0)
            val user = o.optJSONObject("user")
            val name = user?.optString("name", "")?.trim().orEmpty()
            val profile = user?.optJSONObject("links")?.optString("html", "") ?: ""
            val dl = o.optJSONObject("links")?.optString("download_location", "") ?: ""
            if (id.isBlank() || w <= 0 || h <= 0) continue
            if (!raw.startsWith("https://images.unsplash.com/")) continue
            if (!dl.startsWith("https://api.unsplash.com/")) continue
            out.add(Candidate(id, raw, w, h, name.ifEmpty { "Unsplash photographer" }, profile, dl))
        }
        return out
    }

    /** Widest first (wide photos lose the least on the wide card); equal shapes keep Unsplash's order. */
    fun rank(c: List<Candidate>): List<Candidate> = c.take(PER_SEARCH).sortedByDescending { it.aspect }

    /** The image address at [width] pixels wide (Unsplash resizes on its side). */
    fun imageUrl(raw: String, width: Int = 1600): String =
        raw + (if ('?' in raw) "&" else "?") + "w=$width&fm=jpg&q=80"

    /** The credit line shown on the card. */
    fun credit(name: String): String = "Photo: $name / Unsplash"

    /** The next photo after [index] (wraps round). */
    fun next(index: Int, size: Int): Int = if (size <= 0) 0 else (index + 1).mod(size)

    /** A search that found nothing is retried after this long; a failed one (no network, refused) sooner. */
    const val NONE_RETRY_MS = 7 * 24 * 3600_000L
    const val FAIL_RETRY_MS = 30 * 60_000L

    /** At most this many Unsplash requests an hour (demo keys allow 50). */
    const val MAX_PER_HOUR = 40

    /** True when a request may go out now, given the times of earlier ones. */
    fun underLimit(times: List<Long>, now: Long): Boolean = times.count { now - it < 3600_000L } < MAX_PER_HOUR

    val STATES = mapOf(
        "AL" to "Alabama", "AK" to "Alaska", "AZ" to "Arizona", "AR" to "Arkansas", "CA" to "California",
        "CO" to "Colorado", "CT" to "Connecticut", "DE" to "Delaware", "DC" to "Washington DC", "FL" to "Florida",
        "GA" to "Georgia", "HI" to "Hawaii", "ID" to "Idaho", "IL" to "Illinois", "IN" to "Indiana", "IA" to "Iowa",
        "KS" to "Kansas", "KY" to "Kentucky", "LA" to "Louisiana", "ME" to "Maine", "MD" to "Maryland",
        "MA" to "Massachusetts", "MI" to "Michigan", "MN" to "Minnesota", "MS" to "Mississippi", "MO" to "Missouri",
        "MT" to "Montana", "NE" to "Nebraska", "NV" to "Nevada", "NH" to "New Hampshire", "NJ" to "New Jersey",
        "NM" to "New Mexico", "NY" to "New York", "NC" to "North Carolina", "ND" to "North Dakota", "OH" to "Ohio",
        "OK" to "Oklahoma", "OR" to "Oregon", "PA" to "Pennsylvania", "RI" to "Rhode Island",
        "SC" to "South Carolina", "SD" to "South Dakota", "TN" to "Tennessee", "TX" to "Texas", "UT" to "Utah",
        "VT" to "Vermont", "VA" to "Virginia", "WA" to "Washington", "WV" to "West Virginia", "WI" to "Wisconsin",
        "WY" to "Wyoming", "PR" to "Puerto Rico",
    )
}
