package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class DateGuessTest {
    private val zone = ZoneId.of("America/Chicago")
    private val now = ZonedDateTime.of(2026, 11, 17, 12, 0, 0, 0, zone).toInstant().toEpochMilli() // a Tuesday

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private fun g(text: String, section: String = "", datetime: String? = null) = DateGuess.guess(text, section, now, zone, datetime)

    @Test fun noYearPicksNearestYear() {
        assertEquals(at(2026, 5, 24, 9), g("Sat, May 24 • 9:00 AM")) // 5.8 months back is still within -6 months
        assertEquals(at(2026, 5, 27, 18, 30), g("Tue, May 27 • 6:30 PM"))
        assertEquals(at(2027, 1, 5, 19), g("Jan 5, 7 PM"))
        assertEquals(at(2026, 11, 21, 9), g("Sat, Nov 21 • 9:00 AM"))
    }

    @Test fun labelsAreIgnored() {
        assertEquals(at(2026, 9, 8, 12), g("Start time: 12:00 PM", "Tuesday, September 8, 2026"))
        assertEquals(at(2026, 9, 8, 12), g("Kicks off 12:00 PM", "Tuesday, September 8, 2026"))
        assertEquals(at(2026, 12, 5, 20), g("Kickoff: Dec 5 @ 8pm"))
        assertEquals(at(2026, 11, 21, 19), g("Doors 7:00 PM, Sat Nov 21"))
        assertEquals(at(2026, 9, 8, 12), g("12:00 PM", "Tue 8 Sep 2026"))
    }

    @Test fun twentyFourHour() {
        assertEquals(at(2026, 12, 4, 19, 30), g("19:30", "Friday, December 4, 2026"))
        assertEquals(at(2026, 12, 3, 20), g("Dec 3 20:00"))
        assertEquals(at(2026, 12, 4, 21, 30), g("Begins 21:30", "Friday, December 4, 2026"))
    }

    @Test fun amPmEdges() {
        assertEquals(at(2026, 11, 20, 0, 30), g("Nov 20, 12:30 AM"))
        assertEquals(at(2026, 11, 20, 12, 30), g("Nov 20, 12:30 PM"))
        assertEquals(at(2026, 11, 20, 9), g("Nov 20, 9:00 AM – 11:00 AM"))
    }

    @Test fun relativeDays() {
        assertEquals(at(2026, 11, 17, 20), g("Today 8:00 PM"))
        assertEquals(at(2026, 11, 18, 13), g("Tomorrow 1 PM"))
        assertEquals(at(2026, 11, 17, 20), g("Tonight 8pm"))
    }

    @Test fun weekdayOnlyMeansNextOccurrence() {
        assertEquals(at(2026, 11, 20, 19, 30), g("Fri 19:30"))
        assertEquals(at(2026, 11, 17, 19, 30), g("Tuesday 7:30 PM")) // today, later
    }

    @Test fun isoStrings() {
        assertEquals(Instant.parse("2026-11-19T17:15:00Z").toEpochMilli(), g("2026-11-19T17:15:00Z"))
        assertEquals(Instant.parse("2026-11-19T15:15:00Z").toEpochMilli(), g("2026-11-19T17:15:00+02:00"))
        assertEquals(Instant.parse("2026-11-19T22:45:00Z").toEpochMilli(), g("2026-11-19T17:15:00-0530"))
        assertEquals(at(2026, 11, 19, 17, 15), g("2026-11-19 17:15")) // no offset = TV time zone
    }

    @Test fun monthDayWithTime() {
        assertEquals(at(2026, 11, 19, 17, 15), g("Nov 19, 5:15 PM"))
        assertEquals(at(2026, 11, 19, 17, 15), g("19 Nov 2026, 5:15 PM"))
        assertEquals(at(2026, 11, 19, 17, 15), g("November 19th 2026 at 17:15"))
    }

    @Test fun numericDates() {
        assertEquals(at(2026, 11, 19, 17, 15), g("19/11/2026 17:15")) // 19 cannot be a month: day-first
        assertEquals(at(2026, 11, 19, 17, 15), g("11/19/2026 5:15 PM"))
        assertEquals(at(2026, 3, 4, 9), g("03/04/2026 9:00 AM")) // ambiguous: month-first
        assertEquals(at(2026, 11, 19, 17, 15), g("2026-11-19 5:15 PM"))
        assertEquals(at(2026, 11, 19, 17, 15), g("11/19 17:15"))
    }

    @Test fun relativeTimes() {
        assertEquals(now + 2 * 3_600_000L, g("in 2 hours"))
        assertEquals(now + 15 * 60_000L, g("starts in 15 min"))
        assertEquals(now + 60 * 60_000L, g("Starting in an hour"))
    }

    @Test fun datetimeAttribute() {
        assertEquals(Instant.parse("2026-11-19T17:15:00Z").toEpochMilli(), g("Thu 5:15 PM", "", "2026-11-19T17:15:00Z"))
        assertEquals(at(2026, 11, 19, 17, 15), g("", "", "2026-11-19T17:15:00"))
        assertEquals(at(2026, 11, 19, 17, 15), g("5:15 PM", "", "2026-11-19")) // date from the attribute, time from the text
    }

    @Test fun unsureIsNull() {
        assertNull(g(""))
        assertNull(g("TBA"))
        assertNull(g("Nov 19")) // date but no time of day
        assertNull(g("9:00 PM")) // time but no date: not assumed to be today
        assertNull(g("Feb 30, 7pm"))
        assertNull(g("25:99"))
        assertNull(g("Starts soon"))
    }
}
