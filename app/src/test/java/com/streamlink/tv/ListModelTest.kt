package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListModelTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L
    private val hour = 60 * min

    private fun item(title: String, start: Long?, live: Boolean = false) =
        ListItem(title, "", "https://example.com/$title", start, "", "", live, null)

    @Test fun statusRules() {
        assertEquals(ListStatus.LIVE, ListStatus.of(item("a", null, live = true), now))
        assertEquals(ListStatus.LIVE, ListStatus.of(item("a", now + 3 * hour, live = true), now)) // the page says live
        assertEquals(ListStatus.UNKNOWN, ListStatus.of(item("a", null), now))
        assertEquals(ListStatus.UPCOMING, ListStatus.of(item("a", now + 31 * min), now))
        assertEquals(ListStatus.SOON, ListStatus.of(item("a", now + 30 * min), now))
        assertEquals(ListStatus.SOON, ListStatus.of(item("a", now + 1), now))
        assertEquals(ListStatus.LIVE, ListStatus.of(item("a", now), now))
        assertEquals(ListStatus.LIVE, ListStatus.of(item("a", now - 4 * hour + 1), now))
        assertEquals(ListStatus.FINISHED, ListStatus.of(item("a", now - 4 * hour), now))
        assertEquals(ListStatus.FINISHED, ListStatus.of(item("a", now - 9 * hour), now))
    }

    @Test fun sortOrder() {
        val items = listOf(
            item("finishedOld", now - 10 * hour),
            item("upLater", now + 5 * hour),
            item("unknown1", null),
            item("soon", now + 10 * min),
            item("finishedNew", now - 5 * hour),
            item("liveByTime", now - 1 * hour),
            item("upSooner", now + 2 * hour),
            item("liveHint", null, live = true),
            item("unknown2", null),
        )
        val order = ListStatus.sorted(items, now).map { it.title }
        assertEquals(
            listOf("liveByTime", "liveHint", "soon", "upSooner", "upLater", "unknown1", "unknown2", "finishedNew", "finishedOld"),
            order,
        )
    }

    @Test fun sortEmpty() {
        assertTrue(ListStatus.sorted(emptyList(), now).isEmpty())
    }

    // ---- recipes ----

    @Test fun recipeParsing() {
        val json = """{"sites":[
            {"host":"WWW.Example.com","item":"li.ev","title":"h3","subtitle":".org","time":"time","link":"a.more","section":"h2","live":".live"},
            {"host":"other.org","item":"div.row"},
            {"host":"","item":"x"},
            {"item":"y"},
            {"host":"noitem.com"},
            "junk", 7, null
          ], "_example": {"host": "ignored.com", "item": "z"}}"""
        val r = ListRecipes.parse(json)
        assertEquals(2, r.size)
        assertEquals("example.com", r[0].host)
        assertEquals("li.ev", r[0].item)
        assertEquals("h3", r[0].title)
        assertEquals("a.more", r[0].link)
        assertEquals(".live", r[0].live)
        assertNull(r[1].title)
        assertNull(r[1].section)
    }

    @Test fun badRecipeJsonNeverCrashes() {
        for (bad in listOf("", "{", "[]", "null", "{\"sites\": 5}", "{\"sites\": [", "not json", "{\"sites\":[{\"host\":1,\"item\":2}]}", "\u0000")) {
            assertTrue(bad, ListRecipes.parse(bad).isEmpty())
        }
        assertTrue(ListRecipes.parse("{\"sites\": []}").isEmpty())
    }

    @Test fun recipeMatchesHostOrParentDomain() {
        val r = ListRecipes.parse("""{"sites":[{"host":"example.com","item":"a"},{"host":"live.example.com","item":"b"}]}""")
        assertEquals("a", ListRecipes.match(r, "example.com")?.item)
        assertEquals("a", ListRecipes.match(r, "www.example.com")?.item)
        assertEquals("a", ListRecipes.match(r, "events.example.com")?.item)
        assertEquals("b", ListRecipes.match(r, "live.example.com")?.item) // most specific wins
        assertEquals("b", ListRecipes.match(r, "x.live.example.com")?.item)
        assertNull(ListRecipes.match(r, "notexample.com"))
        assertNull(ListRecipes.match(r, "example.org"))
        assertNull(ListRecipes.match(r, null))
    }

    @Test fun shippedRecipesFileIsValid() {
        val text = java.io.File("../recipes.json").takeIf { it.exists() }?.readText() ?: return
        assertTrue(MiniJson.parse(text) is Map<*, *>)
        assertTrue(ListRecipes.parse(text).isEmpty()) // the "_example" is documentation, not a recipe
    }

    // ---- extractor output -> ListResult ----

    @Test fun miniJsonBasics() {
        val v = MiniJson.parse("""{"a":[1,2.5,"x\né\"",true,false,null],"b":{}}""") as Map<*, *>
        assertEquals(listOf(1.0, 2.5, "x\né\"", true, false, null), v["a"])
        assertEquals("héllo", MiniJson.parse("\"h\\u00e9llo\""))
    }

    @Test fun extractionBecomesResult() {
        val zone = java.time.ZoneId.of("America/Chicago")
        val nowMs = java.time.ZonedDateTime.of(2026, 11, 17, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val json = """{"pageTitle":"Things","pageUrl":"https://ex.com/list","method":"automatic","debug":"d","noLink":0,"items":[
          {"title":"Pottery","subtitle":"Clay","url":"https://ex.com/e/1","timeText":"Tue, Nov 24 • 6:30 PM","datetime":"","section":"","liveHint":false,"imageUrl":null},
          {"title":"Match","subtitle":"","url":"https://ex.com/e/2","timeText":"20:00","datetime":"","section":"Friday, December 4, 2026","liveHint":true,"imageUrl":"https://ex.com/i.png"},
          {"title":"Ad","subtitle":"","url":"https://ads.bad/x","timeText":"","datetime":"","section":"","liveHint":false,"imageUrl":null},
          {"title":"Bad scheme","subtitle":"","url":"javascript:void(0)","timeText":"","datetime":"","section":"","liveHint":false,"imageUrl":null},
          {"title":"","subtitle":"","url":"https://ex.com/e/3","timeText":"","datetime":"","section":"","liveHint":false,"imageUrl":null}
        ]}"""
        val ex = IndexReader.parseExtraction(json, "https://fallback", null, nowMs, zone) { it == "ads.bad" }
        val r = ex.result
        assertEquals("d", ex.debug)
        assertEquals("https://ex.com/list", r.pageUrl)
        assertEquals("automatic", r.method)
        assertEquals(2, r.items.size)
        assertEquals(java.time.ZonedDateTime.of(2026, 11, 24, 18, 30, 0, 0, zone).toInstant().toEpochMilli(), r.items[0].startMillis)
        assertEquals(java.time.ZonedDateTime.of(2026, 12, 4, 20, 0, 0, 0, zone).toInstant().toEpochMilli(), r.items[1].startMillis)
        assertTrue(r.items[1].liveHint)
        assertEquals("https://ex.com/i.png", r.items[1].imageUrl)
        assertNull(r.items[0].imageUrl)
        val rec = IndexReader.parseExtraction(json.replace("\"automatic\"", "\"recipe\""), "x", "ex.com", nowMs, zone) { false }
        assertEquals("recipe:ex.com", rec.result.method)
    }
}
