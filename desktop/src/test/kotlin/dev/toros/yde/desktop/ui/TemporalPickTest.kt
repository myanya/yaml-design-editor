package dev.toros.yde.desktop.ui

import dev.toros.yde.semantic.schema.ValueSemantics
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TemporalPickTest {
    private val today = LocalDate.of(2026, 10, 11)

    @Test
    fun `date round trips through the picker's epoch millis`() {
        val d = LocalDate.of(2026, 10, 1)
        assertEquals(d, TemporalPick.fromEpochMillis(TemporalPick.toEpochMillis(d)))
    }

    @Test
    fun `date uses the picked day, else the previous value, else empty`() {
        assertEquals("2026-10-01", TemporalPick.compose(ValueSemantics.DATE, LocalDate.of(2026, 10, 1), null, "2020-01-01", today))
        assertEquals("2026-01-05", TemporalPick.compose(ValueSemantics.DATE, null, null, "2026-01-05", today))
        assertEquals("", TemporalPick.compose(ValueSemantics.DATE, null, null, "", today))
    }

    @Test
    fun `time is HH mm and keeps a seconds shape when the previous value had one`() {
        assertEquals("07:05", TemporalPick.compose(ValueSemantics.TIME, null, LocalTime.of(7, 5), "", today))
        assertEquals("07:05:00", TemporalPick.compose(ValueSemantics.TIME, null, LocalTime.of(7, 5), "09:00:30", today))
    }

    @Test
    fun `datetime falls back to the previous date, then today`() {
        assertEquals("2026-01-05 09:30:00",
            TemporalPick.compose(ValueSemantics.DATETIME, null, LocalTime.of(9, 30), "2026-01-05 08:00:15", today))
        assertEquals("2026-10-11 09:30",
            TemporalPick.compose(ValueSemantics.DATETIME, null, LocalTime.of(9, 30), "", today))
        assertEquals("2026-03-03 08:00",
            TemporalPick.compose(ValueSemantics.DATETIME, LocalDate.of(2026, 3, 3), null, "2026-01-05 08:00", today))
    }

    @Test
    fun `parts are read from the canonical text and ignore unreadable values`() {
        assertEquals(LocalDate.of(2026, 1, 5), TemporalPick.datePart(ValueSemantics.DATETIME, "2026-01-05 08:00"))
        assertEquals(LocalTime.of(8, 0), TemporalPick.timePart(ValueSemantics.DATETIME, "2026-01-05T08:00"))
        assertNull(TemporalPick.datePart(ValueSemantics.DATE, "来週"))
        assertNull(TemporalPick.timePart(ValueSemantics.DATE, "2026-01-05"))
    }
}
