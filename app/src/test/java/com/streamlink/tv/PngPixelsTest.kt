package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class PngPixelsTest {
    private val video = ByteArray(188 * 40) { if (it % 188 == 0) 0x47 else (it * 7).toByte() }

    private fun png(rgba: Boolean): ByteArray {
        val channels = if (rgba) 3 else 3 // video packed into R,G,B; alpha (if any) is 255
        val pixelsNeeded = (video.size + channels - 1) / channels
        val w = 64
        val h = (pixelsNeeded + w - 1) / w
        val img = BufferedImage(w, h, if (rgba) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        var i = 0
        for (y in 0 until h) for (x in 0 until w) {
            fun next(): Int = if (i < video.size) video[i++].toInt() and 0xFF else 0
            val r = next(); val g = next(); val b = next()
            img.setRGB(x, y, (0xFF shl 24) or (r shl 16) or (g shl 8) or b)
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    private fun check(bytes: ByteArray, layout: String) {
        val decoded = PngPixels.decode(bytes, bytes.size)
        val hit = decoded.firstOrNull { d ->
            UnwrapDataSource.looksLikeMedia(d.bytes, d.bytes.size, 0)
        }
        assertTrue("no layout found video", hit != null)
        assertEquals(layout, hit!!.layout)
        assertTrue(hit.bytes.copyOf(video.size).contentEquals(video))
    }

    @Test fun rgbPng() = check(png(rgba = false), "all 3 channel(s)")

    @Test fun rgbaPng() = check(png(rgba = true), "RGB, alpha dropped")

    @Test fun notPng() = assertTrue(PngPixels.decode(ByteArray(100), 100).isEmpty())
}
