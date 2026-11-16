package com.tvcleaner

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Date
import java.util.concurrent.Executors
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private enum class Page { SETUP, SUGGESTED, HOME, ALL, HISTORY }

    private enum class Filter { BUILT_IN, YOURS, OFF }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val history by lazy { History(this) }

    // What the background load found.
    private var loaded = false
    private var index: ListIndex? = null
    private var labels: Map<String, String> = emptyMap()
    private var listKeeps: Map<String, String> = emptyMap()
    private var warnings: Map<String, String> = emptyMap()
    private var apps: Map<String, TvApp> = emptyMap()
    private var homes: Map<String, Home> = emptyMap()
    private var currentHome: String? = null
    private var overrides: Set<String> = emptySet()
    private var entry: ListEntry? = null
    private var list: TvList? = null
    private var listFailed = false

    private var page = Page.SETUP
    private var filter = Filter.BUILT_IN
    private val selected = mutableSetOf<String>()
    private var selectionFor: String? = null
    private var busy = false
    private var applyButton: Button? = null

    private lateinit var status: TextView
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private val navButtons = mutableMapOf<Page, Button>()

    private val onBinder = Shizuku.OnBinderReceivedListener { main.post { onShizukuChanged() } }
    private val onBinderDead = Shizuku.OnBinderDeadListener { main.post { onShizukuChanged() } }
    private val onPermission = Shizuku.OnRequestPermissionResultListener { _, _ -> main.post { onShizukuChanged() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        Shizuku.addBinderReceivedListenerSticky(onBinder)
        Shizuku.addBinderDeadListener(onBinderDead)
        Shizuku.addRequestPermissionResultListener(onPermission)
        load()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from Shizuku, a launcher or Settings: things may have changed.
        if (loaded && !busy) reloadApps()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(onBinder)
        Shizuku.removeBinderDeadListener(onBinderDead)
        Shizuku.removeRequestPermissionResultListener(onPermission)
        io.shutdown()
        super.onDestroy()
    }

    private val gone get() = isFinishing || isDestroyed

    private fun shellState() = try {
        Shell.state(packageManager)
    } catch (_: Exception) {
        Shell.State.NOT_RUNNING
    }

    private fun onShizukuChanged() {
        if (gone) return
        if (shellState() == Shell.State.READY && page == Page.SETUP) page = Page.SUGGESTED
        reloadApps()
    }

    /** Runs [work] off the main thread and hands its result back, unless the screen has closed. */
    private fun <T> background(work: () -> T, then: (T) -> Unit) {
        if (io.isShutdown) return
        io.execute {
            val result = work()
            main.post { if (!gone) then(result) }
        }
    }

    // ---------- Loading ----------

    private class Loaded(
        val index: ListIndex?,
        val labels: Map<String, String>,
        val protected: Pair<Map<String, String>, Map<String, String>>?,
        val entry: ListEntry?,
        val list: TvList?,
        val device: Device,
    )

    /** What changes on the TV itself: its apps, home screens and accessibility services. */
    private class Device(val apps: Map<String, TvApp>, val homes: Map<String, Home>, val current: String?, val overrides: Set<String>)

    private fun readDevice(lbl: Map<String, String>): Device {
        val services = if (shellState() == Shell.State.READY) Launchers.enabledServices() else emptyList()
        val overrideOn = Launchers.ALL.filter { l ->
            Launchers.overrideService(this, l.pkg)?.let { s -> services.any { it.equals(s, ignoreCase = true) } } == true
        }.map { it.pkg }.toSet()
        return Device(Apps.load(this, lbl), Apps.homes(this), Apps.currentHome(this), overrideOn)
    }

    private fun useDevice(d: Device) {
        apps = d.apps
        homes = d.homes
        currentHome = d.current
        overrides = d.overrides
    }

    private fun load() {
        status.text = "Loading…"
        background({
            val f = Fetcher(this)
            val idx = f.get("index.json")?.let(Lists::parseIndex)
            val lbl = idx?.files?.get("labels")?.let { f.get(it.first, it.second) }?.let(Lists::parseLabels).orEmpty()
            val prot = idx?.files?.get("protected")?.let { f.get(it.first, it.second) }?.let(Lists::parseProtected)
            val savedId = prefs.getString("list_id", null)
            val e = idx?.devices?.firstOrNull { it.id == savedId } ?: idx?.let { Lists.bestMatch(it.devices, Apps.tvInfo()) }
            val l = e?.let { f.get(it.file, it.sha256) }?.let(Lists::parseList)
            Loaded(idx, lbl, prot, e, l, readDevice(lbl))
        }) { r ->
            index = r.index
            labels = r.labels
            listKeeps = r.protected?.first.orEmpty()
            warnings = r.protected?.second.orEmpty()
            entry = r.entry
            list = r.list
            listFailed = r.entry != null && r.list == null
            useDevice(r.device)
            loaded = true
            if (shellState() == Shell.State.READY) page = Page.SUGGESTED
            render()
        }
    }

    /** Re-reads the TV after a change. */
    private fun reloadApps() {
        if (!loaded) return
        val lbl = labels
        background({ readDevice(lbl) }) { d ->
            useDevice(d)
            render()
        }
    }

    private fun pickList(e: ListEntry) {
        prefs.edit().putString("list_id", e.id).apply()
        selectionFor = null
        status.text = "Loading the list for ${e.name}…"
        background({ Fetcher(this).get(e.file, e.sha256)?.let(Lists::parseList) }) { l ->
            entry = e
            list = l
            listFailed = l == null
            page = Page.SUGGESTED
            render()
        }
    }

    private fun protectedReason(pkg: String): String? {
        val keeps = listKeeps + (list?.keep.orEmpty().associateWith { "This TV's list says keep it" })
        return Protect.reason(pkg, packageName, Protect.protectedHomes(homes.keys), keeps)
    }

    // ---------- Layout ----------

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG)
            setPadding(dp(32), dp(24), dp(32), dp(24))
        }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nav.addView(text("TV Cleaner", 28f, bold = true).apply { setPadding(dp(8), 0, 0, dp(20)) })
        listOf(
            Page.SETUP to "Setup",
            Page.SUGGESTED to "Suggested cleanup",
            Page.HOME to "Home screen",
            Page.ALL to "All apps",
            Page.HISTORY to "History / undo",
        ).forEach { (p, label) ->
            val b = button(label) {
                page = p
                render()
            }
            navButtons[p] = b
            nav.addView(b, params(MATCH, WRAP).apply { bottomMargin = dp(10) })
        }
        root.addView(nav, params(dp(240), MATCH))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), 0, 0, 0)
        }
        status = text("", 16f, color = DIM).apply { setPadding(0, 0, 0, dp(12)) }
        right.addView(status)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        right.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(right, LinearLayout.LayoutParams(0, MATCH, 1f))
        return root
    }

    private fun render() {
        if (!loaded || gone) return
        val state = shellState()
        val tv = Apps.tvInfo()
        status.text = "${tv.manufacturer} ${tv.model} · Android SDK ${tv.sdk} · " +
            if (state == Shell.State.READY) "Shizuku connected" else "Shizuku not connected"
        navButtons.forEach { (p, b) -> b.isSelected = p == page }
        val hadFocus = content.findFocus() != null
        content.removeAllViews()
        applyButton = null
        when (page) {
            Page.SETUP -> renderSetup(state)
            Page.SUGGESTED -> renderSuggested()
            Page.HOME -> renderHome()
            Page.ALL -> renderAll()
            Page.HISTORY -> renderHistory()
        }
        scroll.scrollTo(0, 0)
        // Rebuilt under the remote's focus: put it back on the page instead of losing it.
        if (hadFocus) content.requestFocus()
    }

    // ---------- Pages ----------

    private fun renderSetup(state: Shell.State) {
        heading("Setup")
        when (state) {
            Shell.State.NOT_INSTALLED -> {
                para("TV Cleaner needs Shizuku to make changes. Install the TV-friendly version first:")
                para("1. Open Downloader and go to github.com/thedjchi/Shizuku/releases\n2. Install the latest APK.\n3. Come back here.")
                action("Check again") { render() }
            }
            Shell.State.NOT_RUNNING -> {
                para("Shizuku is installed but not running.")
                para(
                    "1. Settings → System → Developer options → turn on Wireless debugging.\n" +
                        "2. Open Shizuku and choose Start via Wireless debugging (pair first if it asks).\n3. Come back here.",
                )
                action("Open Shizuku") { openApp(Protect.SHIZUKU) }
                action("Check again") { render() }
            }
            Shell.State.NO_PERMISSION -> {
                para("Shizuku is running. Allow TV Cleaner to use it:")
                action("Allow TV Cleaner in Shizuku") {
                    try {
                        Shizuku.requestPermission(1)
                    } catch (e: Exception) {
                        message("Couldn't ask Shizuku", e.message ?: "Open Shizuku and allow TV Cleaner there.")
                    }
                }
            }
            Shell.State.TOO_OLD -> para("This Shizuku is too old. Install the latest from github.com/thedjchi/Shizuku/releases.")
            Shell.State.READY -> {
                para("All set. TV Cleaner can turn apps off and back on, and install a home screen.")
                action("Go to suggested cleanup") {
                    page = Page.SUGGESTED
                    render()
                }
            }
        }
        para("\nAfter the TV restarts, Shizuku may need starting again (unless you turned on its start-on-boot option).", DIM)
    }

    private fun renderSuggested() {
        heading("Suggested cleanup")
        val idx = index
        if (idx == null) {
            para("Couldn't download the debloat lists. Check the TV's internet connection.")
            action("Try again") { load() }
            return
        }
        val e = entry
        val l = list
        if (e == null) {
            para("No list was found automatically for this TV. Pick yours (or the closest model from the same maker).")
            action("Pick your TV") { showPicker() }
            return
        }
        if (l == null || listFailed) {
            para("Couldn't load the list for ${e.name}.")
            action("Try again") { pickList(e) }
            action("Pick another TV") { showPicker() }
            return
        }

        val sdkMatches = l.sdk != null && l.sdk == Build.VERSION.SDK_INT
        para("List: ${l.name}" + (l.testedOs?.let { " · tested on $it" } ?: ""))
        if (!sdkMatches) {
            para(
                "This list was tested on a different Android version than your TV, so nothing is pre-ticked. " +
                    "Tick only what you recognise.",
                WARN,
            )
        }
        action("Not your TV? Pick another") { showPicker() }

        if (selectionFor != l.id) {
            selectionFor = l.id
            selected.clear()
            if (sdkMatches) {
                l.groups.flatMap { it.apps }.filter { !it.optional && changeable(it.pkg) }.forEach { selected += it.pkg }
            }
        }

        var notOnTv = 0
        l.groups.forEach { g ->
            val rows = g.apps.filter { a -> (a.pkg in apps).also { if (!it) notOnTv++ } }
            if (rows.isEmpty()) return@forEach
            sub(g.title)
            rows.forEach { a ->
                val app = apps.getValue(a.pkg)
                val why = protectedReason(a.pkg)
                val warning = warnings[a.pkg]?.let { "⚠ $it" }
                val detail = listOfNotNull(a.pkg, a.note, warning, if (a.optional) "optional" else null).joinToString(" · ")
                when {
                    why != null -> infoRow(app.label, "Kept: $why · $detail")
                    !app.installed || !app.enabled -> infoRow(app.label, "Already off · $detail")
                    else -> checkRow(app.label, detail, a.pkg in selected) { on ->
                        if (on) selected += a.pkg else selected -= a.pkg
                        updateApplyButton()
                    }
                }
            }
        }
        if (notOnTv > 0) para("$notOnTv app(s) on this list aren't on your TV.", DIM)
        applyButton = action("") { applySelected() }
        updateApplyButton()
    }

    private fun updateApplyButton() {
        val n = selected.count { changeable(it) }
        applyButton?.text = if (n == 0) "Nothing selected" else "Turn off $n selected app(s)"
        applyButton?.isEnabled = n > 0
    }

    private fun changeable(pkg: String): Boolean {
        val app = apps[pkg] ?: return false
        return app.installed && app.enabled && protectedReason(pkg) == null
    }

    private fun applySelected() {
        val todo = selected.filter { changeable(it) }.map { apps.getValue(it) }
        if (todo.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Turn off ${todo.size} app(s)?")
            .setMessage(todo.joinToString("\n") { "• ${it.label}" } + "\n\nYou can turn any of them back on from History / undo.")
            .setPositiveButton("Turn off") { _, _ -> perform(todo.map { Triple(it.pkg, it.label, "disable") }) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderHome() {
        heading("Home screen")
        val now = currentHome
        val nowName = when {
            now == null -> "Not set (Android asks)"
            else -> apps[now]?.label ?: now
        }
        para("Home opens: $nowName" + if (overrides.isNotEmpty()) " (overridden by ${overrides.joinToString { name(it) }})" else "")
        para(
            "On Google TV the built-in home screen keeps taking the Home button back. Projectivy and AT4K get around " +
                "that with an \"override\" service, which TV Cleaner turns on for you. The built-in home screen stays installed.",
            DIM,
        )
        Launchers.ALL.forEach { l ->
            val app = apps[l.pkg]
            val state = when {
                app == null || !app.installed -> "Not installed"
                l.pkg in overrides || l.pkg == now -> "Your home screen"
                else -> "Installed"
            }
            val extra = if (l.canOverride) "" else " · may not stay as Home on Google TV"
            actionRow(l.name, "$state · ${l.about}$extra") { launcherDialog(l) }
        }
        val custom = overrides.isNotEmpty() || (now != null && homes[now]?.system == false)
        if (custom) {
            sub("Undo")
            action("Back to the built-in home screen") { backToStock() }
        }
    }

    private fun name(pkg: String) = Launchers.ALL.firstOrNull { it.pkg == pkg }?.name ?: apps[pkg]?.label ?: pkg

    private fun launcherDialog(l: Launcher) {
        val app = apps[l.pkg]
        val installed = app != null && app.installed
        val isHome = l.pkg in overrides || l.pkg == currentHome
        val b = AlertDialog.Builder(this).setTitle(l.name).setNegativeButton("Cancel", null)
        when {
            isHome -> b.setMessage("${l.name} is your home screen.").setPositiveButton("Open it") { _, _ -> openApp(l.pkg) }
            installed -> b.setMessage("Make ${l.name} the home screen?").setPositiveButton("Make it Home") { _, _ -> makeHome(l) }
            else -> b.setMessage(
                "Download ${l.name} from its developer's GitHub page, check its signature, install it and make it the home screen?",
            ).setPositiveButton("Install") { _, _ -> makeHome(l) }
        }
        b.show()
    }

    private fun makeHome(l: Launcher) {
        task("Setting up ${l.name}") { progress ->
            val steps = mutableListOf<String>()
            val installedNow = packageManager.isInstalled(l.pkg)
            if (!installedNow) {
                progress("Downloading ${l.name}…")
                val apk = try {
                    Launchers.download(this, l) { pct -> if (pct >= 0) progress("Downloading ${l.name}… $pct%") }
                } catch (e: Exception) {
                    return@task "Couldn't download ${l.name}: ${e.message}"
                }
                progress("Installing ${l.name}…")
                val r = Shell.install(apk)
                apk.delete()
                history.add(Change(l.pkg, l.name, "install", System.currentTimeMillis(), r.ok))
                if (!r.ok) return@task "Couldn't install ${l.name}: ${r.out.take(200)}"
                steps += "Installed ${l.name}."
            }
            progress("Making ${l.name} the home screen…")
            val home = Apps.homes(this)[l.pkg]
            val setHome = home?.let { Shell.setHome(it.activity) }
            if (setHome?.ok == true) steps += "Set ${l.name} as the default home screen."
            var overrideOk = false
            if (l.canOverride) {
                val service = Launchers.overrideService(this, l.pkg)
                if (service != null) {
                    overrideOk = Launchers.setService(service, true).ok
                    if (overrideOk) steps += "Turned on ${l.name}'s Home button override."
                }
            }
            val ok = setHome?.ok == true || overrideOk
            history.add(Change(l.pkg, l.name, "home", System.currentTimeMillis(), ok))
            steps += if (ok) {
                "\nPress Home on the remote to check. If the built-in home screen still opens, open ${l.name}'s settings " +
                    "and turn on its \"override current launcher\" option."
            } else {
                "\nCouldn't make it the home screen automatically. " +
                    "Open ${l.name}'s settings and turn on its \"override current launcher\" option."
            }
            steps.joinToString("\n")
        }
    }

    private fun backToStock() {
        task("Going back to the built-in home screen") { progress ->
            progress("Turning off launcher overrides…")
            val off = mutableListOf<String>()
            Launchers.ALL.filter { it.canOverride }.forEach { l ->
                val service = Launchers.overrideService(this, l.pkg) ?: return@forEach
                if (Launchers.enabledServices().any { it.equals(service, ignoreCase = true) }) {
                    if (Launchers.setService(service, false).ok) off += l.name
                }
            }
            val stock = Apps.stockHome(Apps.homes(this))
            val r = stock?.let { Shell.setHome(it.value.activity) }
            val label = stock?.let { apps[it.key]?.label ?: it.key } ?: "built-in home"
            history.add(Change(stock?.key ?: "android", label, "stockhome", System.currentTimeMillis(), r?.ok == true || off.isNotEmpty()))
            buildString {
                if (off.isNotEmpty()) append("Turned off the override for ${off.joinToString()}.\n")
                if (r?.ok == true) {
                    append("$label is the home screen again.")
                } else {
                    append("Couldn't set $label as the home screen: ${r?.out ?: "not found"}")
                }
            }
        }
    }

    private fun renderAll() {
        heading("All apps")
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(Filter.BUILT_IN to "Built-in", Filter.YOURS to "Installed by you", Filter.OFF to "Turned off").forEach { (f, label) ->
            bar.addView(
                button(label) {
                    filter = f
                    render()
                }.apply { isSelected = filter == f },
                params(WRAP, WRAP).apply { rightMargin = dp(10) },
            )
        }
        content.addView(bar, params(MATCH, WRAP).apply { bottomMargin = dp(12) })

        val shown = apps.values.filter {
            when (filter) {
                Filter.BUILT_IN -> it.system && it.installed && it.enabled
                Filter.YOURS -> !it.system
                Filter.OFF -> !it.installed || !it.enabled
            }
        }.sortedBy { it.label.lowercase() }
        if (shown.isEmpty()) para("Nothing here.", DIM)
        shown.forEach { app ->
            val why = protectedReason(app.pkg)
            val state = when {
                !app.installed -> "Uninstalled (can be restored)"
                !app.enabled -> "Off"
                else -> "On"
            }
            val detail = listOfNotNull(state, why?.let { "Kept: $it" }, app.pkg, warnings[app.pkg]?.let { "⚠ $it" }).joinToString(" · ")
            actionRow(app.label, detail) { appDialog(app) }
        }
    }

    private fun appDialog(app: TvApp) {
        val why = protectedReason(app.pkg)
        val b = AlertDialog.Builder(this).setTitle(app.label)
        val msg = StringBuilder(app.pkg)
        warnings[app.pkg]?.let { msg.append("\n\n⚠ ").append(it) }
        when {
            why != null && app.installed && app.enabled -> {
                msg.append("\n\nTV Cleaner won't change this one: $why.")
                b.setPositiveButton("OK", null)
            }
            !app.installed -> b.setPositiveButton("Restore") { _, _ -> perform(listOf(Triple(app.pkg, app.label, "restore"))) }
            !app.enabled -> b.setPositiveButton("Turn on") { _, _ -> perform(listOf(Triple(app.pkg, app.label, "enable"))) }
            else -> b.setPositiveButton("Turn off") { _, _ -> perform(listOf(Triple(app.pkg, app.label, "disable"))) }
        }
        b.setNegativeButton("Cancel", null).setMessage(msg).show()
    }

    private fun renderHistory() {
        heading("History / undo")
        val off = history.turnedOff().filter { apps[it]?.let { a -> !a.enabled || !a.installed } == true }
        if (off.isNotEmpty()) {
            action("Turn everything back on (${off.size})") {
                AlertDialog.Builder(this)
                    .setTitle("Turn ${off.size} app(s) back on?")
                    .setPositiveButton("Turn on") { _, _ ->
                        perform(
                            off.map { pkg ->
                                val a = apps.getValue(pkg)
                                Triple(a.pkg, a.label, if (a.installed) "enable" else "restore")
                            },
                        )
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        val all = history.all()
        if (all.isEmpty()) para("No changes yet.", DIM)
        all.forEach { c ->
            val verb = when (c.action) {
                "disable" -> "Turned off"
                "enable" -> "Turned on"
                "restore" -> "Restored"
                "install" -> "Installed"
                "home" -> "Made home screen"
                "stockhome" -> "Back to built-in home screen"
                else -> c.action
            }
            val whenText = DateFormat.format("MMM d, h:mm a", Date(c.time))
            val detail = "$verb · $whenText · ${c.pkg}" + if (c.ok) "" else " · FAILED"
            val app = apps[c.pkg]
            if (app == null || c.action in setOf("install", "home", "stockhome")) {
                infoRow(c.label, detail)
            } else {
                actionRow(c.label, detail) { appDialog(app) }
            }
        }
    }

    // ---------- Doing things ----------

    /**
     * Shows a progress box while [work] runs off the main thread, then its message. [work] gets a
     * function to update the progress text and returns the message to show.
     */
    private fun task(title: String, work: ((String) -> Unit) -> String) {
        if (shellState() != Shell.State.READY) {
            page = Page.SETUP
            render()
            message("Shizuku isn't connected", "Start Shizuku again, then try once more.")
            return
        }
        busy = true
        val progress = AlertDialog.Builder(this).setTitle(title).setMessage("Starting…").setCancelable(false).show()
        background({
            try {
                work { text -> main.post { if (!gone && progress.isShowing) progress.setMessage(text) } }
            } catch (e: Exception) {
                "Something went wrong: ${e.message}"
            }
        }) { result ->
            busy = false
            if (progress.isShowing) progress.dismiss()
            message(title, result)
            reloadApps()
        }
    }

    /** Runs each (package, label, action) in turn, saving every result to History as it goes. */
    private fun perform(todo: List<Triple<String, String, String>>) {
        task("Working") { progress ->
            val failed = mutableListOf<String>()
            todo.forEachIndexed { i, (pkg, label, act) ->
                progress("${i + 1} of ${todo.size}: $label")
                val r = when (act) {
                    "disable" -> Shell.disable(pkg)
                    "enable" -> Shell.enable(pkg)
                    else -> Shell.restore(pkg)
                }
                val ok = r.ok && isNow(pkg, act)
                history.add(Change(pkg, label, act, System.currentTimeMillis(), ok))
                if (!ok) failed += "$label: ${r.out.ifBlank { "no change" }.take(120)}"
            }
            val done = todo.size - failed.size
            "$done of ${todo.size} changed." + if (failed.isEmpty()) "" else "\n\nProblems:\n" + failed.joinToString("\n")
        }
    }

    /** Checks the TV really made the change. */
    private fun isNow(pkg: String, act: String): Boolean = try {
        val s = packageManager.getApplicationEnabledSetting(pkg)
        val off = s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER || s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (act == "disable") off else !off
    } catch (_: Exception) {
        false
    }

    private fun showPicker() {
        val idx = index ?: return
        val tv = Apps.tvInfo()
        val maker = Lists.byMaker(idx.devices, tv)
        val choices = maker.ifEmpty { idx.devices }
        val names = choices.map { it.name }.toMutableList()
        if (maker.isNotEmpty()) names += "Show every TV (${idx.devices.size})…"
        AlertDialog.Builder(this)
            .setTitle(if (maker.isEmpty()) "Pick your TV" else "Pick your ${tv.manufacturer} model")
            .setItems(names.toTypedArray()) { _, which ->
                if (which < choices.size) {
                    pickList(choices[which])
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Pick your TV")
                        .setItems(idx.devices.map { it.name }.toTypedArray()) { _, w -> pickList(idx.devices[w]) }
                        .show()
                }
            }
            .show()
    }

    private fun openApp(pkg: String) {
        val intent = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            message("Not found", "That app isn't installed.")
        }
    }

    private fun message(title: String, msg: String) {
        if (gone) return
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }

    // ---------- View helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun params(width: Int, height: Int) = LinearLayout.LayoutParams(width, height)

    private fun text(s: String, size: Float, color: Int = FG, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun heading(s: String) = content.addView(text(s, 26f, bold = true).apply { setPadding(0, 0, 0, dp(12)) })

    private fun sub(s: String) = content.addView(text(s, 20f, color = ACCENT, bold = true).apply { setPadding(0, dp(18), 0, dp(6)) })

    private fun para(s: String, color: Int = FG) = content.addView(text(s, 18f, color).apply { setPadding(0, dp(4), 0, dp(8)) })

    private fun focusBackground(): StateListDrawable {
        fun shape(fill: Int, stroke: Int) = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(fill)
            setStroke(dp(3), stroke)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(FOCUS_FILL, Color.WHITE))
            addState(intArrayOf(android.R.attr.state_selected), shape(SELECTED_FILL, SELECTED_FILL))
            addState(intArrayOf(), shape(CARD, CARD))
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 18f
        setTextColor(FG)
        background = focusBackground()
        setPadding(dp(18), dp(12), dp(18), dp(12))
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setOnClickListener { onClick() }
    }

    private fun action(label: String, onClick: () -> Unit): Button {
        val b = button(label, onClick)
        content.addView(
            b,
            params(WRAP, WRAP).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            },
        )
        return b
    }

    private fun rowShell(focusable: Boolean): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = focusBackground()
        isFocusable = focusable
        isClickable = focusable
    }

    private fun texts(title: String, detail: String, dim: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(title, 18f, color = if (dim) DIM else FG))
        addView(text(detail, 14f, color = DIM))
    }

    private fun addRow(row: View) {
        content.addView(row, params(MATCH, WRAP).apply { bottomMargin = dp(6) })
    }

    private fun checkRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = rowShell(true)
        val box = CheckBox(this).apply {
            isChecked = checked
            isFocusable = false
            isClickable = false
        }
        row.addView(box)
        row.addView(texts(title, detail), LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(10) })
        row.setOnClickListener {
            box.isChecked = !box.isChecked
            onChange(box.isChecked)
        }
        addRow(row)
    }

    private fun actionRow(title: String, detail: String, onClick: () -> Unit) {
        val row = rowShell(true)
        row.addView(texts(title, detail), LinearLayout.LayoutParams(0, WRAP, 1f))
        row.setOnClickListener { onClick() }
        addRow(row)
    }

    private fun infoRow(title: String, detail: String) {
        val row = rowShell(false)
        row.addView(texts(title, detail, dim = true), LinearLayout.LayoutParams(0, WRAP, 1f))
        addRow(row)
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        val BG = Color.rgb(14, 18, 28)
        val CARD = Color.rgb(28, 36, 54)
        val FOCUS_FILL = Color.rgb(46, 92, 190)
        val SELECTED_FILL = Color.rgb(40, 56, 90)
        val FG = Color.WHITE
        val DIM = Color.rgb(160, 172, 196)
        val ACCENT = Color.rgb(120, 170, 255)
        val WARN = Color.rgb(255, 200, 90)
    }
}

/** True when [pkg] is installed for this user. */
private fun PackageManager.isInstalled(pkg: String): Boolean = try {
    getApplicationInfo(pkg, 0)
    true
} catch (_: PackageManager.NameNotFoundException) {
    false
}
