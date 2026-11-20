package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineTest {
    private val now = 1_800_000_000_000L
    private fun item(url: String, start: Long? = now - 30 * 60_000L) = ListItem("A vs B", "", url, start, "", "", false, null)

    @Test fun offlineItemIsFinishedWithOfflineBadge() {
        val i = item("https://a.example/e")
        val live = ScoreStatus.resolve(i, now, emptyList(), offline = { false })
        assertEquals(ListStatus.LIVE, live.status)
        val off = ScoreStatus.resolve(i, now, emptyList(), offline = { it == "https://a.example/e" })
        assertEquals(ListStatus.FINISHED, off.status)
        assertEquals("Offline", off.badge)
        assertNull(ScoreStatus.resolve(item("https://b.example/e"), now, emptyList(), offline = { it == "https://a.example/e" }).badge)
    }

    @Test fun defaultHasNoMarks() {
        assertEquals(ListStatus.LIVE, ScoreStatus.resolve(item("https://a.example/x"), now, emptyList()).status)
    }

    @Test fun storedMarksRoundTripAndExpire() {
        val m = mapOf("a.example/e" to now - 60_000L, "b.example/f" to now - OfflineMarks.MARK_MS - 1)
        val back = OfflineText.parse(OfflineText.format(m), now)
        assertEquals(setOf("a.example/e"), back.keys)
        assertTrue(OfflineText.parse("junk\nx\tnotanumber\n\t5", now).isEmpty())
        assertTrue(OfflineText.parse(null, now).isEmpty())
        // a time in the future (clock moved back) is dropped
        assertTrue(OfflineText.parse("k\t${now + 60_000L}", now).isEmpty())
    }
}
