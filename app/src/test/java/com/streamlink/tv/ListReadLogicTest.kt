package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListReadLogicTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L

    private fun item(url: String, start: Long? = null, title: String = "T $url", time: String = "") =
        ListItem(title, "", url, start, time, "", false, null)

    // ---- A1 ----

    @Test fun heavyFilesByAcceptHeader() {
        assertTrue(ListReadLogic.isHeavy("image/avif,image/webp,image/*,*/*;q=0.8", "/x", false))
        assertTrue(ListReadLogic.isHeavy("font/woff2", "/f", false))
        assertTrue(ListReadLogic.isHeavy("video/mp4", "/v", false))
        assertTrue(ListReadLogic.isHeavy("audio/*", "/a", false))
        assertFalse(ListReadLogic.isHeavy("text/html,application/xhtml+xml", "/page", false))
        assertFalse(ListReadLogic.isHeavy("*/*", "/app.js", false))
        assertFalse(ListReadLogic.isHeavy("text/css,*/*;q=0.1", "/site.css", false))
        assertFalse(ListReadLogic.isHeavy(null, null, false))
    }

    @Test fun heavyFilesByExtension() {
        for (p in listOf("/a/logo.PNG", "/x.jpg?v=2", "/f/font.woff2", "/i.svg#frag", "/s/seg.ts", "/p/list.m3u8", "/fav.ico")) {
            assertTrue(p, ListReadLogic.isHeavy("*/*", p, false))
        }
        for (p in listOf("/api/events", "/app.js", "/style.css", "/png/list", "/data.json", "/")) {
            assertFalse(p, ListReadLogic.isHeavy("*/*", p, false))
        }
    }

    @Test fun mainFrameIsNeverBlocked() {
        assertFalse(ListReadLogic.isHeavy("image/png", "/a.png", true))
    }

    // ---- A2 ----

    @Test fun snapshotOnlyOnFailureOrEmpty() {
        val ok = ListResult("https://a.example/", "", listOf(item("https://a.example/1")), "automatic", null)
        assertFalse(ListReadLogic.shouldSnapshot(ok))
        assertFalse(IndexReader.shouldSnapshot(ok))
        assertTrue(ListReadLogic.shouldSnapshot(ok.copy(items = emptyList(), error = ListResult.NO_EVENTS, noEvents = true)))
        assertTrue(ListReadLogic.shouldSnapshot(ok.copy(error = "The page answered HTTP 500")))
        assertTrue(ListReadLogic.shouldSnapshot(ok.copy(items = emptyList())))
    }

    // ---- A3 ----

    @Test fun listLookRebuildsOnlyWhenRowsChange() {
        val a = listOf(ListReadLogic.RowKey("Live now", "u1", "LIVE|"), ListReadLogic.RowKey("Later", "u2", "UPCOMING|"))
        val look = ListReadLogic.listLook(a)
        assertTrue(ListReadLogic.needsRebuild(null, look))
        assertFalse(ListReadLogic.needsRebuild(look, ListReadLogic.listLook(a.toList())))
        // order, section or status change -> rebuild
        assertTrue(ListReadLogic.needsRebuild(look, ListReadLogic.listLook(a.reversed())))
        assertTrue(ListReadLogic.needsRebuild(look, ListReadLogic.listLook(listOf(a[0], a[1].copy(section = "Starting soon")))))
        assertTrue(ListReadLogic.needsRebuild(look, ListReadLogic.listLook(listOf(a[0], a[1].copy(look = "LIVE|")))))
        assertTrue(ListReadLogic.needsRebuild(look, ListReadLogic.listLook(a.take(1))))
    }

    // ---- B2 ----

    @Test fun freshSkip() {

        assertFalse(ListReadLogic.skipFresh(ListReadLogic.Reason.MANUAL, now - 1_000, now))
        assertTrue(ListReadLogic.skipFresh(ListReadLogic.Reason.OPEN, now - 10_000, now))
        assertTrue(ListReadLogic.skipFresh(ListReadLogic.Reason.OPEN, now - ListReadLogic.FRESH_MS + 1, now))
        assertFalse(ListReadLogic.skipFresh(ListReadLogic.Reason.OPEN, now - ListReadLogic.FRESH_MS, now))
        assertTrue(ListReadLogic.skipFresh(ListReadLogic.Reason.TIMER, now - 30_000, now))
        assertFalse(ListReadLogic.skipFresh(ListReadLogic.Reason.TIMER, now - 90_000, now))
        assertFalse(ListReadLogic.skipFresh(ListReadLogic.Reason.OPEN, null, now)) // never read
        assertFalse(ListReadLogic.skipFresh(ListReadLogic.Reason.OPEN, 0L, now))
        assertFalse(ListReadLogic.skipFresh(ListReadLogic.Reason.OPEN, now + 60_000, now)) // clock moved back
    }

    @Test fun queueMergesDuplicates() {
        val q = LinkedHashMap<String, Boolean>()
        assertTrue(ListReadLogic.merge(q, null, "a", false))
        assertTrue(ListReadLogic.merge(q, null, "b", false))
        assertFalse(ListReadLogic.merge(q, null, "a", false))
        assertEquals(listOf("a", "b"), q.keys.toList())
        assertFalse(q["a"]!!)
        assertFalse(ListReadLogic.merge(q, null, "a", true)) // still one entry, now manual, same place
        assertTrue(q["a"]!!)
        assertEquals(listOf("a", "b"), q.keys.toList())
        assertFalse(ListReadLogic.merge(q, "c", "c", true)) // being read now
        assertNull(q["c"])
    }

    // ---- B3 ----

    @Test fun nextReadDelay() {
        assertEquals(5 * min, ListReadLogic.nextReadDelay(emptyList(), now))
        assertEquals(5 * min, ListReadLogic.nextReadDelay(listOf(item("a"), item("b", now + 3 * 60 * min)), now))
        assertEquals(90_000L, ListReadLogic.nextReadDelay(listOf(item("a", now + 14 * min)), now))
        assertEquals(90_000L, ListReadLogic.nextReadDelay(listOf(item("a", now - 9 * min)), now))
        assertEquals(5 * min, ListReadLogic.nextReadDelay(listOf(item("a", now - 11 * min)), now)) // ESPN drives Live/Final
        assertEquals(5 * min, ListReadLogic.nextReadDelay(listOf(item("a", now + 16 * min)), now))
        assertTrue(ListReadLogic.nextReadDelay(listOf(item("a", now)), now) >= ListReadLogic.MIN_READ_GAP_MS)
    }

    // ---- C2 ----

    @Test fun eventsKeyAndStable() {
        val a = listOf(item("u1", time = "8:00 PM"), item("u2"))
        val k = ListReadLogic.eventsKey(a)
        assertTrue(ListReadLogic.stable(k, ListReadLogic.eventsKey(a.toList())))
        assertFalse(ListReadLogic.stable(null, k))
        assertFalse(ListReadLogic.stable(k, ListReadLogic.eventsKey(listOf(a[0]))))
        assertFalse(ListReadLogic.stable(k, ListReadLogic.eventsKey(listOf(a[0].copy(timeText = "8:30 PM"), a[1]))))
        assertFalse(ListReadLogic.stable(k, ListReadLogic.eventsKey(listOf(a[0].copy(title = "Other"), a[1]))))
        assertFalse(ListReadLogic.stable("", ListReadLogic.eventsKey(emptyList()))) // two empty looks are not "stable"
        assertEquals(4, ListReadLogic.MAX_LOOKS)
    }
}
