package com.tvcleaner

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Keeps TV Cleaner up to date from its own GitHub release (the one CI publishes as "cleaner-latest").
 * A download is installed only if it's TV Cleaner signed with the same key as the copy running now.
 */
object Updater {
    private const val RELEASE = "https://api.github.com/repos/drauch69-cmd/tv/releases/tags/cleaner-latest"
    private const val ASSET = "TVCleaner.apk"
    private const val MAX_BYTES = 64L * 1024 * 1024
    private val BUILD = Regex("""Build\s+(\d+)""")

    data class Available(val build: Int, val url: String)

    /** The build number in release notes like "Build 42", or null. */
    fun parseBuild(notes: String?): Int? = notes?.let { BUILD.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    @Suppress("DEPRECATION")
    fun installedBuild(ctx: Context): Long {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    /** The newer build on GitHub, or null when this one is current. Throws with a readable message. */
    fun check(ctx: Context): Available? {
        val json = JSONObject(Net.get(RELEASE))
        val build = parseBuild(json.optString("body")) ?: error("Couldn't read the latest build number")
        if (build <= installedBuild(ctx)) return null
        val assets = json.optJSONArray("assets") ?: error("The release has no files")
        val apk = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            .firstOrNull { it.optString("name") == ASSET } ?: error("The release has no $ASSET")
        if (apk.optLong("size") > MAX_BYTES) error("The update is unexpectedly large")
        val url = apk.optString("browser_download_url").takeIf { it.startsWith("https://github.com/") }
            ?: error("Unexpected download address")
        return Available(build, url)
    }

    /** Downloads [a], checks it, and installs it over this app. TV Cleaner closes and reopens itself. */
    fun install(ctx: Context, a: Available, onProgress: (Int) -> Unit): Shell.Result {
        val file = File(ctx.cacheDir, "update.apk")
        Net.download(a.url, file, MAX_BYTES, onProgress)
        val problem = verify(ctx, file)
        if (problem != null) {
            file.delete()
            return Shell.Result(-1, problem)
        }
        // Installing replaces this app, which stops it; a small helper reopens it once it's gone.
        reopenLater(ctx)
        val r = Shell.install(file)
        file.delete()
        return r
    }

    /** Null when [file] is TV Cleaner signed like this copy; otherwise what's wrong. */
    @Suppress("DEPRECATION")
    private fun verify(ctx: Context, file: File): String? {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getPackageArchiveInfo(file.path, flags)
        } ?: return "The download isn't a valid app"
        if (info.packageName != ctx.packageName) return "The download is a different app (${info.packageName})"
        val mine = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getPackageInfo(ctx.packageName, flags)
        }
        val theirs = hashes(info.signingInfo)
        return if (theirs.isNotEmpty() && theirs == hashes(mine.signingInfo)) null else "The download isn't signed like this copy, so it wasn't installed"
    }

    private fun hashes(s: android.content.pm.SigningInfo?): Set<String> {
        val certs = when {
            s == null -> null
            s.hasMultipleSigners() -> s.apkContentsSigners
            else -> s.signingCertificateHistory
        }
        return certs.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Starts a detached shell helper that waits (up to 90 s) for this process to end, then opens TV Cleaner
     * (only if it really closed, so a failed install doesn't pop the app up later). */
    private fun reopenLater(ctx: Context) {
        val pid = Process.myPid()
        val activity = "${ctx.packageName}/.MainActivity"
        val script = "(i=0; while [ -d /proc/$pid ] && [ \$i -lt 90 ]; do sleep 1; i=\$((i+1)); done; " +
            "[ -d /proc/$pid ] || { sleep 2; am start -n $activity; }) </dev/null >/dev/null 2>&1 &"
        Shell.run("/system/bin/sh", "-c", script)
    }
}
