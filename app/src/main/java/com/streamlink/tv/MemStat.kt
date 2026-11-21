package com.streamlink.tv

import android.app.ActivityManager
import android.content.Context
import android.os.Debug

/**
 * Memory numbers for the report (2026-11-21): how much the app uses and how much the box has free, logged when a
 * search starts, when playback starts and when Android trims or warns. Measured off the main thread (reading the
 * app's own memory use can take a moment).
 */
object MemStat {
    /** Logs one "Memory (<why>): ..." line from a background thread. Never throws. */
    fun log(ctx: Context, why: String) {
        val app = ctx.applicationContext
        Thread({
            try {
                val am = app.getSystemService(ActivityManager::class.java)
                val mi = ActivityManager.MemoryInfo()
                am?.getMemoryInfo(mi)
                val rt = Runtime.getRuntime()
                Report.log(
                    MemText.line(
                        why = why,
                        appMb = Debug.getPss() / 1024,
                        heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / MB,
                        heapMaxMb = rt.maxMemory() / MB,
                        freeMb = mi.availMem / MB,
                        totalMb = mi.totalMem / MB,
                        low = mi.lowMemory,
                    ),
                )
            } catch (_: Throwable) {}
        }, "mem-stat").apply { isDaemon = true }.start()
    }

    private const val MB = 1024L * 1024L
}

/** Pure text for [MemStat], unit tested. */
object MemText {
    fun line(why: String, appMb: Long, heapUsedMb: Long, heapMaxMb: Long, freeMb: Long, totalMb: Long, low: Boolean): String =
        "Memory ($why): app uses $appMb MB (Java $heapUsedMb of $heapMaxMb MB); box has $freeMb of $totalMb MB free" +
            if (low) "; Android says memory is low" else ""
}
