package com.streamlink.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface

/** What the phone page shows while it waits. */
object AppStatus {
    @Volatile var text: String = "Waiting for a link"
}

object Net {
    /** "https://player.example.com/embed/123" -> "https://player.example.com" */
    fun origin(url: String): String = try {
        val u = java.net.URI(url)
        u.scheme + "://" + u.host + (if (u.port > 0) ":" + u.port else "")
    } catch (_: Exception) {
        ""
    }
}

object NetUtil {
    /** The TV's address on your home network, e.g. 192.168.1.50. */
    fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }
}

object Qr {
    fun bitmap(text: String, size: Int = 512): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}

/** The last 10 links you sent, newest first. */
object Recents {
    private const val KEY = "recent"
    private const val MAX = 10

    fun list(ctx: Context): List<String> =
        prefs(ctx).getString(KEY, "")!!.split('\n').filter { it.isNotBlank() }

    fun add(ctx: Context, url: String) = save(ctx, (listOf(url) + list(ctx).filter { it != url }).take(MAX))

    fun remove(ctx: Context, url: String) = save(ctx, list(ctx).filter { it != url })

    private fun save(ctx: Context, items: List<String>) =
        prefs(ctx).edit().putString(KEY, items.joinToString("\n")).apply()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("streamlink", Context.MODE_PRIVATE)
}
