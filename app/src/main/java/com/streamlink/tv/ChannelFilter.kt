package com.streamlink.tv

/**
 * Tells matchups ("A vs B") from channels, and applies the user's own "Live now" channel words.
 * Pure (no Android), unit tested.
 *
 * Deliberate, user-owned exception to the "never key on page words" rule: the word list is the user's own viewing
 * filter (typed by them on the phone page), not detection. Whether a title is a matchup is decided by structure
 * only: a separator token between two sides, as the list reader builds titles.
 */
object ChannelFilter {
    /** What the user sees until they change the words. */
    val DEFAULT_WORDS: List<String> = listOf("ESPN", "NFL", "NBA", "RedZone")
    const val MAX_WORDS = 20
    const val MAX_WORD_LENGTH = 30

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

    /** Words from typed text: separated by commas or new lines, trimmed, no blanks or repeats (any case), capped. */
    fun parseWords(raw: String): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (p in raw.split(',', '\n', ';')) {
            val w = p.filter { !it.isISOControl() }.trim().replace(Regex("\\s+"), " ").take(MAX_WORD_LENGTH).trim()
            if (w.isEmpty() || !seen.add(w.lowercase())) continue
            out.add(w)
            if (out.size >= MAX_WORDS) break
        }
        return out
    }

    fun joinWords(words: List<String>): String = words.joinToString("\n")

    /**
     * Whether an item belongs on the "Live now" row / tab. [matchedGame]: it matched an NFL/CFB/NBA game (ESPN decides);
     * otherwise it must be a channel whose title holds one of [words].
     */
    fun inLiveNow(title: String, matchedGame: Boolean, words: List<String>): Boolean =
        matchedGame || (isChannel(title) && matchesWords(title, words))
}
