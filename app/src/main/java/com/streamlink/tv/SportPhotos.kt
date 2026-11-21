package com.streamlink.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One photo per sport for the home screen's featured card. The user uploads them from the phone page (they stay on
 * the TV only, never in the app or the repo) and taps the spot that should stay in view (the focus point).
 */
object SportPhotos {
    private const val PREFS = "sport_photos"

    private fun dir(ctx: Context) = File(ctx.filesDir, "sport-photos").apply { mkdirs() }
    private fun file(ctx: Context, league: String) = File(dir(ctx), "$league.img")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Width / height of the home featured card on this TV, reported to the phone so its crop box matches. */
    @Volatile var cardAspect: Float = DEFAULT_ASPECT
    const val DEFAULT_ASPECT = 4.1f

    /** The decoded photo last handed out, so redraws don't decode again. */
    @Volatile private var cached: Triple<String, Bitmap, Long>? = null

    fun has(ctx: Context, league: String): Boolean = file(ctx, league).isFile

    fun bytes(ctx: Context, league: String): ByteArray? =
        if (SportPhotoRules.validLeague(league)) file(ctx, league).takeIf { it.isFile }?.readBytes() else null

    /** Saves an upload; null on success, else why it was refused. Resets the focus point to the middle. */
    fun save(ctx: Context, league: String, bytes: ByteArray): String? {
        if (!SportPhotoRules.validLeague(league)) return "Unknown sport"
        if (!SportPhotoRules.acceptable(bytes)) return "That isn't a JPEG or PNG photo under 3 MB"
        val tmp = File(dir(ctx), "$league.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file(ctx, league))) { tmp.delete(); return "Couldn't save the photo" }
        prefs(ctx).edit().putFloat("$league.x", 0.5f).putFloat("$league.y", 0.5f).putLong("$league.at", System.currentTimeMillis()).apply()
        cached = null
        Report.log("Photos: saved a ${bytes.size / 1024} KB photo for ${Sports.label(league)}")
        return null
    }

    fun setFocus(ctx: Context, league: String, x: Float, y: Float): Boolean {
        if (!has(ctx, league)) return false
        prefs(ctx).edit().putFloat("$league.x", x).putFloat("$league.y", y).apply()
        return true
    }

    /** The phone's fade sliders for this sport's photo (see [PhotoFade]). */
    fun setFade(ctx: Context, league: String, width: Float, dark: Float): Boolean {
        if (!has(ctx, league)) return false
        prefs(ctx).edit().putFloat("$league.fw", width.coerceIn(PhotoFade.MIN_WIDTH, 1f)).putFloat("$league.fd", dark.coerceIn(0f, 1f)).apply()
        return true
    }

    /** (width, darkness) of the fade; the defaults until the user moves the sliders. */
    fun fade(ctx: Context, league: String): Pair<Float, Float> {
        val p = prefs(ctx)
        return p.getFloat("$league.fw", PhotoFade.DEFAULT_WIDTH) to p.getFloat("$league.fd", PhotoFade.DEFAULT_DARK)
    }

    fun remove(ctx: Context, league: String): Boolean {
        if (!SportPhotoRules.validLeague(league)) return false
        val gone = file(ctx, league).delete()
        prefs(ctx).edit().remove("$league.x").remove("$league.y").remove("$league.at")
            .remove("$league.fw").remove("$league.fd").apply()
        cached = null
        return gone
    }

    /** The focus point as image fractions (x, y); the middle if none was set. */
    fun focus(ctx: Context, league: String): Pair<Float, Float> {
        val p = prefs(ctx)
        return p.getFloat("$league.x", 0.5f) to p.getFloat("$league.y", 0.5f)
    }

    /** GET /photos for the phone page: which sports have a photo, each one's focus point and fade. */
    fun json(ctx: Context): String {
        val arr = JSONArray()
        for (lg in Sports.ALL) {
            val (x, y) = focus(ctx, lg)
            val (fw, fd) = fade(ctx, lg)
            arr.put(JSONObject().put("league", lg).put("name", Sports.label(lg)).put("has", has(ctx, lg))
                .put("x", x.toDouble()).put("y", y.toDouble()).put("at", prefs(ctx).getLong("$lg.at", 0L))
                .put("fw", fw.toDouble()).put("fd", fd.toDouble()))
        }
        return JSONObject().put("photos", arr).put("aspect", cardAspect.toDouble()).toString()
    }

    /** The photo shrunk to about [w]x[h] pixels (off the main thread), or null if the sport has none. */
    fun bitmap(ctx: Context, league: String, w: Int, h: Int): Bitmap? {
        val f = file(ctx, league)
        if (!f.isFile) return null
        val stamp = f.lastModified() xor (w.toLong() shl 20) xor h.toLong()
        cached?.let { (lg, b, s) -> if (lg == league && s == stamp) return b }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = PhotoFrame.sampleSize(o.outWidth, o.outHeight, w, h) }
        val b = try { BitmapFactory.decodeFile(f.path, opts) } catch (_: OutOfMemoryError) { null } ?: return null
        cached = Triple(league, b, stamp)
        return b
    }
}
