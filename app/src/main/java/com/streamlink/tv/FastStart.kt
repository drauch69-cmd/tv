package com.streamlink.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remembers, per opened page address, the stream set that last produced a picture, so the next open can try the
 * first stored source right away (while the page loads as usual, see StreamSniffer.sniff). Entries expire after
 * 24 h; at most [MAX_PAGES] pages are kept (least recently saved dropped first).
 * Addresses and stream links only; nothing here is ever logged in full (Report.short / host).
 */
object LastAddress {
    const val EXPIRE_MS = 24 * 60 * 60 * 1000L
    const val MAX_PAGES = 50
    const val MAX_SOURCES = 8
    private const val PREFS = "streamlink"
    private const val KEY = "last_address_1"

    data class Entry(val pageUrl: String, val sources: List<Source>, val savedAt: Long)

    // ---- pure, unit tested ----

    /** The page address used as the memory key: no click fragment, trimmed, no trailing slash, scheme and host in lower case. Null if not a web address. */
    internal fun normalize(url: String?): String? {
        val u = ClickRef.pagePart(url?.trim() ?: return null).trim()
        val schemeEnd = u.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = u.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return null
        val hostStart = schemeEnd + 3
        var hostEnd = hostStart
        while (hostEnd < u.length && u[hostEnd] != '/' && u[hostEnd] != '?' && u[hostEnd] != '#') hostEnd++
        if (hostEnd == hostStart) return null
        val out = scheme + "://" + u.substring(hostStart, hostEnd).lowercase() + u.substring(hostEnd)
        return out.trimEnd('/')
    }

    private fun isVideoFile(url: String): Boolean {
        val bare = url.substringBefore('?').substringBefore('#').lowercase()
        return StreamUrls.classify(url).first || bare.endsWith(".mp4")
    }

    /**
     * The keys to save [played] under: the address the search was asked to open and the page it ended on (when
     * different). Empty when nothing should be saved: a source that is not verified or only opens a page, a
     * click-only list event, or a link that is a video file itself.
     */
    internal fun keysToSave(requested: String?, pageUrl: String?, played: Source?): List<String> {
        if (played == null || !played.verified || played.openPage) return emptyList()
        val out = ArrayList<String>()
        for (u in listOf(requested, pageUrl)) {
            if (u.isNullOrBlank()) continue
            if (ClickRef.parse(u) != null || isVideoFile(ClickRef.pagePart(u))) return emptyList()
            normalize(u)?.let { if (it !in out) out.add(it) }
        }
        return out
    }

    /**
     * The sources to store: [played] first (marked verified), then the others in their order, same address once,
     * at most [MAX_SOURCES]. Over the limit, extra playable sources go first (from the end), page-opening entries
     * are kept as long as possible.
     */
    internal fun orderForStore(all: List<Source>, played: Source): List<Source> {
        val rest = all.filter { it.url != played.url }
        val seen = HashSet<String>()
        val list = ArrayList<Source>()
        list.add(played.copy(verified = true, openPage = false))
        seen.add(played.url)
        for (s in rest) if (seen.add(s.url)) list.add(s)
        var i = list.size - 1
        while (list.size > MAX_SOURCES && i >= 1) {
            if (!list[i].openPage) list.removeAt(i)
            i--
        }
        while (list.size > MAX_SOURCES) list.removeAt(list.size - 1)
        return list
    }

    internal fun fresh(e: Entry?, now: Long): Boolean = e != null && now - e.savedAt in 0 until EXPIRE_MS

    /** The shortcut (re-check of the saved address) is pointless when this server's addresses are known to die young and the saved one is older than any that still worked. */
    internal fun skipShortcut(life: ServerMemory.Lifetime, ageMs: Long): Boolean =
        life.kind == ServerMemory.LifeKind.SHORT && ageMs > life.maxOkMs

