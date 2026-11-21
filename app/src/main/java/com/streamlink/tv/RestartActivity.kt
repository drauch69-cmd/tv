package com.streamlink.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process

/**
 * Restarts the app after a settings restore. Runs in its own small process (":restart"), so it can end the
 * main process (which still holds the old settings in memory) and then open home in a fresh one, where
 * [SettingsBackup.applyPending] swaps the backup in.
 */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pid = intent.getIntExtra(PID, -1)
        if (pid > 0) Process.killProcess(pid)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
        Runtime.getRuntime().exit(0)
    }

    companion object {
        private const val PID = "pid"
        const val PROCESS_SUFFIX = ":restart"

        fun restart(ctx: Context) {
            ctx.startActivity(Intent(ctx, RestartActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(PID, Process.myPid()))
        }
    }
}
