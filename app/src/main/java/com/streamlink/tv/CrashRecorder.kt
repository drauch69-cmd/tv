package com.streamlink.tv

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Records why the app last died (our own crash note + Android's exit record) and puts it in the report. Never throws. */
object CrashRecorder {
    private const val FILE = "last_crash.txt"
    private const val PREFS = "crash_recorder"
    private const val KEY_LAST = "last_exit_ts"
    private val memLast = HashMap<Int, Long>()

    private fun time(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    fun install(ctx: Context) {
        try {
            val app = ctx.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                try {
                    val text = CrashText.record(System.currentTimeMillis(), t.name, e)
                    FileOutputStream(File(app.filesDir, FILE)).use { it.write(text.toByteArray()); it.fd.sync() }
                } catch (_: Throwable) {}
                try { previous?.uncaughtException(t, e) } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    /** Called once at start: pins the lines about the last exit into the report. */
    fun reportLast(ctx: Context) {
        try {
            val app = ctx.applicationContext
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lines = ArrayList<String>()
            var newest = prefs.getLong(KEY_LAST, 0L)
            val last = newest
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    val e = am.getHistoricalProcessExitReasons(app.packageName, 0, 3).firstOrNull()
                    if (e != null && CrashText.shouldReport(e.timestamp, last)) {
                        lines.add(CrashText.exitLine(e.reason, e.importance, time(e.timestamp), e.description, e.status))
                        newest = e.timestamp
                        if (e.reason == ApplicationExitInfo.REASON_ANR) {
                            try {
                                e.traceInputStream?.use { s ->
                                    val text = s.bufferedReader().readText()
                                    lines.addAll(CrashText.mainThreadStack(text))
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                } catch (_: Throwable) {}
            }
            val f = File(app.filesDir, FILE)
            if (f.exists()) {
                try {
                    val rec = f.readText()
                    val ms = CrashText.recordMillis(rec)
                    if (CrashText.shouldReport(ms, last) || ms == 0L) lines.addAll(CrashText.crashLines(rec, ::time))
                    if (ms > newest) newest = ms
                } catch (_: Throwable) {}
                f.delete()
            }
            if (newest != last) prefs.edit().putLong(KEY_LAST, newest).commit()
            lines.forEach { Report.pin(it) }
        } catch (_: Throwable) {}
    }

    fun memory(level: Int) {
        try {
            val log = synchronized(memLast) { CrashText.shouldLogMemory(memLast, level, System.currentTimeMillis()) }
            if (log) Report.log("Memory: system is low (level $level)")
        } catch (_: Throwable) {}
    }
}