    /** [map] with [e] saved under [key] as the newest; expired entries removed; oldest dropped beyond [MAX_PAGES]. */
    internal fun withEntry(map: Map<String, Entry>, key: String, e: Entry, now: Long): LinkedHashMap<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        for ((k, v) in map) if (k != key && fresh(v, now)) out[k] = v
        out[key] = e
        while (out.size > MAX_PAGES) out.remove(out.keys.first())
        return out
    }

    internal fun without(map: Map<String, Entry>, key: String): LinkedHashMap<String, Entry> =
        LinkedHashMap(map).also { it.remove(key) }

    internal fun toJson(map: Map<String, Entry>): String {
        val o = JSONObject()
        for ((k, e) in map) {
            val arr = JSONArray()
            for (s in e.sources) {
                arr.put(
                    JSONObject()
                        .put("url", s.url)
                        .put("headers", JSONObject(s.headers))
                        .put("referers", JSONArray(s.referers))
                        .put("label", s.label)
                        .put("default", s.isDefaultLink)
                        .put("verified", s.verified)
                        .put("mime", s.mime)
                        .put("player", s.playerUrl)
                        .put("open", s.openPage)
                        .put("note", s.note)
                )
            }
            o.put(k, JSONObject().put("page", e.pageUrl).put("at", e.savedAt).put("sources", arr))
        }
        return o.toString()
    }

    internal fun fromJson(text: String?): LinkedHashMap<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        if (text.isNullOrBlank()) return out
        try {
            val o = JSONObject(text)
            for (k in o.keys()) {
                val e = o.optJSONObject(k) ?: continue
                val arr = e.optJSONArray("sources") ?: continue
                val sources = ArrayList<Source>()
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    val url = s.optString("url")
                    if (url.isEmpty()) continue
                    val h = LinkedHashMap<String, String>()
                    s.optJSONObject("headers")?.let { ho -> for (hk in ho.keys()) h[hk] = ho.optString(hk) }
                    val refs = ArrayList<String>()
                    s.optJSONArray("referers")?.let { ra -> for (j in 0 until ra.length()) refs.add(ra.optString(j)) }
                    sources.add(
                        Source(
                            url, h, refs, s.optString("label"), s.optBoolean("default"),
                            verified = s.optBoolean("verified", true), note = s.optString("note"),
                            playerUrl = s.optString("player"), openPage = s.optBoolean("open"), mime = s.optString("mime"),
                        )
                    )
                }
                if (sources.isNotEmpty()) out[k] = Entry(e.optString("page"), sources, e.optLong("at"))
            }
        } catch (_: Exception) {
            out.clear()
        }
        return out
    }

    /** The set to hand to the normal finish path: the first stored source (as just re-checked) first, the rest as stored. */
    internal fun restore(e: Entry, firstChecked: Source): StreamSet =
        StreamSet(e.pageUrl, listOf(firstChecked.copy(verified = true)) + e.sources.drop(1))

    /** First stored source that can really be played (the played one is stored first), or null. */
    internal fun firstPlayable(e: Entry): Source? = e.sources.firstOrNull()?.takeIf { !it.openPage }

    // ---- storage ----

    @Synchronized
    fun get(ctx: Context, pageUrl: String, now: Long = System.currentTimeMillis()): Entry? {
        val k = normalize(pageUrl) ?: return null
        return load(ctx)[k]?.takeIf { fresh(it, now) }
    }

    /** Saves [set] under the address(es) the search was asked to open, with [played] first. No-op for lists that must not be stored. */
    @Synchronized
    fun save(ctx: Context, requested: String?, set: StreamSet, played: Source) {
        val keys = keysToSave(requested, set.pageUrl, played)
        if (keys.isEmpty()) return
        val now = System.currentTimeMillis()
        val e = Entry(set.pageUrl, orderForStore(set.sources, played), now)
        var map: Map<String, Entry> = load(ctx)
        for (k in keys) map = withEntry(map, k, e, now)
        write(ctx, map)
    }

    @Synchronized
    fun forget(ctx: Context, vararg pageUrls: String?) {
        var map: Map<String, Entry> = load(ctx)
        var changed = false
        for (u in pageUrls) {
            val k = normalize(u) ?: continue
            if (k in map) { map = without(map, k); changed = true }
        }
        if (changed) write(ctx, map)
    }

    private fun load(ctx: Context): Map<String, Entry> =
        fromJson(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    private fun write(ctx: Context, map: Map<String, Entry>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, toJson(map)).apply()
    }
}

