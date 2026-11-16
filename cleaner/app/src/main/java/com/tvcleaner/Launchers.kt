package com.tvcleaner

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

/**
 * A home screen app TV Cleaner can install. [signer] is the SHA-256 of the developer's signing
 * certificate: a download signed by anyone else is refused. These are built in, not downloaded,
 * so a changed list online can't point the app at someone else's APK.
 */
data class Launcher(
    val pkg: String,
    val name: String,
    val about: String,
    /** GitHub "owner/repo" whose latest release holds the APK. */
    val repo: String,
    /** Exact asset name, or null when the release has a single APK. */
    val apk: String?,
    val signer: String,
    /** True when the launcher has an accessibility service that takes over the Home button on Google TV. */
    val canOverride: Boolean,
)

object Launchers {
    // Signing certificates as published in Tellyscope's launcher list (github.com/man15h/tellyscope, data/launchers.json).
    val ALL = listOf(
        Launcher(
            pkg = "com.spocky.projengmenu",
            name = "Projectivy Launcher",
            about = "The most popular. Rows, wallpapers and icons you can change.",
            repo = "spocky/miproja1",
            apk = null,
            signer = "f6697bf4082ee97511e4de07863193884a015b7ab5860430321bda1042b0aadd",
            canOverride = true,
        ),
        Launcher(
            pkg = "com.overdevs.at4k",
            name = "AT4K Launcher",
            about = "Light and fast, with widgets and folders.",
            repo = "avadhesh18/at4k",
            apk = null,
            signer = "bfc84ab1296c72653ebca20a2f4cd293bc342a67dd4c61df931edfbad5271685",
            canOverride = true,
        ),
        Launcher(
            pkg = "me.efesser.flauncher",
            name = "FLauncher",
            about = "Simple: your apps in rows, nothing else.",
            repo = "osrosal/flauncher",
            apk = "flauncher-universal-release.apk",
            signer = "3ef9f30df1f52f48b4b0c524a6e1861faedd2cccfa96d03e90076a1e30dec101",
            canOverride = false,
        ),
    )

    private const val MAX_BYTES = 64L * 1024 * 1024
    private const val A11Y_ACTION = "android.accessibilityservice.AccessibilityService"
    private val COMPONENT = Regex("[A-Za-z][A-Za-z0-9_.]*/[A-Za-z0-9_.$]+")

    /** The APK's download address in [l]'s latest GitHub release. Throws with a readable message. */
    fun latestApkUrl(l: Launcher): String {
        val json = Net.get("https://api.github.com/repos/${l.repo}/releases/latest")
        val assets = JSONObject(json).optJSONArray("assets") ?: error("The release has no files")
        val apks = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
        val pick = when {
            l.apk != null -> apks.firstOrNull { it.optString("name") == l.apk }
            apks.size == 1 -> apks.first()
            else -> apks.firstOrNull { it.optString("name").contains("universal", ignoreCase = true) }
        } ?: error("Couldn't find the APK in ${l.name}'s latest release")
        if (pick.optLong("size") > MAX_BYTES) error("The APK is unexpectedly large")
        return pick.optString("browser_download_url").takeIf { it.startsWith("https://github.com/") }
            ?: error("Unexpected download address")
    }

    /** Downloads [l]'s APK to the app's cache and checks it's really [l], signed by its developer. */
    fun download(ctx: Context, l: Launcher, onProgress: (Int) -> Unit): File {
        val file = File(ctx.cacheDir, "launcher.apk")
        Net.download(latestApkUrl(l), file, MAX_BYTES, onProgress)
        val problem = check(ctx, file, l)
        if (problem != null) {
            file.delete()
            error(problem)
        }
        return file
    }

    /** Null when [file] is [l] signed by its developer; otherwise what's wrong. */
    @Suppress("DEPRECATION")
    fun check(ctx: Context, file: File, l: Launcher): String? {
        val pm = ctx.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
        } ?: return "The download isn't a valid app"
        if (info.packageName != l.pkg) return "The download is a different app (${info.packageName})"
        val signing = info.signingInfo ?: return "The download isn't signed"
        val certs = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        val hashes = certs.orEmpty().map { sha256(it.toByteArray()) }
        return if (l.signer in hashes) null else "The download isn't signed by ${l.name}'s developer, so it wasn't installed"
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** The accessibility service [pkg] uses to take over Home, as "package/class", or null. */
    @Suppress("DEPRECATION")
    fun overrideService(ctx: Context, pkg: String): String? {
        val found = ctx.packageManager.queryIntentServices(Intent(A11Y_ACTION).setPackage(pkg), PackageManager.GET_META_DATA)
        return found.firstOrNull()?.serviceInfo?.let { "${it.packageName}/${it.name}" }?.takeIf { COMPONENT.matches(it) }
    }

    /** The current value of the enabled accessibility services list, split up. */
    fun enabledServices(): List<String> {
        val r = Shell.run("/system/bin/settings", "get", "secure", "enabled_accessibility_services")
        if (!r.ok) return emptyList()
        return r.out.split(':').map { it.trim() }.filter { it.isNotEmpty() && it != "null" }
    }

    /** Turns [service] on or off, keeping every other accessibility service as it was. */
    fun setService(service: String, on: Boolean): Shell.Result {
        if (!COMPONENT.matches(service)) return Shell.Result(-1, "Refused: unusual service name")
        val now = enabledServices()
        val next = if (on) (now + service).distinct() else now.filter { !it.equals(service, ignoreCase = true) }
        val r = if (next.isEmpty()) {
            Shell.run("/system/bin/settings", "delete", "secure", "enabled_accessibility_services")
        } else {
            Shell.run("/system/bin/settings", "put", "secure", "enabled_accessibility_services", next.joinToString(":"))
        }
        if (r.ok && on) return Shell.run("/system/bin/settings", "put", "secure", "accessibility_enabled", "1")
        return r
    }
}

/** Plain HTTPS helpers. */
object Net {
    fun get(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode == 403) error("GitHub is limiting downloads right now; try again in an hour")
            if (c.responseCode != 200) error("Download failed (HTTP ${c.responseCode})")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    fun download(url: String, to: File, maxBytes: Long, onProgress: (Int) -> Unit) {
        val c = open(url)
        try {
            if (c.responseCode != 200) error("Download failed (HTTP ${c.responseCode})")
            val total = c.contentLengthLong
            if (total > maxBytes) error("The download is unexpectedly large")
            var done = 0L
            c.inputStream.use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastPercent = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        done += n
                        if (done > maxBytes) error("The download is unexpectedly large")
                        out.write(buf, 0, n)
                        val percent = if (total > 0) (done * 100 / total).toInt() else -1
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000
        readTimeout = 30000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "TV-Cleaner")
    }
}
