package com.tvcleaner

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

data class Home(val activity: String, val system: Boolean)

/** One app on the TV. [installed] is false when it was uninstalled for this user but can come back. */
data class TvApp(val pkg: String, val label: String, val system: Boolean, val installed: Boolean, val enabled: Boolean)

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

    /** Home screen apps by package: its home activity as "package/class", and whether it came with the TV. */
    @Suppress("DEPRECATION")
    fun homes(ctx: Context): Map<String, Home> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val found = pm.queryIntentActivities(intent, PackageManager.MATCH_DISABLED_COMPONENTS)
        // Settings carries a fallback home that only shows while the TV starts; it's protected anyway.
        return found.map { it.activityInfo }
            .filter { it.packageName != "com.android.tv.settings" && it.packageName != "com.android.settings" }
            .associate {
                it.packageName to Home("${it.packageName}/${it.name}", (it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
            }
    }

    /**
     * The TV's own home screen: Google's, if present, then any other built-in one that isn't the setup
     * wizard (which registers a Home screen of its own for recovery).
     */
    fun stockHome(homes: Map<String, Home>): Map.Entry<String, Home>? {
        val known = listOf("com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher", "com.google.android.leanbacklauncher")
        known.forEach { k -> homes.entries.firstOrNull { it.key == k }?.let { return it } }
        return homes.entries.firstOrNull { (pkg, home) ->
            home.system && listOf("setup", "wizard", "wraith", "provision").none { it in pkg.lowercase() }
        }
    }

    /** The package Home opens right now, or null when Android would ask. */
    @Suppress("DEPRECATION")
    fun currentHome(ctx: Context): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return ctx.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            ?.takeIf { it != "android" }
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

    private fun download(url: String): String = Net.get(url)
}
