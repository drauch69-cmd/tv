package com.tvcleaner

/**
 * Decides which apps this app refuses to touch. Lists from the internet can only add to this,
 * never take a protection away.
 */
object Protect {
    const val SHIZUKU = "moe.shizuku.privileged.api"

    /** Package names that are safe to put into a `pm` command. */
    val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    private val EXACT = mapOf(
        "android" to "Android itself",
        "com.android.systemui" to "Android itself",
        "com.android.shell" to "Android itself",
        "com.android.settings" to "Settings",
        "com.android.tv.settings" to "Settings",
        "com.android.vending" to "Play Store and app updates",
        "com.google.android.gms" to "Google Play services",
        "com.google.android.gsf" to "Google Play services",
        "com.google.android.webview" to "Web pages inside apps",
        "com.android.webview" to "Web pages inside apps",
        SHIZUKU to "Shizuku, which this app needs",
    )

    /** The TV can stop booting without these. */
    private val CORE = Regex(
        "com\\.(google\\.)?android\\.(permissioncontroller|packageinstaller|ext\\.services|ext\\.shared|modulemetadata)|" +
            "com\\.google\\.android\\.overlay\\.modules.*|com\\.(google\\.)?android\\.providers\\..*",
    )

    /** Data providers no TV feature needs; the tested lists turn them off. */
    private val CORE_OK = Regex("com\\.android\\.providers\\.(calendar|contacts|userdictionary|blockednumber)")

    /** Name pieces, checked in order. Makers name things loosely, so these match loosely too. */
    private val PIECES = listOf(
        "networkstack" to "Wi-Fi and network",
        "com.android.wifi" to "Wi-Fi and network",
        "com.google.android.wifi" to "Wi-Fi and network",
        "wifi.resources" to "Wi-Fi and network",
        "connectivity" to "Wi-Fi and network",
        "trichromelibrary" to "Web pages inside apps",
        "inputmethod" to "Keyboard",
        "keyboard" to "Keyboard",
        ".ime" to "Keyboard",
        "inputdevices" to "Keyboard and remote",
        "tvinput" to "TV inputs",
        "tv.input" to "TV inputs",
        "hdmi" to "TV inputs",
        "tuner" to "TV inputs",
        "bluetooth" to "Remote and Bluetooth",
        "remote" to "Remote and Bluetooth",
        "rcu" to "Remote and Bluetooth",
        "setupwizard" to "Setup",
        "setupwraith" to "Setup",
        "bootwizard" to "Setup",
        "provision" to "Setup",
    )

    /** System updaters: "update" or "upgrade" anywhere, "ota" only as a whole name part. */
    private val UPDATER = Regex("updat|upgrad|(^|\\.)(f|usb|remote|auth)?ota(\\.|$)|otaupdat")

    /**
     * Why [pkg] must stay on, or null when it may be changed.
     * [protectedHomes] are home screens that must stay; [self] is this app; [extra] comes from
     * the downloaded protected list and the chosen TV list's "keep" entries.
     */
    fun reason(
        pkg: String,
        self: String,
        protectedHomes: Set<String> = emptySet(),
        extra: Map<String, String> = emptyMap(),
    ): String? {
        if (pkg == self) return "This app"
        if (pkg in protectedHomes) return "Home screen"
        EXACT[pkg]?.let { return it }
        if (CORE.matches(pkg) && !CORE_OK.matches(pkg)) return "Android itself"
        val lower = pkg.lowercase()
        PIECES.firstOrNull { (piece, _) -> piece in lower }?.let { return it.second }
        if (UPDATER.containsMatchIn(lower)) return "System updates"
        if (!PACKAGE.matches(pkg)) return "Unusual name"
        return extra[pkg]
    }

    /**
     * Which home screens must stay on. With only built-in home screens, all of them stay. Once you
     * have installed your own (like Projectivy), yours stays and the built-in ones may be turned off.
     */
    fun protectedHomes(homes: Map<String, Boolean>): Set<String> {
        val yours = homes.filterValues { system -> !system }.keys
        return if (yours.isEmpty()) homes.keys else yours
    }
}
