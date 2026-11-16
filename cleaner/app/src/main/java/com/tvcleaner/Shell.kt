package com.tvcleaner

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.File
import java.lang.reflect.Method
import kotlin.concurrent.thread

/** Runs `pm`, `cmd` and `settings` with ADB-level rights, through Shizuku. */
object Shell {
    enum class State { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, TOO_OLD, READY }

    data class Result(val code: Int, val out: String) {
        val ok get() = code == 0
    }

    private const val PM = "/system/bin/pm"
    private const val CMD = "/system/bin/cmd"
    private val ACTIVITY = Regex("[A-Za-z][A-Za-z0-9_.]*/[A-Za-z0-9_.$]+")

    fun state(pm: PackageManager): State {
        val installed = try {
            pm.getPackageInfo(Protect.SHIZUKU, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        if (!Shizuku.pingBinder()) return if (installed) State.NOT_RUNNING else State.NOT_INSTALLED
        if (Shizuku.isPreV11()) return State.TOO_OLD
        return if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) State.READY else State.NO_PERMISSION
    }

    // Shizuku keeps newProcess private in its current API but still ships it; many tools call it this way.
    private val newProcess: Method by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }
    }

    /**
     * Runs [args] as the shell user (no shell in between, so nothing in them is interpreted).
     * [input], when given, is streamed to the command's stdin.
     */
    fun run(vararg args: String, input: File? = null): Result = try {
        val p = newProcess.invoke(null, arrayOf(*args), null, null) as Process
        val err = StringBuilder()
        // Read stderr alongside stdout so neither pipe can fill up and stall the command.
        val errReader = thread(isDaemon = true) { err.append(p.errorStream.bufferedReader().readText()) }
        val writer = thread(isDaemon = true) {
            try {
                p.outputStream.use { out -> input?.inputStream()?.use { it.copyTo(out) } }
            } catch (_: Exception) {
                // The command ended early; its exit code says why.
            }
        }
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        writer.join(5000)
        errReader.join(5000)
        Result(code, (out + "\n" + err).trim())
    } catch (e: Exception) {
        val cause = e.cause ?: e
        Result(-1, cause.javaClass.simpleName + ": " + (cause.message ?: ""))
    }

    fun disable(pkg: String) = checked(pkg) { run(PM, "disable-user", "--user", "0", pkg) }

    fun enable(pkg: String) = checked(pkg) { run(PM, "enable", "--user", "0", pkg) }

    /** Brings back an app that was uninstalled for this user (by Canta, for example). */
    fun restore(pkg: String) = checked(pkg) {
        val r = run(CMD, "package", "install-existing", "--user", "0", pkg)
        if (r.ok) run(PM, "enable", "--user", "0", pkg) else r
    }

    /** Installs an APK this app has already checked. "Success" in the output is the only proof. */
    fun install(apk: File): Result {
        val r = run(PM, "install", "-S", apk.length().toString(), input = apk)
        return if (r.ok && r.out.contains("Success")) r else r.copy(code = if (r.ok) 1 else r.code)
    }

    /** Makes "package/class" the default home screen. */
    fun setHome(activity: String): Result = if (ACTIVITY.matches(activity)) {
        run(CMD, "package", "set-home-activity", "--user", "0", activity)
    } else {
        Result(-1, "Refused: unusual name")
    }

    private fun checked(pkg: String, block: () -> Result): Result =
        if (Protect.PACKAGE.matches(pkg)) block() else Result(-1, "Refused: unusual package name")
}
