package com.tvcleaner

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** One app on the TV. [installed] is false when it was uninstalled for this user but can come back. */
data class TvApp(
    val pkg: String,
    val label: String,
    val system: Boolean,
    val installed: Boolean,
    val enabled: Boolean,
)

object Apps {
    @Suppress("DEPRECATION")
    fun load(ctx: Context, labels: Map<String, String>): Map<String, TvApp> {
        val pm = ctx.packageManager
        val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS
        val infos: List<ApplicationInfo> = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags.toLong()))
        } else {
            pm.getInstalledApplications(flags)
        }
        return infos.associate { ai ->
            val installed = (ai.flags and ApplicationInfo.FLAG_INSTALLED) != 0
            val setting = try {
                pm.getApplicationEnabledSetting(ai.packageName)
            } catch (_: Exception) {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            }
            val enabled = when (setting) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> ai.enabled
                else -> false
            }
            val loaded = try {
                ai.loadLabel(pm).toString()
            } catch (_: Exception) {
                ai.packageName
            }
            val label = if (loaded.isBlank() || loaded == ai.packageName) labels[ai.packageName] ?: ai.packageName else loaded
            ai.packageName to TvApp(
                pkg = ai.packageName,
                label = label,
                system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                installed = installed,
                enabled = installed && enabled,
            )
        }
    }

    /** Home screen apps, as package to "is a built-in app". */
    @Suppress("DEPRECATION")
    fun homes(ctx: Context): Map<String, Boolean> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val found = pm.queryIntentActivities(intent, PackageManager.MATCH_DISABLED_COMPONENTS)
        // Settings carries a fallback home that only shows while the TV starts; it's protected anyway.
        return found.map { it.activityInfo.applicationInfo }
            .filter { it.packageName != "com.android.tv.settings" && it.packageName != "com.android.settings" }
            .associate { it.packageName to ((it.flags and ApplicationInfo.FLAG_SYSTEM) != 0) }
    }

    fun tvInfo() = TvInfo(Build.MANUFACTURER, Build.BRAND, Build.MODEL, Build.DEVICE, Build.VERSION.SDK_INT)
}

/** Downloads list files, keeping the last good copy for when the TV is offline. */
class Fetcher(ctx: Context) {
    private val dir = File(ctx.filesDir, "lists")

    /** The file at [path] under [Lists.BASE]; checked against [sha256] when given. Falls back to the saved copy. */
    fun get(path: String, sha256: String? = null): String? {
        val cached = File(dir, path)
        if (sha256 != null && cached.exists()) {
            val text = cached.readText()
            if (Lists.sha256(text) == sha256) return text
        }
        val fresh = try {
            download(Lists.BASE + path)
        } catch (_: Exception) {
            null
        }
        if (fresh != null && (sha256 == null || Lists.sha256(fresh) == sha256)) {
            cached.parentFile?.mkdirs()
            cached.writeText(fresh)
            return fresh
        }
        // Offline or a bad download: the last saved copy is better than nothing.
        return if (cached.exists()) cached.readText() else null
    }

    private fun download(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "TV-Cleaner")
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }
}