/**
 * Remembers, per stream-server host, the referer the server accepted and which network engine ("chrome" or
 * "standard") it worked with. Entries expire after 7 days (each part on its own clock); at most [MAX_HOSTS] hosts,
 * least recently used dropped first. Hosts and referers only; never logged in full.
 * Call [init] once at app start; before that (unit tests) it only works on what is passed in.
 */
object ServerMemory {
    const val EXPIRE_MS = 7 * 24 * 60 * 60 * 1000L
    const val MAX_HOSTS = 100
    /** Re-check samples kept per host; younger checks than [MIN_SAMPLE_AGE_MS] say nothing about expiry and are not kept. */
    const val MAX_SAMPLES = 12
    const val MIN_SAMPLE_AGE_MS = 20_000L
    /** Failures at or under this age (with no success older than the youngest of them) mean "short lived": above the 10 min refresh, so a failed re-check at the long interval still counts. */
    const val SHORT_FAIL_MS = 11 * 60_000L
    /** A success this old, with no short failure, means "long lived". */
    const val LONG_OK_MS = 10 * 60_000L
    const val CHROME = "chrome"
    const val STANDARD = "standard"
    private const val PREFS = "streamlink"
    private const val KEY = "server_memory_1"

    /** [refuseStdAt]: when this host last refused standard networking (403); 0 = never. [samples]: how old saved addresses were when re-checked (see [lifetimeEstimate]). */
    data class Entry(
        val referer: String?, val refererAt: Long, val engine: String?, val engineAt: Long, val refuseStdAt: Long = 0L,
        val samples: List<Sample> = emptyList(),
    )

    /** One re-check of an address of this server: it was [ageMs] old (since it was found) and still worked ([ok]) or not; [at] = when the check was made. */
    data class Sample(val ageMs: Long, val ok: Boolean, val at: Long)

    enum class LifeKind { UNKNOWN, SHORT, LONG }

    /** How long addresses of one server stay valid. [maxOkMs]: for SHORT, a typical age that still worked (0 otherwise). */
    data class Lifetime(val kind: LifeKind, val maxOkMs: Long = 0L)

    /** What the player starts with for one source. [note] is the report line to log, or null. */
    data class EngineStart(val useChromeNet: Boolean, val chromeForced: Boolean, val note: String?)

    // ---- pure, unit tested ----

    internal fun key(host: String?): String? =
        host?.trim()?.lowercase()?.removePrefix("www.")?.ifEmpty { null }

    internal fun referer(e: Entry?, now: Long): String? =
        e?.takeIf { it.referer != null && it.referer.isNotEmpty() && now - it.refererAt in 0 until EXPIRE_MS }?.referer

    internal fun engine(e: Entry?, now: Long): String? =
        e?.takeIf { (it.engine == CHROME || it.engine == STANDARD) && now - it.engineAt in 0 until EXPIRE_MS }?.engine

    internal fun refusal(e: Entry?, now: Long): Boolean = e != null && e.refuseStdAt > 0 && now - e.refuseStdAt in 0 until EXPIRE_MS

    private fun alive(e: Entry, now: Long) =
        referer(e, now) != null || engine(e, now) != null || refusal(e, now) || e.samples.any { now - it.at in 0 until EXPIRE_MS }

    /**
     * What the re-check samples say about this server's address lifetime. Only the newest 8 samples of the last 7 days count.
     * SHORT: at least 2 failed re-checks at an age of 20 s to [SHORT_FAIL_MS] and no success older than the youngest of those
     * failures (a success older than a failure means the failures were not about age); [Lifetime.maxOkMs] is the oldest success
     * younger than that failure (else half its age, at least 20 s). LONG: a success of [LONG_OK_MS] or more and no short failure.
     * Otherwise UNKNOWN.
     */
    internal fun lifetimeEstimate(samples: List<Sample>, now: Long): Lifetime {
        val live = samples.filter { now - it.at in 0 until EXPIRE_MS }.takeLast(8)
        val oks = live.filter { it.ok }
        val shortFails = live.filter { !it.ok && it.ageMs in MIN_SAMPLE_AGE_MS..SHORT_FAIL_MS }
        if (shortFails.size >= 2) {
            val youngest = shortFails.minOf { it.ageMs }
            if (oks.none { it.ageMs > youngest }) {
                val okBelow = oks.filter { it.ageMs < youngest }.maxOfOrNull { it.ageMs }
                return Lifetime(LifeKind.SHORT, (okBelow ?: (youngest / 2)).coerceAtLeast(MIN_SAMPLE_AGE_MS))
            }
            return Lifetime(LifeKind.UNKNOWN)
        }
        if (shortFails.isEmpty()) {
            val best = oks.maxOfOrNull { it.ageMs } ?: 0L
            if (best >= LONG_OK_MS) return Lifetime(LifeKind.LONG, best)
        }
        return Lifetime(LifeKind.UNKNOWN)
    }

