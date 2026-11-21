package com.streamlink.tv

/** Pure text helpers for the crash recorder and the "Last exit" report lines (see CrashRecorder). No Android types. */
object CrashText {
    const val MAX_MESSAGE = 300
    const val STACK_HEAD = 25
    const val APP_EXTRA = 15
    const val CAUSE_HEAD = 10
    const val MAX_CAUSES = 5
    const val ANR_LINES = 40
    private const val APP = "com.streamlink"

    private fun describe(t: Throwable): String {
        val m = t.message?.replace('\n', ' ')?.replace('\r', ' ')?.take(MAX_MESSAGE)
        return if (m.isNullOrEmpty()) t.javaClass.name else "${t.javaClass.name}: $m"
    }

    /** First [head] frames, plus any later frame of this app (up to [APP_EXTRA]); a gap is marked. */
    internal fun frames(st: Array<StackTraceElement>, head: Int): List<String> {
        val out = ArrayList<String>()
        var extra = 0
        var skipped = 0
        for ((i, f) in st.withIndex()) {
            val s = f.toString()
            val keep = i < head || (s.startsWith(APP) && extra++ < APP_EXTRA)
            if (keep) {
                if (skipped > 0) { out.add("  … $skipped more"); skipped = 0 }
                out.add("  at $s")
            } else skipped++
        }
        if (skipped > 0) out.add("  … $skipped more")
        return out
    }

    /** The record written when the app dies: "T millis", "H thread", "E exception", frames, then "C cause" blocks. */
    fun record(millis: Long, thread: String, t: Throwable): String {
        val sb = StringBuilder()
        sb.append("T ").append(millis).append('\n')
        sb.append("H ").append(thread.replace('\n', ' ')).append('\n')
        sb.append("E ").append(describe(t)).append('\n')
        frames(t.stackTrace, STACK_HEAD).forEach { sb.append(it).append('\n') }
        var c = t.cause
        var n = 0
        while (c != null && c !== t && n < MAX_CAUSES) {
            sb.append("C ").append(describe(c)).append('\n')
            frames(c.stackTrace, CAUSE_HEAD).forEach { sb.append(it).append('\n') }
            c = c.cause
            n++
        }
        return sb.toString()
    }

    fun recordMillis(rec: String): Long =
        rec.lineSequence().firstOrNull { it.startsWith("T ") }?.substring(2)?.trim()?.toLongOrNull() ?: 0L

    /** Report lines for a stored record; [fmtTime] formats millis as local time. */
    fun crashLines(rec: String, fmtTime: (Long) -> String): List<String> {
        val out = ArrayList<String>()
        val lines = rec.lines().filter { it.isNotEmpty() }
        val ex = lines.firstOrNull { it.startsWith("E ") }?.substring(2) ?: return out
        val th = lines.firstOrNull { it.startsWith("H ") }?.substring(2) ?: "?"
        val ms = recordMillis(rec)
        out.add("Crash: $ex")
        out.add("Crash thread: $th" + if (ms > 0) ", at ${fmtTime(ms)}" else "")
        for (l in lines) {
            when {
                l.startsWith("E ") || l.startsWith("H ") || l.startsWith("T ") -> {}
                l.startsWith("C ") -> out.add("Caused by: ${l.substring(2)}")
                else -> out.add(l)
            }
        }
        return out
    }

    fun reasonName(reason: Int): String = when (reason) {
        1 -> "the app quit itself"
        2 -> "killed by a signal"
        3 -> "low memory"
        4 -> "crash"
        5 -> "native crash"
        6 -> "ANR (app not responding)"
        7 -> "failed to start"
        8 -> "permission change"
        9 -> "too many resources used"
        10 -> "user requested"
        11 -> "user stopped it"
        12 -> "a dependency died"
        13 -> "other"
        14 -> "frozen by the system"
        15 -> "package state change"
        16 -> "package updated"
        else -> "unknown ($reason)"
    }

    fun importanceName(importance: Int): String = when {
        importance <= 100 -> "in the foreground"
        importance <= 125 -> "foreground service"
        importance <= 150 -> "top, sleeping"
        importance <= 200 -> "visible"
        importance <= 230 -> "perceptible"
        importance <= 300 -> "service"
        importance < 1000 -> "cached in the background"
        else -> "gone"
    }

    fun exitLine(reason: Int, importance: Int, timeText: String, description: String?, status: Int): String {
        val sb = StringBuilder("Last exit: ").append(reasonName(reason)).append(" at ").append(timeText)
            .append(", was ").append(importanceName(importance))
        if (reason == 1 || reason == 2) sb.append(", status ").append(status)
        val d = description?.replace('\n', ' ')?.trim().orEmpty()
        if (d.isNotEmpty()) sb.append(" (").append(d.take(200)).append(')')
        return sb.toString()
    }

    /** Only exits newer than the last reported one are reported. */
    fun shouldReport(timestamp: Long, lastReported: Long): Boolean = timestamp > lastReported

    /** The "main" thread section of an ANR trace, as "  ..." lines (at most [max]). */
    fun mainThreadStack(trace: String, max: Int = ANR_LINES): List<String> {
        val out = ArrayList<String>()
        var inMain = false
        for (raw in trace.lineSequence()) {
            if (!inMain) {
                if (raw.startsWith("\"main\"")) inMain = true
                continue
            }
            if (raw.isBlank()) break
            val l = raw.trim()
            if (l.startsWith("at ") || l.startsWith("native:") || l.startsWith("-") || l.startsWith("waiting") || l.startsWith("held")) {
                out.add("  $l")
                if (out.size >= max) break
            }
        }
        return out
    }

    const val TRIM_RUNNING_LOW = 10
    const val TRIM_UI_HIDDEN = 20 // the app went to the background: not memory pressure

    /** True when this memory level should be logged now (once per level per minute); records the time when it is. */
    /**
     * The report line for an Android trim [level]. 40 only means the app went to the background (2026-11-21: it was
     * logged as "system is low", which it is not); 10/15 are real pressure while open, 60/80 while in the background.
     */
    fun memoryText(level: Int): String = when (level) {
        10, 15 -> "Memory: system is low while the app is open (level $level)"
        40 -> "App moved to the background (Android trim level 40; not low memory)"
        60 -> "Memory: system is getting low while the app is in the background (level 60)"
        80 -> "Memory: system is very low; Android may close the app soon (level 80)"
        else -> "Memory: Android trim level $level"
    }

    fun shouldLogMemory(last: MutableMap<Int, Long>, level: Int, now: Long): Boolean {
        if (level < TRIM_RUNNING_LOW || level == TRIM_UI_HIDDEN) return false
        val prev = last[level]
        if (prev != null && now - prev < 60_000L) return false
        last[level] = now
        return true
    }
}
