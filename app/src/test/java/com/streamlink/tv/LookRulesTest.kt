package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LookRulesTest {
    @Test fun logoCandidatesTryDarkThenNormalThenOriginal() {
        val u = "https://a.espncdn.com/i/teamlogos/nfl/500/nyg.png"
        val c = LogoUrls.candidates(u, 64)
        assertEquals(3, c.size)
        assertEquals("https://a.espncdn.com/combiner/i?img=%2Fi%2Fteamlogos%2Fnfl%2F500-dark%2Fnyg.png&w=64&h=64", c[0])
        assertEquals("https://a.espncdn.com/combiner/i?img=%2Fi%2Fteamlogos%2Fnfl%2F500%2Fnyg.png&w=64&h=64", c[1])
        assertEquals(u, c[2])
        // Sizes are kept sane
        assertTrue(LogoUrls.candidates(u, 4000)[0].endsWith("&w=256&h=256"))
        // A logo path without "/500/" has no dark try
        assertEquals(2, LogoUrls.candidates("https://a.espncdn.com/i/teamlogos/ncaa/200/2.png", 40).size)
        // Not ESPN: as is; not https: nothing
        assertEquals(listOf("https://other.example/x.png"), LogoUrls.candidates("https://other.example/x.png", 40))
        assertTrue(LogoUrls.candidates("http://a.espncdn.com/i/x.png", 40).isEmpty())
        assertTrue(LogoUrls.candidates("", 40).isEmpty())
    }

    @Test fun photoCoversTheBoxAndKeepsTheFocusInView() {
        // 1600x900 photo in a 510x196 box: width decides the scale, the photo is cropped top and bottom.
        val mid = PhotoFrame.place(1600, 900, 510, 196, 0.5f, 0.5f)
        assertEquals(510f / 1600, mid.scale, 1e-4f)
        assertEquals(0f, mid.dx, 0.01f)
        assertEquals((196 - 900 * mid.scale) / 2, mid.dy, 0.01f)
        // Focus near the top: the top edge stays at 0, never a gap
        val top = PhotoFrame.place(1600, 900, 510, 196, 0.5f, 0.02f)
        assertEquals(0f, top.dy, 0.01f)
        // Focus at the bottom: the bottom edge meets the box bottom
        val bottom = PhotoFrame.place(1600, 900, 510, 196, 0.5f, 1f)
        assertEquals(196f - 900 * bottom.scale, bottom.dy, 0.01f)
        // Tall photo in a wide box: height can move, width fills
        val tall = PhotoFrame.place(900, 1600, 510, 196, 0.3f, 0.25f)
        assertTrue(900 * tall.scale >= 510f - 0.01f && 1600 * tall.scale >= 196f)
        // Out-of-range focus values are clamped; bad sizes give the identity
        assertEquals(PhotoFrame.place(1600, 900, 510, 196, 0.5f, 0f), PhotoFrame.place(1600, 900, 510, 196, 0.5f, -3f))
        assertEquals(PhotoFrame.Placement(1f, 0f, 0f), PhotoFrame.place(0, 900, 510, 196, 0.5f, 0.5f))
    }

    @Test fun sampleSizeShrinksWithoutGoingBelowTheBox() {
        assertEquals(1, PhotoFrame.sampleSize(1000, 600, 510, 196))
        assertEquals(2, PhotoFrame.sampleSize(1600, 900, 510, 196))
        assertEquals(4, PhotoFrame.sampleSize(4000, 2250, 510, 196))
        assertEquals(1, PhotoFrame.sampleSize(4000, 2250, 0, 196))
    }

    @Test fun photoUploadRules() {
        val jpeg = ByteArray(100).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }
        val png = ByteArray(100).also { it[0] = 0x89.toByte(); it[1] = 'P'.code.toByte(); it[2] = 'N'.code.toByte(); it[3] = 'G'.code.toByte() }
        assertTrue(SportPhotoRules.acceptable(jpeg))
        assertTrue(SportPhotoRules.acceptable(png))
        assertFalse(SportPhotoRules.acceptable("<html>hello world, this is not a picture at all, not even close ok</html>".toByteArray()))
        assertFalse(SportPhotoRules.acceptable(ByteArray(10)))
        assertFalse(SportPhotoRules.acceptable(ByteArray(SportPhotoRules.MAX_BYTES + 1).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }))
        assertTrue(SportPhotoRules.validLeague("nfl"))
        assertFalse(SportPhotoRules.validLeague("../nfl"))
        assertFalse(SportPhotoRules.validLeague(null))
        assertEquals(0.25f, SportPhotoRules.focus("0.25")!!, 1e-6f)
        assertEquals(1f, SportPhotoRules.focus("7")!!, 1e-6f)
        assertEquals(0f, SportPhotoRules.focus("-1")!!, 1e-6f)
        assertNull(SportPhotoRules.focus("NaN"))
        assertNull(SportPhotoRules.focus("x"))
        assertNull(SportPhotoRules.focus(null))
    }

    @Test fun playerSpeedPill() {
        val ok = PlayerStats.line(18_400_000, 6_200_000, 0)
        assertEquals("18.4 Mbps · needs 6.2 · 0 dropped", ok.text)
        assertEquals(PlayerStats.Level.GOOD, ok.level)
        assertEquals(PlayerStats.Level.TIGHT, PlayerStats.line(7_000_000, 6_200_000, 1).level)
        assertEquals(PlayerStats.Level.SLOW, PlayerStats.line(5_000_000, 6_200_000, 1).level)
        val unknown = PlayerStats.line(9_000_000, 0, 2)
        assertEquals("9.0 Mbps · 2 dropped", unknown.text)
        assertEquals(PlayerStats.Level.MEASURING, unknown.level)
        assertEquals("Measuring speed…", PlayerStats.line(0, -1, -1).text)
    }

    @Test fun streamRateMeasuresFromPieces() {
        val r = StreamRate()
        assertEquals(0L, r.bps())
        // 6-second video pieces of 3 MB = 4 Mbps
        repeat(3) { r.add(2, 3_000_000, 6_000) }
        assertEquals(4_000_000L, r.bps())
        // Separate audio pieces over the same seconds add on top: 96 kB per 6 s = 128 kbps
        r.add(1, 96_000, 6_000)
        assertEquals(4_128_000L, r.bps())
        // Odd pieces are ignored
        r.add(2, 0, 6_000); r.add(2, 5_000, 50); r.add(2, 5_000, 120_000)
        assertEquals(4_128_000L, r.bps())
        // Only the last pieces count
        repeat(StreamRate.WINDOW) { r.add(2, 1_500_000, 6_000) }
        assertEquals(2_128_000L, r.bps())
        r.clear()
        assertEquals(0L, r.bps())
    }
}
