package com.streamlink.tv

/**
 * Tells matchups ("A vs B") from channels, and finds NFL RedZone. Pure (no Android), unit tested.
 *
 * Deliberate, user-owned exception to the "never key on page words" rule: "RedZone" is the user's own viewing
 * choice (it gets a card in Live games), not detection. Whether a title is a matchup is decided by structure
 * only: a separator token between two sides, as the list reader builds titles.
 */
object ChannelFilter {
    /** The one channel word left (user, 2026-11-19): RedZone shows first in Live games while it airs. */
    val REDZONE: List<String> = listOf("RedZone")

    /** Separator tokens the list reader puts between two sides (hyphens are left out: "NBA TV - HD" is a channel). */
    private val SEPARATORS = setOf("vs", "vs.", "v", "v.", "versus", "@", "at")

    /** True for a two-sided title: a separator token with letters on both sides ("A vs B", "A at B", "A @ B"). */
    fun isMatchup(title: String): Boolean {
        val t = title.trim().split(Regex("\\s+"))
        for (i in 1 until t.size - 1) {
            if (t[i].lowercase() !in SEPARATORS) continue
            if (t.subList(0, i).any { hasLetter(it) } && t.subList(i + 1, t.size).any { hasLetter(it) }) return true
        }
        return false
    }

    private fun hasLetter(s: String) = s.any { it.isLetter() }

    /** A channel (or show): an entry with a title that is not a matchup. */
    fun isChannel(title: String): Boolean = title.isNotBlank() && !isMatchup(title)

    /**
     * True when [title] holds one of [words] as a whole word, ignoring case. A word written in camel case also
     * matches with a space inside ("RedZone" matches "Red Zone"); spaces inside a word match any run of spaces.
     */
    fun matchesWords(title: String, words: List<String>): Boolean {
        if (title.isBlank()) return false
        for (w in words) {
            val re = wordRegex(w) ?: continue
            if (re.containsMatchIn(title)) return true
        }
        return false
    }

    private val regexCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    private fun wordRegex(word: String): Regex? {
        val w = word.trim()
        if (w.isEmpty() || !w.any { it.isLetterOrDigit() }) return null
        return regexCache.getOrPut(w) {
            val parts = w.split(Regex("\\s+")).joinToString("\\s+") { part ->
                // split at lower-case -> upper-case steps ("RedZone" -> "Red", "Zone"), optional space between
                part.split(Regex("(?<=\\p{Ll})(?=\\p{Lu})")).joinToString("\\s?") { Regex.escape(it) }
            }
            Regex("(?<![\\p{L}\\p{N}])$parts(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        }
    }

    /** A channel whose title says RedZone ("NFL RedZone", "Red Zone"). */
    fun isRedZone(title: String): Boolean = isChannel(title) && matchesWords(title, REDZONE)

    /**
     * Whether an item belongs on the phone's Live now tab: it matched an NFL/CFB/NBA/college basketball game (ESPN decides), or it is
     * RedZone.
     */
    fun inLiveNow(title: String, matchedGame: Boolean): Boolean = matchedGame || isRedZone(title)
}
