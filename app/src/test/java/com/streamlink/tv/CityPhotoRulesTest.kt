package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CityPhotoRulesTest {
    private val t = TeamSide("A", "A", "A", "A", "A", "", "")
    private fun g(city: String, state: String = "", country: String = "") =
        Game("1", "nfl", 0L, "pre", "", 0, "", t, t, "", venueCity = city, venueState = state, venueCountry = country)

    @Test fun keysAndLabels() {
        assertEquals("chicago|il", CityPhotoRules.placeKey(g("Chicago", "IL", "USA")))
        assertEquals("london|england", CityPhotoRules.placeKey(g("London", "", "England")))
        assertNull(CityPhotoRules.placeKey(g("")))
        assertEquals("Chicago, IL", CityPhotoRules.placeLabel(g("Chicago", "IL")))
        assertEquals("Laramie", CityPhotoRules.placeLabel(g("Laramie")))
    }

    @Test fun queriesUseTheCityThenTheStateOrCountry() {
        assertEquals(listOf("Laramie skyline", "Wyoming landscape"), CityPhotoRules.queries(g("Laramie", "WY")))
        assertEquals(listOf("London skyline", "England landscape"), CityPhotoRules.queries(g("London", "", "England")))
        assertEquals(listOf("Mexico City skyline", "Mexico landscape"), CityPhotoRules.queries(g("Mexico City", "", "Mexico")))
        // A state given in full is used as is; a city named like its region gets no second search.
        assertEquals(listOf("Austin skyline", "Texas landscape"), CityPhotoRules.queries(g("Austin", "Texas")))
        assertEquals(listOf("Singapore skyline"), CityPhotoRules.queries(g("Singapore", "", "Singapore")))
        assertEquals(emptyList<String>(), CityPhotoRules.queries(g("")))
    }

    private fun r(id: String, w: Int, h: Int, raw: String = "https://images.unsplash.com/photo-$id?ixid=x",
                  dl: String = "https://api.unsplash.com/photos/$id/download") =
        """{"id":"$id","width":$w,"height":$h,"urls":{"raw":"$raw"},"links":{"download_location":"$dl"},"user":{"name":"Pat $id","links":{"html":"https://unsplash.com/@p$id"}}}"""

    @Test fun parseKeepsOnlyGoodResults() {
        val json = """{"results":[${r("a", 3000, 2000)},${r("b", 0, 2000)},${r("c", 3000, 1000, raw = "https://evil.example/x")},${r("d", 4000, 1000, dl = "http://x")}]}"""
        val c = CityPhotoRules.parseSearch(json)
        assertEquals(listOf("a"), c.map { it.id })
        assertEquals("Pat a", c[0].name)
        assertEquals(emptyList<CityPhotoRules.Candidate>(), CityPhotoRules.parseSearch("not json"))
    }

    @Test fun widestFirstKeepingOrderOnTies() {
        val json = """{"results":[${r("a", 3000, 2000)},${r("b", 4000, 1000)},${r("c", 1500, 1000)},${r("d", 3000, 1000)}]}"""
        assertEquals(listOf("b", "d", "a", "c"), CityPhotoRules.rank(CityPhotoRules.parseSearch(json)).map { it.id })
    }

    @Test fun imageAddressCreditNextAndLimit() {
        assertEquals("https://images.unsplash.com/p?ixid=x&w=1600&fm=jpg&q=80", CityPhotoRules.imageUrl("https://images.unsplash.com/p?ixid=x"))
        assertEquals("https://images.unsplash.com/p?w=800&fm=jpg&q=80", CityPhotoRules.imageUrl("https://images.unsplash.com/p", 800))
        assertEquals("Photo: Pat / Unsplash", CityPhotoRules.credit("Pat"))
        assertEquals(1, CityPhotoRules.next(0, 3))
        assertEquals(0, CityPhotoRules.next(2, 3))
        assertEquals(0, CityPhotoRules.next(0, 0))
        val now = 10_000_000L
        assertTrue(CityPhotoRules.underLimit(List(39) { now - 1000 }, now))
        assertFalse(CityPhotoRules.underLimit(List(40) { now - 1000 }, now))
        assertTrue(CityPhotoRules.underLimit(List(40) { now - 3_600_001L }, now))
    }

    @Test fun venueIsReadFromEspn() {
        val json = """{"events":[{"id":"9","date":"2026-11-22T18:00Z","competitions":[{"venue":{"fullName":"Soldier Field","address":{"city":"Chicago","state":"IL","country":"USA"}},
            "competitors":[{"homeAway":"home","team":{"displayName":"Chicago Bears","shortDisplayName":"Bears","abbreviation":"CHI","location":"Chicago","name":"Bears"}},
            {"homeAway":"away","team":{"displayName":"Green Bay Packers","shortDisplayName":"Packers","abbreviation":"GB","location":"Green Bay","name":"Packers"}}]}],
            "status":{"type":{"state":"pre","shortDetail":"Sun"}}}]}"""
        val g = ScoreParse.parse(json, "nfl").single()
        assertEquals("Chicago", g.venueCity)
        assertEquals("IL", g.venueState)
        assertEquals("USA", g.venueCountry)
        val none = ScoreParse.parse(json.replace(""""venue":{"fullName":"Soldier Field","address":{"city":"Chicago","state":"IL","country":"USA"}},""", ""), "nfl").single()
        assertEquals("", none.venueCity)
    }
}
