package com.streamlink.tv

import android.content.Context
import java.io.File
import java.util.concurrent.Executors

/**
 * Running log (user, 2026-11-20): every play's "What happened?" report is kept on the TV, so many can be sent at once
 * from the phone page ("Download all logs"). The last [ReportHistory.MAX_PLAYS] plays, at most
 * [ReportHistory.MAX_BYTES]. The current play is also written now and then, so a crash or a kill doesn't lose it.
 * File work runs on one background thread; nothing here throws.
 */
object ReportLog {
    private const val HISTORY = "report-history.txt"
    private const val CURRENT = "report-current.txt"
    private const val SAVE_EVERY_MS = 10_000L

    @Volatile private var dir: File? = null
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "report-log").apply { isDaemon = true } }
    @Volatile private var lastSave = 0L
    @Volatile private var saveQueued = false

    /** App start: a play left over from last time (crash, update, power off) moves into the history. */
    fun init(ctx: Context) {
        dir = ctx.filesDir
        run {
            val cur = file(CURRENT) ?: return@run
            if (cur.length() > 0) append(cur.readText())
            cur.delete()
        }
    }

    /** A play ended (a new one starts): add its report to the history. */
    fun archive(text: String) = run {
        append(text)
        file(CURRENT)?.delete()
    }

    /** A line was logged: save the current play at most every [SAVE_EVERY_MS]. [text] is read on the background thread. */
    fun touch(text: () -> String) {
        val now = System.currentTimeMillis()
        if (dir == null || saveQueued || now - lastSave < SAVE_EVERY_MS) return
        saveQueued = true
        lastSave = now
        run {
            saveQueued = false
            file(CURRENT)?.writeText(text())
        }
    }

    /** Every saved play, oldest first, then [current] (the play going on now). */
    fun all(current: String): String {
        val h = try { io.submit<String> { file(HISTORY)?.takeIf { it.exists() }?.readText() ?: "" }.get() } catch (_: Exception) { "" }
        return ReportHistory.download(h, current, stamp())
    }

    fun clear() = run { file(HISTORY)?.delete() }

    private fun append(play: String) {
        val f = file(HISTORY) ?: return
        val old = if (f.exists()) f.readText() else ""
        f.writeText(ReportHistory.append(old, play, stamp()))
    }

    private fun stamp() = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())

    private fun file(name: String): File? = dir?.let { File(it, name) }

    private fun run(block: () -> Unit) {
        try { io.execute { try { block() } catch (_: Exception) { } } } catch (_: Exception) { }
    }
}

/** The history file's text rules, pure so they run on the JVM. */
object ReportHistory {
    const val MAX_PLAYS = 25
    const val MAX_BYTES = 1_500_000
    const val MARK = "\n======== Play saved "

    /** [old] plus [play] as a new block; oldest blocks dropped beyond [maxPlays] or [maxBytes]. Blank plays are skipped. */
    fun append(old: String, play: String, stamp: String, maxPlays: Int = MAX_PLAYS, maxBytes: Int = MAX_BYTES): String {
        if (play.isBlank()) return old
        val blocks = split(old).toMutableList()
        blocks.add("$MARK$stamp ========\n${play.trimEnd()}\n")
        while (blocks.size > maxPlays) blocks.removeAt(0)
        while (blocks.size > 1 && blocks.sumOf { it.length } > maxBytes) blocks.removeAt(0)
        return blocks.joinToString("")
    }

    fun count(text: String): Int = split(text).size

    /** The download: a header, the saved plays, then the current one. */
    fun download(history: String, current: String, stamp: String): String {
        val n = count(history)
        return "StreamLink logs, downloaded $stamp: $n saved ${if (n == 1) "play" else "plays"} (oldest first), then the current one.\n" +
            history + "$MARK(current, not finished) ========\n" + current.trimEnd() + "\n"
    }

    private fun split(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>()
        var i = text.indexOf(MARK)
        if (i < 0) return listOf(text)
        while (i >= 0) {
            val next = text.indexOf(MARK, i + MARK.length)
            out.add(if (next < 0) text.substring(i) else text.substring(i, next))
            i = next
        }
        return out
    }
}
