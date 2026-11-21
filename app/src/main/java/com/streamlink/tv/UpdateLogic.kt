package com.streamlink.tv

import java.io.InputStream
import java.security.MessageDigest

/** What a release's version.json says. */
internal data class VersionInfo(val versionCode: Int, val channel: String, val apk: String, val sha256: String)

/** Outcome of comparing a fetched version.json with the running app. */
internal sealed class UpdateCheck {
    object UpToDate : UpdateCheck()
    class Newer(val info: VersionInfo) : UpdateCheck()
    class Invalid(val reason: String) : UpdateCheck()
}

/** Where the update UI is. Plain data so Session.stateJson() and the unit tests don't need Android. */
internal object UpdateState {
    enum class Phase { NONE, DOWNLOADING, READY, INSTALLING }

    @Volatile var phase = Phase.NONE
    @Volatile var build = 0
    @Volatile var percent = -1
    @Volatile var checking = false
    /** Result of the last check, for the home screen ("You have the latest version", "Update check failed: …"). */
    @Volatile var message = ""

    /** The phone page's "update" field: null when there is nothing to show. */
    fun phoneJson(): Pair<Int, Boolean>? = when (phase) {
        Phase.NONE -> null
        Phase.DOWNLOADING, Phase.INSTALLING -> build to false
        Phase.READY -> build to true
    }
}

/** The pure parts of the updater (no Android APIs), unit tested on the JVM. */
internal object UpdateLogic {
    const val BASE = "https://github.com/drauch69-cmd/tv/releases/download"
    const val MAX_VERSION_JSON = 16 * 1024
    const val MAX_APK_BYTES = 80L * 1024 * 1024
    const val CHECK_EVERY_MS = 12L * 60 * 60 * 1000
    /** After a failed check (offline, release being rebuilt) try again after this long. */
    const val RETRY_AFTER_FAIL_MS = 30L * 60 * 1000

    private val HEX64 = Regex("^[0-9a-fA-F]{64}$")

    /** Release tag a channel updates from, or null for an unknown channel. */
    fun tagFor(channel: String): String? = when (channel) {
        "main" -> "latest"
        "test" -> "test-latest"
        "sports" -> "sports-latest"
        "lab" -> "lab-latest"
        else -> null
    }

    /** Name of the APK asset a channel publishes. */
    fun apkNameFor(channel: String): String? = when (channel) {
        "main" -> "StreamLink.apk"
        "test" -> "StreamLink-Test.apk"
        "sports" -> "StreamLink-Sports.apk"
        "lab" -> "StreamLink-Lab.apk"
        else -> null
    }

    fun versionUrl(channel: String): String? = tagFor(channel)?.let { "$BASE/$it/version.json" }

    fun apkUrl(channel: String): String? {
        val tag = tagFor(channel) ?: return null
        val name = apkNameFor(channel) ?: return null
        return "$BASE/$tag/$name"
    }

    /** Parses version.json; null if it is malformed or any field is missing or odd. */
    fun parseVersionJson(text: String): VersionInfo? {
        val root = try { MiniJson.parse(text) as? Map<*, *> } catch (_: Exception) { null } ?: return null
        val code = (root["versionCode"] as? Double) ?: return null
        if (code != Math.floor(code) || code < 1 || code > Int.MAX_VALUE) return null
        val channel = root["channel"] as? String ?: return null
        val apk = root["apk"] as? String ?: return null
        val sha = root["sha256"] as? String ?: return null
        if (!HEX64.matches(sha)) return null
        return VersionInfo(code.toInt(), channel, apk, sha.lowercase())
    }

    fun isNewer(remote: Int, installed: Int) = remote > installed

    /**
     * Decides what a fetched version.json means for this app. The file must describe our own channel and
     * the one APK name that channel publishes, so a stray file can never point the updater elsewhere.
     */
    fun evaluate(json: String, ownChannel: String, installed: Int): UpdateCheck {
        val expectApk = apkNameFor(ownChannel) ?: return UpdateCheck.Invalid("unknown channel $ownChannel")
        val info = parseVersionJson(json) ?: return UpdateCheck.Invalid("version.json is not valid")
        if (info.channel != ownChannel) return UpdateCheck.Invalid("version.json is for channel ${info.channel}, not $ownChannel")
        if (info.apk != expectApk) return UpdateCheck.Invalid("version.json names an unexpected file")
        return if (isNewer(info.versionCode, installed)) UpdateCheck.Newer(info) else UpdateCheck.UpToDate
    }

    /** Whether a check is due: 12 hours after the last good one, 30 minutes after a failed one. */
    fun checkDue(now: Long, lastOk: Long, lastFail: Long): Boolean {
        if (lastOk != 0L && now - lastOk < CHECK_EVERY_MS) return false
        if (lastFail != 0L && now - lastFail < RETRY_AFTER_FAIL_MS) return false
        return true
    }

    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append("0123456789abcdef"[(b.toInt() shr 4) and 15]).append("0123456789abcdef"[b.toInt() and 15])
        return sb.toString()
    }

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return hex(md.digest())
    }

    /** True when both certificate sets are non-empty and identical (compared by SHA-256 of each certificate). */
    fun sameSigners(a: Collection<ByteArray>, b: Collection<ByteArray>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        return a.map { sha256Hex(it) }.toSet() == b.map { sha256Hex(it) }.toSet()
    }
}
