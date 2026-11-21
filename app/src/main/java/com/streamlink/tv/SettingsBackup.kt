package com.streamlink.tv

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup and restore of everything the user set up: all settings files (lists, ranks, habits, site memory,
 * photo framing...) plus the sport and city photos and saved game matches. Downloaded from and uploaded to the
 * phone page. Works across the app's channels (Sports -> Lab): settings file names are the same in every build.
 *
 * Restore never writes into live settings: the zip is unpacked into a pending folder, the app restarts, and
 * [applyPending] swaps it in at start-up before anything reads a setting.
 */
object SettingsBackup {
    const val MAX_BYTES = 60 * 1024 * 1024
    private const val PENDING = "restore-pending"
    private const val READY = ".ready"

    /** The zip for the phone to download. */
    fun export(ctx: Context): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val info = JSONObject()
                .put("app", ctx.packageName)
                .put("channel", BuildConfig.CHANNEL)
                .put("version", BuildConfig.VERSION_NAME)
                .put("made", System.currentTimeMillis())
            zip.putNextEntry(ZipEntry(SettingsBackupRules.INFO))
            zip.write(info.toString().toByteArray())
            zip.closeEntry()
            prefsDir(ctx).listFiles()?.filter { it.isFile && it.name.endsWith(".xml") }?.forEach {
                add(zip, it, "shared_prefs/" + it.name)
            }
            for (name in SettingsBackupRules.FILES) {
                val f = File(ctx.filesDir, name)
                if (f.isDirectory) f.walkTopDown().filter { it.isFile }.forEach {
                    add(zip, it, "files/" + it.relativeTo(ctx.filesDir).invariantSeparatorsPath)
                } else if (f.isFile) add(zip, f, "files/$name")
            }
        }
        return out.toByteArray()
    }

    fun fileName(): String =
        "streamlink-backup-" + BuildConfig.CHANNEL + "-" +
            java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".zip"

    /** Checks and unpacks an uploaded backup into the pending folder. Null on success, else why it was refused. */
    fun stage(ctx: Context, bytes: ByteArray): String? {
        val pending = File(ctx.filesDir, PENDING)
        pending.deleteRecursively()
        var total = 0L
        var count = 0
        var sawInfo = false
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    if (e.name == SettingsBackupRules.INFO) { sawInfo = true; continue }
                    if (!SettingsBackupRules.safeEntry(e.name)) return fail(pending, "That file has something in it that isn't a StreamLink setting")
                    if (++count > SettingsBackupRules.MAX_ENTRIES) return fail(pending, "That backup has too many files")
                    val dest = File(pending, e.name)
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { o ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > SettingsBackupRules.MAX_UNPACKED) return fail(pending, "That backup is too big")
                            o.write(buf, 0, n)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            return fail(pending, "That isn't a StreamLink backup file")
        }
        if (!sawInfo || count == 0) return fail(pending, "That isn't a StreamLink backup file")
        File(pending, READY).writeText("1")
        return null
    }

    private fun fail(pending: File, why: String): String { pending.deleteRecursively(); return why }

    /** At start-up, before any setting is read: swaps in a staged backup. Returns true if one was applied. */
    fun applyPending(ctx: Context): Boolean {
        val pending = File(ctx.filesDir, PENDING)
        if (!File(pending, READY).isFile) { if (pending.exists()) pending.deleteRecursively(); return false }
        return try {
            val prefs = prefsDir(ctx)
            prefs.listFiles()?.forEach { if (it.name.endsWith(".xml") || it.name.endsWith(".bak")) it.delete() }
            for (name in SettingsBackupRules.FILES) File(ctx.filesDir, name).deleteRecursively()
            File(pending, "shared_prefs").listFiles()?.forEach { src ->
                prefs.mkdirs(); src.copyTo(File(prefs, src.name), overwrite = true)
            }
            File(pending, "files").takeIf { it.isDirectory }?.let { it.copyRecursively(ctx.filesDir, overwrite = true) }
            true
        } catch (_: Exception) {
            false
        } finally {
            pending.deleteRecursively()
        }
    }

    private fun prefsDir(ctx: Context) = File(ctx.applicationInfo.dataDir, "shared_prefs")

    private fun add(zip: ZipOutputStream, f: File, name: String) {
        zip.putNextEntry(ZipEntry(name))
        f.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }
}

/** Pure rules for [SettingsBackup] (unit-tested). */
object SettingsBackupRules {
    const val INFO = "backup.json"
    const val MAX_ENTRIES = 3000
    const val MAX_UNPACKED = 120L * 1024 * 1024
    /** What's kept besides the settings files: photos the user picked or framed, and saved game matches. Not logs or caches. */
    val FILES = listOf("sport-photos", "city-photos", "match-answers.json")

    /** Only settings files and the listed data, with no way to climb out of the folder. */
    fun safeEntry(name: String): Boolean {
        if (name.isEmpty() || name.startsWith("/") || name.contains('\\')) return false
        val parts = name.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return false
        return when (parts[0]) {
            "shared_prefs" -> parts.size == 2 && parts[1].endsWith(".xml")
            "files" -> parts.size >= 2 && parts[1] in FILES && (parts.size > 2 || parts[1].endsWith(".json"))
            else -> false
        }
    }
}
