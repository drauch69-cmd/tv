package com.streamlink.tv

/**
 * Where the seconds go between pressing Play and the picture appearing, for ONE foreground play attempt.
 * [start] resets (called from [Report.start]); [mark] keeps only the first time each mark is set;
 * [emit] writes one "Timing:" line to the report, once per attempt. Never throws, never changes behavior.
 */
object Timing {
    /** Mark names, in the order they are printed. */
    const val BROWSER = "browser"
    const val LOADED = "loaded"
    const val TAPPED = "tapped"
    const val SEEN = "seen"
    const val CHECKING = "checking"
    const val CHECKED = "checked"
    const val PLAYER = "player"
    const val PICTURE = "picture"

    const val PATH_FULL = "full search"
    const val PATH_HEAD_START = "head start"
    const val PATH_REMEMBERED = "remembered address"
    const val PATH_SITE_PLAYER = "site player"

    /** Clock in ms; replaceable for tests. */
    @Volatile var clock: () -> Long = { System.currentTimeMillis() }

    private val marks = HashMap<String, Long>()
    private var t0 = 0L
    private var emitted = true // nothing is timed until the first start()
    private var browserTookMs: Long? = null
    private var firstBrowser = false
    private var webViewMade = false

    /** How the stream was reached this attempt. A future feature may set "remembered address". */
    @Volatile var path: String = PATH_FULL

    @Synchronized
    fun start() {
        try {
            marks.clear()
            t0 = clock()
            emitted = false
            browserTookMs = null
            firstBrowser = false
            path = PATH_FULL
        } catch (_: Throwable) {
        }
    }

    /** Records the first time [name] is set in this attempt. */
    @Synchronized
    fun mark(name: String) {
        try {
            if (emitted || marks.containsKey(name)) return
            marks[name] = clock() - t0
        } catch (_: Throwable) {
        }
    }

    /**
     * A hidden browser was just created, taking [tookMs]. The process-wide flag always flips (even for
     * quiet runs), so only the very first browser since the app opened is called "first". [timed]: this
     * is a foreground run that may set marks.
     */
    @Synchronized
    fun browserCreated(tookMs: Long, timed: Boolean) {
        try {
            val first = !webViewMade
            webViewMade = true
            if (!timed || emitted || marks.containsKey(BROWSER)) return
            browserTookMs = tookMs
            firstBrowser = first
            marks[BROWSER] = clock() - t0
        } catch (_: Throwable) {
        }
    }

    /** Writes the line to the report, once per attempt. [pathOverride] (e.g. the site player) wins over [path]. */
    @Synchronized
    fun emit(pathOverride: String? = null) {
        try {
            if (emitted) return
            emitted = true
            if (pathOverride != null) path = pathOverride
            Report.log(format(HashMap(marks), browserTookMs, firstBrowser, path))
        } catch (_: Throwable) {
        }
    }

    private val labels = listOf(
        LOADED to "page loaded",
        TAPPED to "tapped",
        SEEN to "stream seen",
        CHECKING to "checking",
        CHECKED to "checked",
        PLAYER to "player",
        PICTURE to "picture",
    )

    private fun secs(ms: Long): String = String.format(java.util.Locale.US, "%.1f s", ms / 1000.0)

    /** Pure: builds the line. Marks never set are left out. */
    fun format(marks: Map<String, Long>, browserTookMs: Long?, firstBrowser: Boolean, path: String): String {
        val parts = ArrayList<String>()
        marks[BROWSER]?.let { at ->
            val notes = ArrayList<String>()
            browserTookMs?.let { notes.add("took ${secs(it)}") }
            if (firstBrowser) notes.add("first browser since the app opened")
            parts.add("browser ready ${secs(at)}" + if (notes.isEmpty()) "" else " (${notes.joinToString(", ")})")
        }
        for ((key, label) in labels) marks[key]?.let { parts.add("$label ${secs(it)}") }
        parts.add("path: $path")
        return "Timing: " + parts.joinToString(" · ")
    }
}
