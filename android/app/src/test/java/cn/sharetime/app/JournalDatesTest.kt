package cn.sharetime.app

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class JournalDatesTest {
    @Test fun pickerDateDoesNotShiftWithTimeZone() {
        val date = LocalDate.of(2026, 10, 29)
        assertEquals(date, JournalDates.fromPicker(JournalDates.pickerMillis(date)))
        assertEquals(date, JournalDates.fromPicker(Instant.parse("2026-10-29T00:00:00Z").toEpochMilli()))
    }
    @Test fun dailyCountUsesRecordedDayAndLocalBoundary() {
        val instant = Instant.parse("2026-10-28T16:01:00Z").toEpochMilli()
        assertEquals(LocalDate.of(2026, 10, 29), JournalDates.day(instant, ZoneId.of("Asia/Shanghai")))
        val rows = listOf(JournalEntry("a", 1, "2026-10-28", "A"), JournalEntry("b", 2, "2026-10-29", "A"))
        assertEquals(2, JournalDates.ordinal(rows, "2026-10-29"))
        assertEquals(1, JournalDates.ordinal(rows, "2026-10-30"))
        assertEquals(3, JournalDates.ordinal(listOf(JournalEntry("later", 3, "2026-10-29", "A", 2)), "2026-10-29"))
    }
}
