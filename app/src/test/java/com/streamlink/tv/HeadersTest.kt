package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadersTest {

    @Test
    fun originOfHttpsAndHttp() {
        assertEquals("https://player.example.com", Headers.originOf("https://player.example.com/embed/123"))
        assertEquals("http://a.example.com", Headers.originOf("http://a.example.com/x?y=1#z"))
    }

    @Test
    fun originOfKeepsNonDefaultPortOnly() {
        assertEquals("https://p.example.com:8443", Headers.originOf("https://p.example.com:8443/e"))
        assertEquals("https://p.example.com", Headers.originOf("https://p.example.com:443/x"))
        assertEquals("http://h.example.com", Headers.originOf("http://h.example.com:80/"))
    }

    @Test
    fun originOfHasNoPathOrTrailingSlash() {
        assertEquals("https://a.example.com", Headers.originOf("https://a.example.com/"))
        assertEquals("https://a.example.com", Headers.originOf("https://a.example.com/deep/path/"))
    }

    @Test
    fun originOfLowersSchemeAndHost() {
        assertEquals("https://player.example.com", Headers.originOf("HTTPS://Player.Example.COM/x"))
    }

    @Test
    fun originOfRejectsGarbageAndNonWeb() {
        assertNull(Headers.originOf(null))
        assertNull(Headers.originOf(""))
        assertNull(Headers.originOf("   "))
        assertNull(Headers.originOf("not a url"))
        assertNull(Headers.originOf("ftp://files.example.com/x"))
        assertNull(Headers.originOf("https:///nohost"))
        assertNull(Headers.originOf("about:blank"))
        assertNull(Headers.originOf("javascript:void(0)"))
        assertNull(Headers.originOf("https://exa mple.com/"))
    }

    @Test
    fun originForUsesCapturedThenDerivedAndNothingWithoutReferer() {
        assertEquals("https://player.example.com", Headers.originFor("https://player.example.com/e", "https://captured.example.com"))
        assertEquals("https://player.example.com", Headers.originFor("https://player.example.com/e", null))
        assertNull(Headers.originFor("", "https://captured.example.com"))
    }

    @Test
    fun capturedOriginIgnoresNullAndGarbage() {
        assertEquals("https://x.example.com", Headers.capturedOrigin(mapOf("origin" to "https://x.example.com/")))
        assertNull(Headers.capturedOrigin(mapOf("Origin" to "null")))
        assertNull(Headers.capturedOrigin(mapOf("Accept" to "*/*")))
    }

    @Test
    fun embeddedRefererOrderCarrierThenFramesWithoutAdsAndDuplicates() {
        val frames = listOf(
            "https://newest.example.com/p",
            "https://ads.adnet.test/frame",
            "about:blank",
            "javascript:void(0)",
            "https://carrier.example.com/page", // duplicate of the carrier, dropped
            "https://older.example.com/p",
        )
        val out = Headers.embeddedReferers(
            "https://carrier.example.com/page",
            frames,
            { it.contains("adnet") },
        )
        // each full address is followed by its bare origin; the cap of 4 cuts the tail
        assertEquals(
            listOf(
                "https://carrier.example.com/page",
                "https://carrier.example.com/",
                "https://newest.example.com/p",
                "https://newest.example.com/",
            ),
            out,
        )
    }

    @Test
    fun embeddedRefererCapAndNoCarrier() {
        val frames = (1..6).map { "https://f$it.example.com/" }
        val out = Headers.embeddedReferers(null, frames, { false })
        assertEquals(4, out.size)
        assertEquals("https://f1.example.com/", out[0])
        assertEquals(4, Headers.embeddedReferers("https://c.example.com/", frames, { false }).size)
    }

    @Test
    fun originFormFollowsFullFormAndNeverDisplacesTheFirstFrame() {
        val out = Headers.withOriginForms(
            listOf("https://dembed.st/premiumtv/daddy.php?id=45", "https://thedaddy.li/embed/x"),
            4,
        )
        assertEquals(
            listOf(
                "https://dembed.st/premiumtv/daddy.php?id=45",
                "https://dembed.st/",
                "https://thedaddy.li/embed/x",
                "https://thedaddy.li/",
            ),
            out,
        )
        // tight cap: the first frame and its origin come before anything from the second
        assertEquals(
            listOf("https://dembed.st/premiumtv/daddy.php?id=45", "https://dembed.st/"),
            Headers.withOriginForms(listOf("https://dembed.st/premiumtv/daddy.php?id=45", "https://thedaddy.li/embed/x"), 2),
        )
    }

    @Test
    fun originFormsSkipDuplicatesByOrigin() {
        val out = Headers.withOriginForms(listOf("https://a.example.com/x", "https://a.example.com/y"), 5)
        assertEquals(
            listOf("https://a.example.com/x", "https://a.example.com/", "https://a.example.com/y"),
            out,
        )
        assertEquals(listOf("https://a.example.com/"), Headers.withOriginForms(listOf("https://a.example.com/"), 5))
    }

    @Test
    fun originForABareOriginRefererHasNoTrailingSlash() {
        assertEquals("https://dembed.st", Headers.originFor("https://dembed.st/", null))
    }

    @Test
    fun describeRefererForTheReport() {
        assertEquals("dembed.st/…", Headers.describeReferer("https://dembed.st/premiumtv/daddy.php?id=45"))
        assertEquals("dembed.st/", Headers.describeReferer("https://dembed.st/"))
        assertEquals("none", Headers.describeReferer(""))
    }
}
