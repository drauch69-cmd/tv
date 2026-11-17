package com.streamlink.tv

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * In-app updates from this app's own GitHub release channel (main -> `latest`, test -> `test-latest`).
 *
 * Flow: read version.json -> if newer, download the APK -> check its SHA-256 -> check it is our package
 * signed by the same certificate as the installed app -> keep it ready. Nothing is installed until the
 * user presses OK on the home screen banner (or the phone's "Install update on TV" button); then a
 * PackageInstaller session is committed and Android shows its own confirmation.
 */
object Updater {
    const val ACTION_RESULT = "com.streamlink.tv.UPDATE_RESULT"
    private const val IO_TIMEOUT_MS = 10_000
    private const val DOWNLOAD_DEADLINE_MS = 15L * 60 * 1000

    private val busy = AtomicBoolean(false)
    @Volatile private var lastOk = 0L
    @Volatile private var lastFail = 0L
    @Volatile private var readyFile: File? = null
    /** version.json of the verified download; its SHA-256 must still match at install time. */
    @Volatile private var readyInfo: VersionInfo? = null
    /** Called (on any thread) whenever UpdateState changes; the home screen re-draws itself. */
    @Volatile var listener: (() -> Unit)? = null

    private fun changed() { try { listener?.invoke() } catch (_: Exception) {} }

    private fun updatesDir(ctx: Context) = File(ctx.cacheDir, "updates")

    /** Automatic check: only when due, and never while something is playing or being found. */
    fun checkIfDue(ctx: Context) {
        if (Session.mode != "idle") return
        if (!UpdateLogic.checkDue(System.currentTimeMillis(), lastOk, lastFail)) return
        check(ctx, manual = false)
    }

    /** Looks for a newer build now (the home screen's "Check for updates" button uses manual = true). */
    fun check(ctx: Context, manual: Boolean) {
        val app = ctx.applicationContext
        if (UpdateState.phase == UpdateState.Phase.INSTALLING) return
        if (!busy.compareAndSet(false, true)) return
        UpdateState.checking = true
        if (manual) UpdateState.message = "Checking…"
        changed()
        thread(isDaemon = true, name = "updater") {
            try {
                run(app, manual)
            } catch (e: Exception) {
                fail("Update check failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                UpdateState.checking = false
                busy.set(false)
                changed()
            }
        }
    }

    private fun fail(msg: String) {
        lastFail = System.currentTimeMillis()
        UpdateState.message = msg
        Report.log("Update: $msg")
        if (UpdateState.phase == UpdateState.Phase.DOWNLOADING) {
            UpdateState.phase = UpdateState.Phase.NONE
            UpdateState.percent = -1
        }
    }

    private fun run(ctx: Context, manual: Boolean) {
        val channel = BuildConfig.CHANNEL
        val url = UpdateLogic.versionUrl(channel) ?: return fail("Update check failed: unknown channel")
        val text = fetchText(url) ?: return
        val installed = BuildConfig.VERSION_CODE
        when (val r = UpdateLogic.evaluate(text, channel, installed)) {
            is UpdateCheck.Invalid -> fail("Update check failed: ${r.reason}")
            is UpdateCheck.UpToDate -> {
                lastOk = System.currentTimeMillis()
                UpdateState.message = "You have the latest version (build $installed)."
                if (manual) Report.log("Update: up to date (build $installed)")
            }
            is UpdateCheck.Newer -> {
                lastOk = System.currentTimeMillis()
                val info = r.info
                if (UpdateState.phase == UpdateState.Phase.READY && UpdateState.build >= info.versionCode && readyFile?.exists() == true) {
                    UpdateState.message = "Update ready (build ${UpdateState.build})."
                    return
                }
                download(ctx, info)
            }
        }
    }

    /** Small text download with the size cap; null (after recording the failure) if anything is off. */
    private fun fetchText(url: String): String? {
        val c = open(url)
        try {
            val code = c.responseCode
            if (code != 200) { fail("Update check failed: HTTP $code"); return null }
            if (c.url.protocol != "https") { fail("Update check failed: not served over https"); return null }
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(2048)
            c.inputStream.use { ins ->
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > UpdateLogic.MAX_VERSION_JSON) { fail("Update check failed: version.json too large"); return null }
                }
            }
            return out.toString("UTF-8")
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        require(url.startsWith("https://")) { "https only" }
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = IO_TIMEOUT_MS
        c.readTimeout = IO_TIMEOUT_MS
        c.instanceFollowRedirects = true // GitHub redirects release downloads to its CDN (https -> https)
        c.useCaches = false
        c.setRequestProperty("User-Agent", "StreamLink-updater")
        return c
    }

    private fun download(ctx: Context, info: VersionInfo) {
        val dir = updatesDir(ctx)
        dir.mkdirs()
        val target = File(dir, "update-${info.versionCode}.apk")
        // Delete older downloads (and leftovers of failed ones), keep only what we are about to use.
        dir.listFiles()?.forEach { if (it.name != target.name) it.delete() }
        readyFile = null
        UpdateState.build = info.versionCode
        UpdateState.percent = 0
        UpdateState.phase = UpdateState.Phase.DOWNLOADING
        UpdateState.message = "Downloading update (build ${info.versionCode})…"
        changed()
        Report.log("Update: build ${info.versionCode} is available (installed ${BuildConfig.VERSION_CODE}); downloading")

        // A copy that is already here and still checks out needs no new download.
        if (!(target.exists() && verify(ctx, target, info) == null)) {
            target.delete()
            val part = File(dir, target.name + ".part")
            part.delete()
            val apkUrl = UpdateLogic.apkUrl(BuildConfig.CHANNEL) ?: return fail("Update failed: unknown channel")
            val c = open(apkUrl)
            try {
                val code = c.responseCode
                if (code != 200) return fail("Update download failed: HTTP $code")
                if (c.url.protocol != "https") return fail("Update download failed: not served over https")
                val len = c.contentLengthLong
                if (len > UpdateLogic.MAX_APK_BYTES) return fail("Update download refused: file is larger than 80 MB")
                var total = 0L
                var lastLogged = -1
                val deadline = System.currentTimeMillis() + DOWNLOAD_DEADLINE_MS
                c.inputStream.use { ins ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > UpdateLogic.MAX_APK_BYTES) { part.delete(); return fail("Update download refused: file is larger than 80 MB") }
                            if (System.currentTimeMillis() > deadline) { part.delete(); return fail("Update download took too long") }
                            out.write(buf, 0, n)
                            if (len > 0) {
                                val pct = (total * 100 / len).toInt()
                                if (pct != UpdateState.percent) { UpdateState.percent = pct; changed() }
                                if (pct / 10 != lastLogged) { lastLogged = pct / 10; Report.log("Update: downloading… $pct%") }
                            }
                        }
                    }
                }
                if (len > 0 && total != len) { part.delete(); return fail("Update download was cut short") }
            } finally {
                c.disconnect()
            }
            if (!part.renameTo(target)) { part.delete(); return fail("Update failed: could not save the download") }
        }

        val problem = verify(ctx, target, info)
        if (problem != null) {
            target.delete()
            return fail("Update refused: $problem")
        }
        readyFile = target
        readyInfo = info
        UpdateState.percent = 100
        UpdateState.phase = UpdateState.Phase.READY
        UpdateState.message = "Update ready (build ${info.versionCode})."
        Report.log("Update: build ${info.versionCode} downloaded and verified (SHA-256, package name and signing certificate); waiting for the user to press OK")
        changed()
    }

    /** All checks an APK must pass before it may be installed. Returns null if it is fine, else why not. */
    private fun verify(ctx: Context, apk: File, info: VersionInfo): String? {
        val sha = try { FileInputStream(apk).use { UpdateLogic.sha256Hex(it) } } catch (e: Exception) { return "cannot read the file" }
        if (sha != info.sha256) return "SHA-256 does not match version.json"
        return verifyPackage(ctx, apk, info.versionCode)
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(ctx: Context, apk: File, expectedBuild: Int): String? {
        val pm = ctx.packageManager
        val signingFlag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, signingFlag) ?: return "the file is not a readable APK"
        if (archive.packageName != ctx.packageName) return "package name ${archive.packageName} is not ${ctx.packageName}"
        val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        if (archiveCode != expectedBuild.toLong()) return "APK is build $archiveCode but version.json says $expectedBuild"
        if (archiveCode <= BuildConfig.VERSION_CODE) return "APK is not newer than the installed app"
        val installed = try { pm.getPackageInfo(ctx.packageName, signingFlag) } catch (_: Exception) { return "cannot read the installed app's certificate" }
        val theirs = signers(archive)
        val ours = signers(installed)
        if (!UpdateLogic.sameSigners(theirs, ours)) return "signing certificate differs from the installed app"
        return null
    }

    @Suppress("DEPRECATION")
    private fun signers(p: android.content.pm.PackageInfo): List<ByteArray> {
        if (Build.VERSION.SDK_INT >= 28) {
            val si = p.signingInfo ?: return emptyList()
            return (si.apkContentsSigners ?: emptyArray()).map { it.toByteArray() }
        }
        return (p.signatures ?: emptyArray()).map { it.toByteArray() }
    }

    fun canInstall(ctx: Context): Boolean = try { ctx.packageManager.canRequestPackageInstalls() } catch (_: Exception) { false }

    /**
     * Starts installing the verified download. Only call this from a user action. Returns false (and says
     * why in the report) if there is nothing ready or this app is not yet allowed to install apps.
     */
    fun install(ctx: Context): Boolean {
        val app = ctx.applicationContext
        val file = readyFile
        if (UpdateState.phase != UpdateState.Phase.READY || file == null || !file.exists()) return false
        if (!canInstall(app)) {
            Report.log("Update: not allowed to install apps yet (Settings -> Apps -> Security & restrictions -> Unknown sources -> StreamLink)")
            return false
        }
        val build = UpdateState.build
        UpdateState.phase = UpdateState.Phase.INSTALLING
        UpdateState.message = "Installing update (build $build)…"
        changed()
        thread(isDaemon = true, name = "updater-install") {
            val err = try { commitSession(app, file, build) } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            if (err != null) installFailed("Update install failed: $err")
        }
        return true
    }

    /** Called when an install did not happen (or the user cancelled): the update stays ready to try again. */
    fun installFailed(msg: String) {
        Report.log("Update: $msg")
        UpdateState.message = msg
        UpdateState.phase = if (readyFile?.exists() == true) UpdateState.Phase.READY else UpdateState.Phase.NONE
        changed()
    }

    private fun commitSession(ctx: Context, file: File, build: Int): String? {
        // Re-check right before installing: the file must still be exactly what was verified.
        val info = readyInfo?.takeIf { it.versionCode == build } ?: return "nothing verified to install"
        val problem = verify(ctx, file, info)
        if (problem != null) return "refused: $problem"

        val installer = ctx.packageManager.packageInstaller
        installer.mySessions.forEach { try { installer.abandonSession(it.sessionId) } catch (_: Exception) {} }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        params.setSize(file.length())
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        val id = installer.createSession(params)
        val session = installer.openSession(id)
        try {
            val md = MessageDigest.getInstance("SHA-256")
            session.openWrite("base.apk", 0, file.length()).use { out ->
                FileInputStream(file).use { ins ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
                session.fsync(out)
            }
            if (UpdateLogic.hex(md.digest()) != info.sha256) {
                session.abandon()
                return "the file changed after it was verified"
            }
            val intent = Intent(ctx, UpdateReceiver::class.java).setAction(ACTION_RESULT) // explicit, not exported
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(ctx, id, intent, flags)
            Report.log("Update: asking Android to install build $build (confirm on the TV)")
            session.commit(pending.intentSender)
            return null
        } catch (e: Exception) {
            try { session.abandon() } catch (_: Exception) {}
            throw e
        } finally {
            session.close()
        }
    }

}
