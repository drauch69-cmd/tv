package com.tvcleaner

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.lang.reflect.Method

/** Runs `pm` commands with ADB-level rights, through Shizuku. */
object Shell {
    enum class State { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, TOO_OLD, READY }

    data class Result(val code: Int, val out: String) {
        val ok get() = code == 0
    }

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

    /** Runs [args] as the shell user. Never pass text that didn't come from this app. */
    fun run(vararg args: String): Result = try {
        val p = newProcess.invoke(null, arrayOf(*args), null, null) as Process
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        val code = p.waitFor()
        Result(code, (out + err).trim())
    } catch (e: Exception) {
        val cause = (e.cause ?: e)
        Result(-1, cause.javaClass.simpleName + ": " + (cause.message ?: ""))
    }

    fun disable(pkg: String) = checked(pkg) { run("/system/bin/pm", "disable-user", "--user", "0", pkg) }

    fun enable(pkg: String) = checked(pkg) { run("/system/bin/pm", "enable", "--user", "0", pkg) }

    /** Brings back an app that was uninstalled for this user (by Canta, for example). */
    fun restore(pkg: String) = checked(pkg) {
        val r = run("/system/bin/cmd", "package", "install-existing", "--user", "0", pkg)
        if (r.ok) run("/system/bin/pm", "enable", "--user", "0", pkg) else r
    }

    private fun checked(pkg: String, block: () -> Result): Result =
        if (Protect.PACKAGE.matches(pkg)) block() else Result(-1, "Refused: unusual package name")
}
