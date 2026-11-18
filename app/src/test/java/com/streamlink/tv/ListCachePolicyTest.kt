package com.streamlink.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListCachePolicyTest {
    private val item = ListItem("A vs B", "", "https://x.test/1", null, "", "", false, null)
    private fun r(items: List<ListItem>, error: String?, noEvents: Boolean = false) =
        ListResult("https://x.test/", "", items, "automatic", error, noEvents)

    @Test fun eventsAreSaved() = assertTrue(ListCachePolicy.shouldSave(r(listOf(item), null)))
    @Test fun eventsWithErrorAreSaved() = assertTrue(ListCachePolicy.shouldSave(r(listOf(item), "partial")))
    @Test fun noEventsSavesTheEmptyList() = assertTrue(ListCachePolicy.shouldSave(r(emptyList(), ListResult.NO_EVENTS, true)))
    @Test fun loadFailuresKeepTheOldCache() {
        assertFalse(ListCachePolicy.shouldSave(r(emptyList(), "Could not open the page (net::ERR)")))
        assertFalse(ListCachePolicy.shouldSave(r(emptyList(), "the page took too long to answer")))
        assertFalse(ListCachePolicy.shouldSave(r(emptyList(), "The page answered HTTP 500")))
    }
}
