package com.streamlink.tv

import android.net.Uri

/**
 * Rules that keep the hidden page loader from being hijacked.
 *
 * - [isBlockedHost]: known ad, popup and redirect networks. Requests to them are dropped.
 * - [sameSite]: a page may only navigate itself within the site you sent.
 */
object AdBlock {

    private val blocked = listOf(
        // Popup / popunder / redirect networks
        "popads.net", "popcash.net", "propellerads.com", "propellerclick.com", "onclickads.net",
        "onclkds.com", "adsterra.com", "adsterratech.com", "hilltopads.net", "hilltopads.com",
        "clickadu.com", "adcash.com", "admaven.com", "ad-maven.com", "admaven.net",
        "exoclick.com", "exosrv.com", "juicyads.com", "trafficjunky.net", "trafficjunky.com",
        "trafficstars.com", "tsyndicate.com", "richpush.co", "pushame.com", "evadav.com",
        "galaksion.com", "monetag.com", "a-ads.com", "adspyglass.com", "bidvertiser.com",
        "zeropark.com", "clickaine.com", "mobiyield.com", "realsrv.com", "pemsrv.com",
        "magsrv.com", "displayvertising.com", "highperformanceformat.com",
        "profitablegatecpm.com", "profitabledisplaynetwork.com", "acint.net",
        "shorte.st", "adf.ly", "linkvertise.com", "ouo.io",
        // Widget / banner networks
        "mgid.com", "taboola.com", "outbrain.com", "revcontent.com", "adnxs.com",
        "doubleclick.net", "googlesyndication.com", "adservice.google.com",
        "amazon-adsystem.com", "criteo.com", "pubmatic.com", "rubiconproject.com",
        "openx.net", "33across.com", "yieldmo.com",
        // Ad/tracker frames (useless as a stream's referer).
        // Not adsco.re (AdScore): players use its bot check as a pass; blocked = "authorization failed" (2026-11-21).
        "lijit.com", "sovrn.com", "crwdcntrl.net", "yieldz.gs", "sharethis.com", "adsafeprotected.com",
        "moatads.com", "scorecardresearch.com", "quantserve.com", "casalemedia.com", "bidswitch.net",
    )

    /**
     * Video ad servers that play pre-roll ads inside a site's own player. Allowed in the site
     * player (so pre-rolls still play, as wanted); popup and redirect networks stay blocked.
     */
    private val videoAds = listOf(
        "doubleclick.net", "googlesyndication.com", "adservice.google.com", "2mdn.net",
        "imasdk.googleapis.com", "amazon-adsystem.com",
    )

    fun isBlockedHost(host: String?, allowVideoAds: Boolean = false): Boolean {
        if (host == null) return false
        val h = host.lowercase()
        if (allowVideoAds && videoAds.any { h == it || h.endsWith(".$it") }) return false
        return blocked.any { h == it || h.endsWith(".$it") }
    }

    @Volatile private var tvIp: String? = null
    @Volatile private var tvIpAt = 0L

    /**
     * The TV itself (localhost, or its own network address). Web pages must never reach it:
     * that's where the phone-page server runs.
     */
    fun isLocalHost(host: String?): Boolean {
        val h = host?.lowercase()?.trim('[', ']') ?: return false
        if (h == "localhost" || h.endsWith(".localhost") || h.startsWith("127.") || h == "::1" || h == "0.0.0.0") return true
        val now = System.currentTimeMillis()
        if (now - tvIpAt > 10_000) {
            tvIp = NetUtil.localIp()
            tvIpAt = now
        }
        return h == tvIp
    }

    /** "www.example.co.uk" -> "example.co.uk", "live.site.com" -> "site.com" (good enough here). */
    fun baseDomain(host: String?): String {
        if (host == null) return ""
        val parts = host.lowercase().trimEnd('.').split('.')
        if (parts.size <= 2) return parts.joinToString(".")
        val twoPartSuffixes = setOf("co.uk", "com.au", "co.nz", "com.br", "co.jp", "co.in", "com.mx")
        val lastTwo = parts.takeLast(2).joinToString(".")
        return if (lastTwo in twoPartSuffixes) parts.takeLast(3).joinToString(".") else lastTwo
    }

    fun sameSite(a: String?, b: String?): Boolean =
        baseDomain(Uri.parse(a ?: "").host) == baseDomain(Uri.parse(b ?: "").host)
}
