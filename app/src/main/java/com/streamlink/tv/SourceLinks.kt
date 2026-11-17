package com.streamlink.tv

/** What assets/source-switch.js found next to the player: a row of alternate-stream buttons. */
data class Switcher(
    val count: Int,
    /** Index of the link that is showing now, or -1 when the page does not show which. */
    val active: Int,
    val labels: List<String>,
    val xs: List<Float>,
    val ys: List<Float>,
    val iw: Float,
)

/** Pure helpers for the alternate-links phase of [StreamSniffer] (kept apart so they run on the JVM). */
object LinkOrder {
    const val MAX_LINKS = 8
    private const val MAX_LABEL = 24

    /** Parses the answer of source-switch.js "find"; null when there is no usable switcher. */
    internal fun parseFind(raw: String?): Switcher? {
        if (raw == null || raw == "null") return null
        val parsed: Map<*, *>? = try {
            // evaluateJavascript hands back the JSON string quoted once more
            val first = MiniJson.parse(raw)
            (if (first is String) MiniJson.parse(first) else first) as? Map<*, *>
        } catch (_: Exception) {
            null
        }
        val o = parsed ?: return null
        if (o["found"] != true) return null
        val labels = (o["labels"] as? List<*>)?.map { cleanLabel(it as? String ?: "") } ?: return null
        val n = labels.size
        if (n < 2 || n > MAX_LINKS) return null
        fun nums(k: String) = (o[k] as? List<*>)?.map { (it as? Double)?.toFloat() ?: 0f } ?: List(n) { 0f }
        val xs = nums("xs").let { if (it.size == n) it else List(n) { 0f } }
        val ys = nums("ys").let { if (it.size == n) it else List(n) { 0f } }
        val active = ((o["active"] as? Double)?.toInt() ?: -1).let { if (it in 0 until n) it else -1 }
        return Switcher(n, active, labels, xs, ys, (o["iw"] as? Double)?.toFloat() ?: 0f)
    }

    /** Text from the page for display only: whitespace collapsed, shortened. */
    fun cleanLabel(raw: String): String {
        val t = raw.replace(Regex("\\s+"), " ").trim()
        return if (t.length <= MAX_LABEL) t else t.substring(0, MAX_LABEL - 1).trimEnd() + "…"
    }

    /** "<link label> · adaptive"; without a link label the host is shown, as before. */
    fun sourceLabel(linkLabel: String, host: String, kind: String, responded: Boolean): String =
        (if (linkLabel.isNotBlank()) linkLabel else host) + " · " + kind + (if (responded) "" else " · didn't respond")

    /** The next link still to try (lowest index not tried yet), or null when all were. */
    fun nextToTry(count: Int, tried: Set<Int>): Int? = (0 until count).firstOrNull { it !in tried }

    /** Keeps the ranking, except that the first (default) link's stream goes first when it is good. */
    fun <T> defaultFirst(ranked: List<T>, isGoodDefault: (T) -> Boolean): List<T> {
        val i = ranked.indexOfFirst(isGoodDefault)
        if (i <= 0) return ranked
        return listOf(ranked[i]) + ranked.filterIndexed { j, _ -> j != i }
    }

    /** Two streams from the same link get " (2)", " (3)" so the Source list stays readable. */
    fun uniqueLabels(labels: List<String>): List<String> {
        val seen = HashMap<String, Int>()
        val total = labels.groupingBy { it }.eachCount()
        return labels.map { l ->
            if ((total[l] ?: 0) < 2) l else {
                val n = (seen[l] ?: 0) + 1
                seen[l] = n
                if (n == 1) l else "$l ($n)"
            }
        }
    }
}
