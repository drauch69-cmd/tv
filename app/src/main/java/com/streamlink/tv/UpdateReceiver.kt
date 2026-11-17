package com.streamlink.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/**
 * Gets the PackageInstaller's answer for an app update. Not exported: only the system can send to it
 * (through the PendingIntent that Updater creates). Shows Android's own confirmation when it asks for one.
 */
class UpdateReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    Updater.installFailed("Update install failed: Android gave no confirmation screen")
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                    Report.log("Update: waiting for you to confirm the install on the TV")
                } catch (e: Exception) {
                    Updater.installFailed("Update install failed: could not open Android's install screen (${e.message})")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Report.log("Update: installed")
            PackageInstaller.STATUS_FAILURE_ABORTED -> Updater.installFailed("Update install cancelled")
            else -> Updater.installFailed("Update install failed (code $status): $message")
        }
    }
}
