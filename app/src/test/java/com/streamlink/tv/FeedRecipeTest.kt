package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedRecipeTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    // Shape A: a bare array of all sports, filtered by the list address's last part; live from start/end.
    private val arrayFeed = FeedRecipe.Feed(
        url = "https://api.example.net/api/matches/all", filter = mapOf("category" to "{path:-1}"),
        link = "/watch/{id}", title = "title", start = "date", end = "ends_at", subtitle = "league.name",
    )
    private val arrayAnswer = """[
      {"id":"a-at-b","title":"Away Team at Home Team","category":"american-football","date":${now - hour},"ends_at":${now + 2 * hour},"league":{"name":"NFL"}},
      {"id":"c-at-d","title":"C at D","category":"american-football","date":${now + 20 * hour},"ends_at":${now + 24 * hour},"league":{"name":"NFL"}},
      {"id":"e-at-f","title":"E at F","category":"basketball","date":${now},"ends_at":${now + hour}},
      {"id":"","title":"no id"},
      {"id":"a-at-b","title":"duplicate link"}
    ]"""

    // Shape B: an object holding the array; the address part goes into the url and links; a live flag; no time = channel.
    private val objectFeed = FeedRecipe.Feed(
        url = "https://site.example.org/api/get-matches?server={path:1|main}&type=both", items = "all",
        link = "/watch/{path:1|main}/{id}", title = "title", subtitle = "tournament", section = "category",
        start = "date", end = "ends", live = "live",
    )
    private val objectAnswer = """{"success":true,"all":[
      {"id":"ppv-1","title":"Channel One","category":"24/7 Streams","tournament":"24/7 channel","date":0,"ends":0,"live":true},
      {"id":"ppv-2","title":"Team X at Team Y","category":"Basketball","tournament":"NBA","date":${now + hour},"ends":${now + 4 * hour},"live":false}
    ]}"""

    @Test
    fun arrayFeedFiltersByTheListAddressAndMarksLiveByTime() {
        val items = FeedRecipe.items(arrayAnswer, arrayFeed, "https://www.example.com/sport/american-football", now)
        assertEquals(listOf("https://www.example.com/watch/a-at-b", "https://www.example.com/watch/c-at-d"), items.map { it.url })
        assertEquals("Away Team at Home Team", items[0].title)
        assertEquals("NFL", items[0].subtitle)
        assertEquals(now - hour, items[0].startMillis)
        assertTrue(items[0].liveHint)
        assertFalse(items[1].liveHint)
        // Another sport list of the same site gets its own events
        val bb = FeedRecipe.items(arrayAnswer, arrayFeed, "https://www.example.com/sport/basketball", now)
        assertEquals(listOf("https://www.example.com/watch/e-at-f"), bb.map { it.url })
        // No last address part to filter by: nothing (never guess)
        assertTrue(FeedRecipe.items(arrayAnswer, arrayFeed, "https://www.example.com/", now).isEmpty())
    }

    @Test
    fun objectFeedUsesAddressPartsAndLiveFlag() {
        val page = "https://site.example.org/matches/raptor"
        assertEquals("https://site.example.org/api/get-matches?server=raptor&type=both", FeedRecipe.feedUrl(objectFeed, page))
        val items = FeedRecipe.items(objectAnswer, objectFeed, page, now)
        assertEquals(listOf("https://site.example.org/watch/raptor/ppv-1", "https://site.example.org/watch/raptor/ppv-2"), items.map { it.url })
        assertNull(items[0].startMillis) // a channel: no time
        assertTrue(items[0].liveHint)
        assertEquals("24/7 Streams", items[0].section)
        assertFalse(items[1].liveHint)
        // The site's main address: the fallback part is used
        assertEquals("https://site.example.org/api/get-matches?server=main&type=both", FeedRecipe.feedUrl(objectFeed, "https://site.example.org/"))
    }

    @Test
    fun brokenAnswersGiveNothing() {
        val page = "https://www.example.com/sport/american-football"
        assertTrue(FeedRecipe.items("not json", arrayFeed, page, now).isEmpty())
        assertTrue(FeedRecipe.items("{\"all\":5}", objectFeed, page, now).isEmpty())
        assertTrue(FeedRecipe.items("<html></html>", arrayFeed, page, now).isEmpty())
    }

    @Test
    fun recipeParsesFeedEntries() {
        val json = """{"sites":[
          {"host":"example.com","feed":{"url":"https://api.example.net/x","filter":{"category":"{path:-1}"},"link":"/watch/{id}","start":"date","end":"ends_at"}},
          {"host":"bad.example","feed":{"url":"https://x"}}
        ]}"""
        val r = ListRecipes.parse(json)
        assertEquals(1, r.size)
        val f = r[0].feed
        assertNotNull(f)
        assertEquals("/watch/{id}", f!!.link)
        assertEquals(mapOf("category" to "{path:-1}"), f.filter)
        assertEquals("ms", f.startUnit)
        assertNull(r[0].item)
    }

    @Test
    fun templates() {
        val parts = listOf("sport", "basketball")
        assertEquals("basketball", FeedRecipe.expand("{path:-1}", parts))
        assertEquals("sport", FeedRecipe.expand("{path:0}", parts))
        assertNull(FeedRecipe.expand("{path:5}", parts))
        assertEquals("x", FeedRecipe.expand("{path:5|x}", parts))
        assertEquals("/w/7", FeedRecipe.expand("/w/{a.b}", parts, org.json.JSONObject("""{"a":{"b":7}}""")))
        assertNull(FeedRecipe.expand("/w/{a.c}", parts, org.json.JSONObject("""{"a":{"b":7}}""")))
    }
}
