package com.streamlink.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * City photos for the featured card (user, 2026-11-20, choice A): the game's city photo wins over the sport photo
 * the user uploaded. Searched on Unsplash once per city, saved on the TV, reused for every game there. The phone
 * can swap to the next search result or move the framing per city. The sport's fade still applies.
 * Off when the build has no Unsplash key. Rules: [CityPhotoRules].
 */
object CityPhotos {
    private const val PREFS = "city_photos"
    private const val API = "https://api.unsplash.com"
    /** Cities listed on the phone page (most recently shown first). */
    private const val PHONE_MAX = 12

    data class Photo(val bitmap: Bitmap, val x: Float, val y: Float, val credit: String)

    /** Bumped on every change (new photo, next photo, framing); the card redraws when it moves. */
    val version = AtomicInteger(0)

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "city-photos").apply { isDaemon = true } }
    private val busy = HashSet<String>()
    private val requests = ArrayList<Long>()
    @Volatile private var cached: Triple<String, Bitmap, Long>? = null

    fun enabled(): Boolean = BuildConfig.UNSPLASH_KEY.isNotBlank()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dir(ctx: Context) = File(ctx.filesDir, "city-photos").apply { mkdirs() }
    private fun entry(ctx: Context, key: String): JSONObject? =
        prefs(ctx).getString("e.$key", null)?.let { try { JSONObject(it) } catch (_: Exception) { null } }
    private fun store(ctx: Context, key: String, e: JSONObject) { prefs(ctx).edit().putString("e.$key", e.toString()).apply() }

    private fun cands(e: JSONObject): List<CityPhotoRules.Candidate> {
        val a = e.optJSONArray("cands") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            CityPhotoRules.Candidate(o.optString("id"), o.optString("raw"), o.optInt("w"), o.optInt("h"),
                o.optString("name"), o.optString("profile"), o.optString("dl"))
        }
    }

    private fun current(e: JSONObject): CityPhotoRules.Candidate? {
        val c = cands(e)
        return c.getOrNull(e.optInt("idx", 0).coerceIn(0, (c.size - 1).coerceAtLeast(0)))
    }

    private fun file(ctx: Context, key: String, id: String): File {
        val safeId = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(40)
        return File(dir(ctx), Integer.toHexString(key.hashCode()) + "_" + safeId + ".jpg")
    }

    /**
     * The city photo for [g] at about [w]x[h] pixels, or null (no city, no key, not fetched yet, nothing found).
     * When it isn't fetched yet, a search starts in the background and [onReady] runs (any thread) once it is.
     * Call off the main thread.
     */
    fun forGame(ctx: Context, g: Game, w: Int, h: Int, onReady: () -> Unit): Photo? {
        if (!enabled()) return null
        val key = CityPhotoRules.placeKey(g) ?: return null
        val now = System.currentTimeMillis()
        val e = entry(ctx, key) ?: JSONObject().put("label", CityPhotoRules.placeLabel(g))
        // Remember the sport and when it was shown, for the phone page's list.
        e.put("league", g.league).put("seen", now)
        store(ctx, key, e)
        val c = current(e)
        if (c == null) {
            val retry = when (e.optString("state")) {
                "none" -> CityPhotoRules.NONE_RETRY_MS
                "fail" -> CityPhotoRules.FAIL_RETRY_MS
                else -> 0L
            }
            if (now - e.optLong("at", 0L) >= retry) fetch(ctx.applicationContext, key, g, onReady)
            return null
        }
        val f = file(ctx, key, c.id)
        if (!f.isFile) { fetch(ctx.applicationContext, key, g, onReady); return null }
        val b = decode(f, w, h) ?: return null
        return Photo(b, e.optDouble("x", CityPhotoRules.DEFAULT_X.toDouble()).toFloat(),
            e.optDouble("y", CityPhotoRules.DEFAULT_Y.toDouble()).toFloat(), CityPhotoRules.credit(c.name))
    }

    private fun decode(f: File, w: Int, h: Int): Bitmap? {
        val stamp = f.lastModified() xor (w.toLong() shl 20) xor h.toLong()
        cached?.let { (p, b, s) -> if (p == f.path && s == stamp) return b }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = PhotoFrame.sampleSize(o.outWidth, o.outHeight, w, h) }
        val b = try { BitmapFactory.decodeFile(f.path, opts) } catch (_: OutOfMemoryError) { null } ?: return null
        cached = Triple(f.path, b, stamp)
        return b
    }

    /** Searches (if needed) and downloads the current photo for [key], once at a time per city. */
    private fun fetch(ctx: Context, key: String, g: Game, onReady: () -> Unit) {
        synchronized(busy) { if (!busy.add(key)) return }
        try {
            worker.execute {
                try {
                    if (work(ctx, key, g)) { version.incrementAndGet(); onReady() }
                } catch (_: Throwable) {
                } finally {
                    synchronized(busy) { busy.remove(key) }
                }
            }
        } catch (_: Exception) {
            synchronized(busy) { busy.remove(key) }
        }
    }

    private fun work(ctx: Context, key: String, g: Game): Boolean {
        val e = entry(ctx, key) ?: JSONObject().put("label", CityPhotoRules.placeLabel(g))
        if (current(e) == null) {
            var found = emptyList<CityPhotoRules.Candidate>()
            var failed = false
            for (q in CityPhotoRules.queries(g)) {
                val body = get("$API/search/photos?orientation=landscape&per_page=${CityPhotoRules.PER_SEARCH}&query=" +
                    URLEncoder.encode(q, "UTF-8"))
                if (body == null) { failed = true; break }
                found = CityPhotoRules.rank(CityPhotoRules.parseSearch(body))
                if (found.isNotEmpty()) { Report.log("City photos: ${found.size} for \"$q\""); break }
            }
            e.put("at", System.currentTimeMillis())
            if (found.isEmpty()) {
                e.put("state", if (failed) "fail" else "none")
                store(ctx, key, e)
                if (!failed) Report.log("City photos: nothing found for ${e.optString("label")}")
                return false
            }
            val arr = JSONArray()
            for (c in found) arr.put(JSONObject().put("id", c.id).put("raw", c.raw).put("w", c.width).put("h", c.height)
                .put("name", c.name).put("profile", c.profile).put("dl", c.download))
            e.put("cands", arr).put("idx", 0).put("state", "ok")
                .put("x", CityPhotoRules.DEFAULT_X.toDouble()).put("y", CityPhotoRules.DEFAULT_Y.toDouble())
            store(ctx, key, e)
        }
        return download(ctx, key, e)
    }

    /** Downloads the current photo's image and tells Unsplash it was used (their guideline), once per photo. */
    private fun download(ctx: Context, key: String, e: JSONObject): Boolean {
        val c = current(e) ?: return false
        val f = file(ctx, key, c.id)
        if (!f.isFile) {
            val bytes = getBytes(CityPhotoRules.imageUrl(c.raw)) ?: return false
            val tmp = File(f.path + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) { tmp.delete(); return false }
        }
        val pinged = e.optJSONArray("pinged") ?: JSONArray()
        if ((0 until pinged.length()).none { pinged.optString(it) == c.id }) {
            get(c.download)
            pinged.put(c.id)
            e.put("pinged", pinged)
            store(ctx, key, e)
        }
        return true
    }

    /** Phone page: the next search result for [key]; framing goes back to the default. */
    fun next(ctx: Context, key: String, onDone: (Boolean) -> Unit) {
        val e = entry(ctx, key)
        val list = e?.let { cands(it) }.orEmpty()
        if (e == null || list.size < 2) { onDone(false); return }
        val old = current(e)
        e.put("idx", CityPhotoRules.next(e.optInt("idx", 0), list.size))
            .put("x", CityPhotoRules.DEFAULT_X.toDouble()).put("y", CityPhotoRules.DEFAULT_Y.toDouble())
        store(ctx, key, e)
        old?.let { file(ctx, key, it.id).delete() }
        try {
            worker.execute {
                val ok = try { download(ctx.applicationContext, key, e) } catch (_: Throwable) { false }
                version.incrementAndGet()
                onDone(ok)
            }
        } catch (_: Exception) { onDone(false) }
    }

    fun setFocus(ctx: Context, key: String, x: Float, y: Float): Boolean {
        val e = entry(ctx, key) ?: return false
        if (current(e) == null) return false
        e.put("x", x.coerceIn(0f, 1f).toDouble()).put("y", y.coerceIn(0f, 1f).toDouble())
        store(ctx, key, e)
        version.incrementAndGet()
        return true
    }

    /** GET /cityphoto?key=: the current photo's bytes, or null. */
    fun bytes(ctx: Context, key: String): ByteArray? {
        val e = entry(ctx, key) ?: return null
        val c = current(e) ?: return null
        return file(ctx, key, c.id).takeIf { it.isFile }?.readBytes()
    }

    /** GET /cityphotos: cities with a photo, most recently shown first, with framing, credit and the sport's fade. */
    fun json(ctx: Context): String {
        val rows = prefs(ctx).all.keys.filter { it.startsWith("e.") }.mapNotNull { k ->
            val key = k.removePrefix("e.")
            entry(ctx, key)?.let { key to it }
        }.filter { (key, e) -> current(e)?.let { file(ctx, key, it.id).isFile } == true }
            .sortedByDescending { it.second.optLong("seen", 0L) }.take(PHONE_MAX)
        val arr = JSONArray()
        for ((key, e) in rows) {
            val c = current(e) ?: continue
            val lg = e.optString("league")
            val (fw, fd) = SportPhotos.fade(ctx, lg)
            arr.put(JSONObject().put("key", key).put("label", e.optString("label")).put("league", lg)
                .put("sport", Sports.label(lg)).put("id", c.id).put("credit", CityPhotoRules.credit(c.name))
                .put("profile", c.profile).put("count", cands(e).size)
                .put("x", e.optDouble("x", CityPhotoRules.DEFAULT_X.toDouble()))
                .put("y", e.optDouble("y", CityPhotoRules.DEFAULT_Y.toDouble()))
                .put("fw", fw.toDouble()).put("fd", fd.toDouble()))
        }
        return JSONObject().put("enabled", enabled()).put("cities", arr).put("aspect", SportPhotos.cardAspect.toDouble()).toString()
    }

    private fun allowed(): Boolean {
        synchronized(requests) {
            val now = System.currentTimeMillis()
            requests.removeAll { now - it >= 3600_000L }
            if (!CityPhotoRules.underLimit(requests, now)) return false
            requests.add(now)
            return true
        }
    }

    /** An API call with the app's key; null on any failure or when over the hourly limit. */
    private fun get(address: String): String? {
        if (!allowed()) { Report.log("City photos: hourly limit reached, waiting"); return null }
        return try {
            val c = URL(address).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("Authorization", "Client-ID " + BuildConfig.UNSPLASH_KEY)
            c.setRequestProperty("Accept-Version", "v1")
            try {
                if (c.responseCode != 200) { Report.log("City photos: Unsplash answered ${c.responseCode}"); null }
                else c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
            } finally { c.disconnect() }
        } catch (e: Exception) { Report.log("City photos: ${e.javaClass.simpleName}"); null }
    }

    /** Image files don't count against Unsplash's limit and need no key. */
    private fun getBytes(address: String): ByteArray? = try {
        val c = URL(address).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        try {
            if (c.responseCode != 200) null
            else c.inputStream.use { s -> s.readBytes().takeIf { it.size in 1..(8 shl 20) } }
        } finally { c.disconnect() }
    } catch (_: Exception) { null }
}
