package com.streamlink.tv

import android.app.Application
import android.content.ComponentCallbacks2

class StreamLinkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServerMemory.init(this)
        OfflineMarks.init(this)
        DeadHosts.init(this)
        ReportLog.init(this)
        ScoreFeed.init(this)
        CrashRecorder.install(this)
        CrashRecorder.reportLast(this)
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