    /** [map] with one more re-check sample for [host] (old ones past 7 days dropped, newest [MAX_SAMPLES] kept); unchanged when [ageMs] is too small to say anything. */
    internal fun withSample(map: Map<String, Entry>, host: String, ageMs: Long, ok: Boolean, now: Long): LinkedHashMap<String, Entry> {
        if (ageMs < MIN_SAMPLE_AGE_MS) return LinkedHashMap(map)
        return updated(map, host, now) { e ->
            e.copy(samples = (e.samples.filter { now - it.at in 0 until EXPIRE_MS } + Sample(ageMs, ok, now)).takeLast(MAX_SAMPLES))
        }
    }

    /** [map] with [update] applied to [host]'s entry, which becomes the newest; dead entries removed, oldest dropped beyond [MAX_HOSTS]. */
    internal fun updated(map: Map<String, Entry>, host: String, now: Long, update: (Entry) -> Entry): LinkedHashMap<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        for ((k, v) in map) if (k != host && alive(v, now)) out[k] = v
        val next = update(map[host] ?: Entry(null, 0L, null, 0L))
        if (alive(next, now)) out[host] = next
        while (out.size > MAX_HOSTS) out.remove(out.keys.first())
        return out
    }

    internal fun withReferer(map: Map<String, Entry>, host: String, referer: String, now: Long) =
        updated(map, host, now) { it.copy(referer = referer, refererAt = now) }

    internal fun withEngine(map: Map<String, Entry>, host: String, engine: String, now: Long) =
        updated(map, host, now) { it.copy(engine = engine, engineAt = now) }

    internal fun withRefusesStandard(map: Map<String, Entry>, host: String, now: Long) =
        updated(map, host, now) { it.copy(refuseStdAt = now) }

    internal fun withoutEngine(map: Map<String, Entry>, host: String, now: Long) =
        updated(map, host, now) { it.copy(engine = null, engineAt = 0L) }

    /**
     * The referers to try, in order: the remembered one first (even if the candidate did not list it), then the
     * candidate's own (at most 5 real ones in all, as before), then none. Each address once; the empty one last.
     */
    internal fun refererOrder(candidate: List<String>, remembered: String?): List<String> {
        val own = candidate.filter { it.isNotEmpty() }
        val real = if (remembered.isNullOrEmpty()) own.take(5)
        else listOf(remembered) + own.filter { it != remembered }.take(4)
        return (real + "").distinct()
    }

    /** Start state of the network engine for a source whose host remembers [remembered]. */
    internal fun engineStart(remembered: String?, chromeAvailable: Boolean): EngineStart = when {
        remembered == STANDARD ->
            EngineStart(false, false, "Using standard networking for this server (it worked last time)")
        remembered == CHROME && chromeAvailable ->
            EngineStart(true, true, "Staying on Chrome's network engine for this server (it worked last time)")
        else -> EngineStart(true, false, null)
    }

    internal fun toJson(map: Map<String, Entry>): String {
        val o = JSONObject()
        for ((h, e) in map) {
            val j = JSONObject()
            if (e.referer != null) j.put("ref", e.referer).put("refAt", e.refererAt)
            if (e.engine != null) j.put("eng", e.engine).put("engAt", e.engineAt)
            if (e.refuseStdAt > 0) j.put("rsAt", e.refuseStdAt)
            if (e.samples.isNotEmpty()) {
                val a = JSONArray()
                for (x in e.samples) a.put(JSONArray().put(x.ageMs).put(if (x.ok) 1 else 0).put(x.at))
                j.put("life", a)
            }
            o.put(h, j)
        }
        return o.toString()
    }

    internal fun fromJson(text: String?): LinkedHashMap<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        if (text.isNullOrBlank()) return out
        try {
            val o = JSONObject(text)
            for (k in o.keys()) {
                val j = o.optJSONObject(k) ?: continue
                out[k] = Entry(
                    if (j.has("ref")) j.optString("ref") else null, j.optLong("refAt"),
                    if (j.has("eng")) j.optString("eng") else null, j.optLong("engAt"),
                    j.optLong("rsAt"),
                    parseSamples(j.optJSONArray("life")),
                )
            }
        } catch (_: Exception) {
            out.clear()
        }
        return out
    }

    private fun parseSamples(a: JSONArray?): List<Sample> {
        if (a == null) return emptyList()
        val out = ArrayList<Sample>()
        for (i in 0 until a.length()) {
            val x = a.optJSONArray(i) ?: continue
            if (x.length() < 3) continue
            out.add(Sample(x.optLong(0), x.optInt(1) == 1, x.optLong(2)))
        }
        return out
    }

    // ---- storage (in memory, written through) ----

    @Volatile private var app: Context? = null
    private var map: LinkedHashMap<String, Entry>? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    @Synchronized
    private fun current(): LinkedHashMap<String, Entry> {
        map?.let { return it }
        val loaded = app?.let { fromJson(it.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)) } ?: LinkedHashMap()
        map = loaded
        return loaded
    }

    @Synchronized
    private fun store(next: LinkedHashMap<String, Entry>) {
        map = next
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString(KEY, toJson(next))?.apply()
    }

    @Synchronized
    fun refererFor(host: String?): String? {
        val k = key(host) ?: return null
        return referer(current()[k], System.currentTimeMillis())
    }

    @Synchronized
    fun engineFor(host: String?): String? {
        val k = key(host) ?: return null
        return engine(current()[k], System.currentTimeMillis())
    }

    @Synchronized
    fun recordReferer(host: String?, referer: String) {
        val k = key(host) ?: return
        if (referer.isEmpty()) return
        val now = System.currentTimeMillis()
        if (referer(current()[k], now) == referer) return
        store(withReferer(current(), k, referer, now))
    }

    @Synchronized
    fun recordEngine(host: String?, engine: String) {
        val k = key(host) ?: return
        val now = System.currentTimeMillis()
        if (engine(current()[k], now) == engine) return
        store(withEngine(current(), k, engine, now))
    }

    @Synchronized
    fun forgetEngine(host: String?) {
        val k = key(host) ?: return
        val now = System.currentTimeMillis()
        if (engine(current()[k], now) == null) return
        store(withoutEngine(current(), k, now))
    }

    @Synchronized
    fun refusesStandard(host: String?): Boolean {
        val k = key(host) ?: return false
        return refusal(current()[k], System.currentTimeMillis())
    }

    @Synchronized
    fun recordRefusesStandard(host: String?) {
        val k = key(host) ?: return
        val now = System.currentTimeMillis()
        if (refusal(current()[k], now)) return
        store(withRefusesStandard(current(), k, now))
    }

    /** What is known about how long this server's addresses stay valid (UNKNOWN when nothing was learned). */
    @Synchronized
    fun lifetimeFor(host: String?): Lifetime {
        val k = key(host) ?: return Lifetime(LifeKind.UNKNOWN)
        return lifetimeEstimate(current()[k]?.samples ?: emptyList(), System.currentTimeMillis())
    }

    /** Records that an address of [host] that was [ageMs] old (since it was found) still worked ([ok]) or was refused. */
    @Synchronized
    fun recordAge(host: String?, ageMs: Long, ok: Boolean) {
        val k = key(host) ?: return
        if (ageMs < MIN_SAMPLE_AGE_MS) return
        store(withSample(current(), k, ageMs, ok, System.currentTimeMillis()))
    }
}
