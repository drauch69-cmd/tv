package com.streamlink.tv

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Turns the date/time text of an index page into a start time. Pure JVM.
 *
 * The date and time are searched anywhere in the text (labels and prefixes such as "Starts:" or
 * "Kickoff" are ignored). Returns null whenever it is not sure: no time of day, no date at all
 * (a bare time is NOT assumed to mean today), impossible values, or a date-without-year that has
 * no year within -6..+6 months of now.
 */
object DateGuess {
    private const val MONTHS = "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sept?(?:ember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?"
    private const val DAYS = "mon(?:day)?|tue(?:s(?:day)?)?|wed(?:nesday)?|thu(?:r(?:s(?:day)?)?)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?"
    private val OPT = setOf(RegexOption.IGNORE_CASE)

    private val ISO_FULL = Regex("""(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?\s*(Z|[+-]\d{2}(?::?\d{2})?)?""", OPT)
    private val ISO_DATE = Regex("""\b(\d{4})-(\d{1,2})-(\d{1,2})\b""")
    private val REL_IN = Regex("""\bin\s+(?:about\s+|around\s+|~\s*)?(\d+|an?|half an)\s*(min(?:ute)?s?|m|hours?|hrs?|h|days?)\b""", OPT)
    private val TIME12 = Regex("""(?<![\d:])(1[0-2]|0?[1-9])(?::([0-5]\d))?(?::[0-5]\d)?\s*([ap])\.?m\.?(?![a-z])""", OPT)
    private val TIME24 = Regex("""(?<![\d:.])([01]?\d|2[0-3]):([0-5]\d)(?::[0-5]\d)?(?![\d:])""")
    private val MONTH_DAY = Regex("""\b($MONTHS)\b\.?\s*(\d{1,2})(?!\d)(?:st|nd|rd|th)?(?:\s*,?\s*(\d{4})(?![\d:]))?""", OPT)
    private val DAY_MONTH = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?($MONTHS)\b\.?(?:\s*,?\s*(\d{4})(?![\d:]))?""", OPT)
    private val NUM_DATE = Regex("""(?<![\d:])(\d{1,2})[/.\-](\d{1,2})(?:[/.\-](\d{4}|\d{2}))?(?![\d:])""")
    private val REL_DAY = Regex("""\b(today|tonight|tomorrow|yesterday)\b""", OPT)
    private val WEEKDAY = Regex("""\b($DAYS)\b\.?""", OPT)

    /**
     * @param timeText date/time text as shown (may be empty)
     * @param section heading above the row, used for the date when [timeText] has none
     * @param datetime machine-readable value next to the text (<time datetime>, data-start), or null
     */
    fun guess(timeText: String, section: String, nowMillis: Long, zone: ZoneId, datetime: String? = null): Long? =
        try {
            guessInner(timeText.replace(' ', ' '), section.replace(' ', ' '), nowMillis, zone, datetime?.trim().orEmpty())
        } catch (_: Exception) {
            null
        }

    private fun guessInner(timeTextRaw: String, sectionRaw: String, nowMillis: Long, zone: ZoneId, datetime: String): Long? {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val today = now.toLocalDate()

        // 1. a full machine-readable stamp wins (datetime attribute first, then anywhere in the text)
        for (src in listOf(datetime, timeTextRaw)) {
            val m = ISO_FULL.find(src) ?: continue
            val (y, mo, d, h, mi, s, off) = m.destructured
            val ldt = LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.ifEmpty { "0" }.toInt())
            return if (off.isEmpty()) ldt.atZone(zone).toInstant().toEpochMilli()
            else OffsetDateTime.of(ldt, parseOffset(off)).toInstant().toEpochMilli()
        }

        // 2. "in 2 hours", "starts in 15 min"
        REL_IN.find(timeTextRaw)?.let { m ->
            val n = when (val q = m.groupValues[1].lowercase()) { "a", "an" -> 1.0; "half an" -> 0.5; else -> q.toDouble() }
            val u = m.groupValues[2].lowercase()
            val minutes = when {
                u.startsWith("min") || u == "m" -> n
                u.startsWith("h") -> n * 60
                else -> n * 60 * 24
            }
            return nowMillis + (minutes * 60_000).toLong()
        }

        // 3. time of day: from the text, else from the datetime attribute
        val time = timeOf(timeTextRaw) ?: timeOf(datetime) ?: timeOf(sectionRaw)
        // time text with the time cut out, so its digits cannot be mistaken for a date
        val dateSources = listOf(timeTextRaw, datetime, sectionRaw).map { stripTimes(it) }

        // 4. the date
        var date: LocalDate? = null
        var weekdayOnly: DayOfWeek? = null
        for (src in dateSources) {
            if (src.isBlank()) continue
            date = dateOf(src, today, nowMillis, zone, time)
            if (date != null) break
        }
        if (date == null) {
            for (src in dateSources) {
                val wd = WEEKDAY.find(src)?.let { weekday(it.groupValues[1]) }
                if (wd != null) { weekdayOnly = wd; break }
            }
        }
        if (time == null) return null
        if (date == null && weekdayOnly != null) {
            var d = today.plusDays(((weekdayOnly.value - today.dayOfWeek.value + 7) % 7).toLong())
            // the same weekday earlier today but long over means next week
            if (d == today && ZonedDateTime.of(d, time, zone).toInstant().toEpochMilli() < nowMillis - 4 * 3_600_000L) d = d.plusDays(7)
            date = d
        }
        if (date == null) return null
        return ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
    }

    private fun parseOffset(s: String): java.time.ZoneOffset {
        if (s.equals("Z", true)) return java.time.ZoneOffset.UTC
        val sign = if (s[0] == '-') -1 else 1
        val digits = s.substring(1).replace(":", "")
        val h = digits.substring(0, 2).toInt()
        val m = if (digits.length >= 4) digits.substring(2, 4).toInt() else 0
        return java.time.ZoneOffset.ofTotalSeconds(sign * (h * 3600 + m * 60))
    }

    private fun timeOf(text: String): LocalTime? {
        if (text.isBlank()) return null
        TIME12.find(text)?.let { m ->
            var h = m.groupValues[1].toInt()
            val mi = m.groupValues[2].ifEmpty { "0" }.toInt()
            val pm = m.groupValues[3].equals("p", true)
            if (h == 12) h = 0
            if (pm) h += 12
            return LocalTime.of(h, mi)
        }
        TIME24.find(text)?.let { m -> return LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
        if (Regex("""\bnoon\b""", OPT).containsMatchIn(text)) return LocalTime.NOON
        if (Regex("""\bmidnight\b""", OPT).containsMatchIn(text)) return LocalTime.MIDNIGHT
        return null
    }

    private fun stripTimes(text: String): String =
        text.replace(ISO_FULL) { m -> m.value.substring(0, 10) }
            .replace(TIME12, " ").replace(TIME24, " ")

    private fun month(name: String): Int = when (name.lowercase().take(3)) {
        "jan" -> 1; "feb" -> 2; "mar" -> 3; "apr" -> 4; "may" -> 5; "jun" -> 6
        "jul" -> 7; "aug" -> 8; "sep" -> 9; "oct" -> 10; "nov" -> 11; else -> 12
    }

    private fun weekday(name: String): DayOfWeek? = when (name.lowercase().take(3)) {
        "mon" -> DayOfWeek.MONDAY; "tue" -> DayOfWeek.TUESDAY; "wed" -> DayOfWeek.WEDNESDAY
        "thu" -> DayOfWeek.THURSDAY; "fri" -> DayOfWeek.FRIDAY; "sat" -> DayOfWeek.SATURDAY
        "sun" -> DayOfWeek.SUNDAY; else -> null
    }

    private fun dateOf(src: String, today: LocalDate, nowMillis: Long, zone: ZoneId, time: LocalTime?): LocalDate? {
        REL_DAY.find(src)?.let { m ->
            return when (m.groupValues[1].lowercase()) {
                "tomorrow" -> today.plusDays(1)
                "yesterday" -> today.minusDays(1)
                else -> today
            }
        }
        ISO_DATE.find(src)?.let { m -> return safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
        val weekday = WEEKDAY.find(src)?.let { weekday(it.groupValues[1]) }
        MONTH_DAY.find(src)?.let { m ->
            return withYear(month(m.groupValues[1]), m.groupValues[2].toInt(), m.groupValues[3], weekday, nowMillis, zone, time)
        }
        DAY_MONTH.find(src)?.let { m ->
            return withYear(month(m.groupValues[2]), m.groupValues[1].toInt(), m.groupValues[3], weekday, nowMillis, zone, time)
        }
        NUM_DATE.find(src)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            // month-first unless that is impossible (first number > 12)
            val (mo, d) = if (a > 12 && b <= 12) b to a else a to b
            if (mo !in 1..12) return null
            val y = m.groupValues[3]
            val yy = if (y.length == 2) "20$y" else y
            return withYear(mo, d, yy, weekday, nowMillis, zone, time)
        }
        return null
    }

    private fun safeDate(y: Int, mo: Int, d: Int): LocalDate? = try { LocalDate.of(y, mo, d) } catch (_: Exception) { null }

    /** With an explicit year use it; otherwise the year that puts the date nearest to now, within -6..+6 months. */
    private fun withYear(mo: Int, d: Int, year: String, weekday: DayOfWeek?, nowMillis: Long, zone: ZoneId, time: LocalTime?): LocalDate? {
        if (year.isNotEmpty()) return safeDate(year.toInt(), mo, d)
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val lo = now.minusMonths(6).toLocalDate()
        val hi = now.plusMonths(6).toLocalDate()
        val cands = (now.year - 1..now.year + 1).mapNotNull { safeDate(it, mo, d) }.filter { !it.isBefore(lo) && !it.isAfter(hi) }
        if (cands.isEmpty()) return null
        val byWeekday = if (weekday != null) cands.filter { it.dayOfWeek == weekday } else emptyList()
        val pool = byWeekday.ifEmpty { cands }
        val today = now.toLocalDate()
        return pool.minByOrNull { Math.abs(ChronoUnit.DAYS.between(today, it)) }
    }
}
