package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickRefTest {
    @Test fun roundTrip() {
        val ref = ClickRef("https://ex.com/events?day=1", "Rovers vs \"Athletic\" + Co. \\ ünï ✓", 3, "div.row.item")
        val url = ref.toUrl()
        assertTrue(url.startsWith("https://ex.com/events?day=1#sl-click="))
        assertFalse(url.contains(" ")) // a valid absolute URL
        assertTrue(java.net.URI(url).host == "ex.com")
        assertEquals(ref, ClickRef.parse(url))
    }

    @Test fun pageWithOwnHashIsKept() {
        val ref = ClickRef("https://ex.com/#/events", "Final", 0, "")
        val parsed = ClickRef.parse(ref.toUrl())!!
        assertEquals("https://ex.com/#/events", parsed.pageUrl)
        assertEquals("https://ex.com/#/events", ClickRef.pagePart(ref.toUrl()))
        assertEquals(1, ref.toUrl().count { it == '#' }) // still a valid URL
        java.net.URI(ref.toUrl())
    }

    @Test fun readsWhatJavascriptWrites() {
        // encodeURIComponent(JSON.stringify({t:"Cup Final",i:2,s:"div.row"}))
        val url = "https://ex.com/list#sl-click=%7B%22t%22%3A%22Cup%20Final%22%2C%22i%22%3A2%2C%22s%22%3A%22div.row%22%7D"
        assertEquals(ClickRef("https://ex.com/list", "Cup Final", 2, "div.row"), ClickRef.parse(url))
        assertEquals("""{"t":"Cup Final","i":2,"s":"div.row"}""", ClickRef.specJson(ClickRef.parse(url)!!))
    }

    @Test fun missingSelectorAndIndexAreAllowed() {
        val p = ClickRef.parse("https://ex.com/l#sl-click=%7B%22t%22%3A%22A%22%7D")!!
        assertEquals(-1, p.index)
        assertEquals("", p.selector)
    }

    @Test fun notAClickUrl() {
        assertNull(ClickRef.parse("https://ex.com/e/1"))
        assertNull(ClickRef.parse("https://ex.com/e/1#section"))
        assertFalse(ClickRef.isClickUrl("https://ex.com/e/1#x"))
        assertEquals("https://ex.com/e/1#x", ClickRef.pagePart("https://ex.com/e/1#x"))
    }

    @Test fun malformedFragmentsGiveNull() {
        assertNull(ClickRef.parse("https://ex.com/l#sl-click="))
        assertNull(ClickRef.parse("https://ex.com/l#sl-click=%ZZ"))
        assertNull(ClickRef.parse("https://ex.com/l#sl-click=notjson"))
        assertNull(ClickRef.parse("https://ex.com/l#sl-click=%7B%22i%22%3A1%7D")) // neither title nor selector
        assertNull(ClickRef.parse("ftp://ex.com/l#sl-click=%7B%22t%22%3A%22A%22%7D"))
    }

    @Test fun distinctRowsGiveDistinctUrls() {
        val a = ClickRef("https://ex.com/l", "Same", 0, "div.r").toUrl()
        val b = ClickRef("https://ex.com/l", "Same", 1, "div.r").toUrl()
        assertTrue(a != b)
    }

    @Test fun clickUrlsPassParseExtraction() {
        val url = ClickRef("https://ex.com/l", "Cup Final", 1, "div.r").toUrl()
        val json = """{"pageTitle":"T","pageUrl":"https://ex.com/l","method":"automatic","debug":"","textLen":900,"loading":true,
          "frames":[{"src":"https://player.other.net/embed","w":1280,"h":720,"pct":100,"same":false,"vis":true}],
          "items":[{"title":"Cup Final","subtitle":"","url":"$url","timeText":"8pm","datetime":"","section":"","liveHint":false,"imageUrl":null}]}"""
        val ex = IndexReader.parseExtraction(json, "x", null, 0L, java.time.ZoneId.of("UTC")) { false }
        assertEquals(url, ex.result.items.single().url)
        assertEquals(1, ex.clickRows)
        assertEquals(900, ex.textLen)
        assertTrue(ex.loading)
        assertEquals(listOf(FramePick.Frame("https://player.other.net/embed", 100.0, false, true)), ex.frames)
    }

    // ---- FramePick
    private fun f(src: String, pct: Double, vis: Boolean = true) = FramePick.Frame(src, pct, false, vis)
    private val none: (String?) -> Boolean = { false }

    @Test fun picksLargeFrameAsStrong() {
        val p = FramePick.pick(listOf(f("https://a.com/x", 10.0), f("https://b.com/y", 80.0)), "https://top.com/", none)!!
        assertEquals("https://b.com/y", p.url)
        assertTrue(p.strong)
    }

    @Test fun smallBestFrameIsWeak() {
        val p = FramePick.pick(listOf(f("https://a.com/x", 10.0), f("https://b.com/y", 20.0)), "https://top.com/", none)!!
        assertEquals("https://b.com/y", p.url)
        assertFalse(p.strong)
    }

    @Test fun thresholdIsThirtyFivePercent() {
        assertTrue(FramePick.pick(listOf(f("https://a.com/x", 35.0)), "https://t.com/", none)!!.strong)
        assertFalse(FramePick.pick(listOf(f("https://a.com/x", 34.9)), "https://t.com/", none)!!.strong)
    }

    @Test fun skipsAdsHiddenTinyNonWebAndSelf() {
        val frames = listOf(
            f("https://ads.bad/x", 90.0),            // blocked host
            f("https://a.com/hidden", 90.0, vis = false),
            f("https://a.com/tiny", 1.0),            // tracker pixel
            f("javascript:void(0)", 90.0),
            f("about:blank", 90.0),
            f("", 90.0),
            f("https://top.com/#frag", 90.0),        // the page itself
        )
        assertNull(FramePick.pick(frames, "https://top.com", { it == "ads.bad" }))
        assertEquals("https://ok.com/", FramePick.pick(frames + f("https://ok.com/", 40.0), "https://top.com", { it == "ads.bad" })!!.url)
    }

    @Test fun noFramesNoPick() {
        assertNull(FramePick.pick(emptyList(), "https://t.com/", none))
    }

    @Test fun recipeFailureReasonNamesTheCause() {
        assertEquals("HTTP 404", ListRecipes.describeFailure(ListRecipes.RecipesHttpException("HTTP 404")))
        assertEquals("SocketTimeoutException: timed out", ListRecipes.describeFailure(java.net.SocketTimeoutException("timed out")))
        assertEquals("UnknownHostException", ListRecipes.describeFailure(java.net.UnknownHostException()))
    }
}
