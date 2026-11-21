package com.streamlink.tv

import android.app.Application
import android.content.ComponentCallbacks2

class StreamLinkApp : Application() {
    companion object {
        /** True on the first start after a settings restore (for the report). */
        @Volatile var restoredBackup = false
    }

    private fun processName(): String =
        if (android.os.Build.VERSION.SDK_INT >= 28) Application.getProcessName()
        else try { java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ') } catch (_: Exception) { "" }

    override fun onCreate() {
        super.onCreate()
        // The restart helper's own process needs none of the app's start-up.
        if (processName().endsWith(RestartActivity.PROCESS_SUFFIX)) return
        // A restored backup is swapped in before anything reads a setting.
        restoredBackup = SettingsBackup.applyPending(this)
        ServerMemory.init(this)
        OfflineMarks.init(this)
        DeadHosts.init(this)
        ReportLog.init(this)
        ScoreFeed.init(this)
        CrashRecorder.install(this)
        CrashRecorder.reportLast(this)
        if (restoredBackup) Report.pin("Settings restored from a backup file")
    }

    @Suppress("DEPRECATION")
    override fun onLowMemory() {
        super.onLowMemory()
        CrashRecorder.memory(this, ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        CrashRecorder.memory(this, level)
    }
}
