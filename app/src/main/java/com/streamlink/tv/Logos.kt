package com.streamlink.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Team logos from ESPN. Each logo is downloaded once at the size it is drawn, saved on the TV, and kept in a small
 * memory cache so redrawn rows show it instantly (no flicker). Downloads run on two background threads.
 * A logo that can't be had leaves the team's abbreviation in its circle.
 */
object Logos {
    private val mem = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "logos").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())
    /** Logos that failed this run: not tried again until the app restarts. */
    private val failed = java.util.Collections.synchronizedSet(HashSet<String>())
    /** Logos being fetched, with everyone waiting for each. Main thread only. */
    private val waiting = HashMap<String, MutableList<(Bitmap) -> Unit>>()

    private fun key(url: String, px: Int) = "$px|$url"

    private fun file(ctx: Context, key: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(ctx.cacheDir, "logos").apply { mkdirs() }, "$h.png")
    }

    /** Main thread. The logo now if it is in memory; otherwise null, and [done] gets it later (on the main thread) if it can be had. */
    fun get(ctx: Context, url: String, px: Int, done: (Bitmap) -> Unit): Bitmap? {
        if (url.isBlank()) return null
        val k = key(url, px)
        mem.get(k)?.let { return it }
        if (k in failed) return null
        waiting[k]?.let { it.add(done); return null }
        waiting[k] = mutableListOf(done)
        val app = ctx.applicationContext
        pool.execute {
            val bmp = try { load(app, url, px, k) } catch (_: Throwable) { null }
            if (bmp == null) failed.add(k) else mem.put(k, bmp)
            main.post {
                val all = waiting.remove(k) ?: return@post
                if (bmp != null) all.forEach { it(bmp) }
            }
        }
        return null
    }

    private fun load(ctx: Context, url: String, px: Int, k: String): Bitmap? {
        val f = file(ctx, k)
        if (f.isFile) BitmapFactory.decodeFile(f.path)?.let { return it }
        for (u in LogoUrls.candidates(url, px)) {
            val bytes = fetch(u) ?: continue
            val bmp = decode(bytes, px) ?: continue
            try { f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } } catch (_: Exception) {}
            return bmp
        }
        return null
    }

    private fun fetch(address: String): ByteArray? {
        val c = URL(address).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 8000
            c.readTimeout = 8000
            if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() }.takeIf { it.size in 1..2_000_000 }
        } catch (_: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    private fun decode(bytes: ByteArray, px: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = PhotoFrame.sampleSize(o.outWidth, o.outHeight, px, px) }
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        if (b.width <= px * 2 && b.height <= px * 2) return b
        val s = px.toFloat() / maxOf(b.width, b.height)
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
    }

    /** A round team mark [sizeDp] wide: the abbreviation in a circle until (or unless) the logo arrives. */
    fun view(ctx: Context, team: TeamSide, sizeDp: Int): FrameLayout {
        val px = Ui.dp(ctx, sizeDp)
        val box = FrameLayout(ctx)
        val label = Ui.text(ctx, team.abbr.ifBlank { team.shortName.take(3) }.uppercase(), (sizeDp * 0.28f).coerceAtLeast(8f), Ui.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            background = Ui.rounded(ctx, Ui.CHIP, sizeDp / 2f)
            maxLines = 1
        }
        val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = team.displayName }
        box.addView(label, FrameLayout.LayoutParams(px, px))
        box.addView(img, FrameLayout.LayoutParams(px, px))
        fun show(b: Bitmap) { img.setImageBitmap(b); label.visibility = android.view.View.INVISIBLE }
        img.tag = team.logo
        get(ctx, team.logo, px) { b -> if (img.tag == team.logo) show(b) }?.let { show(it) }
        return box
    }
}
