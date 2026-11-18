package dev.toros.yde.desktop.ui.run

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** 受信箱のカードの提出時刻の書式（UX-52）。 */
class InboxTimeTest {
    @Test
    fun `提出時刻は 月と日と時分で出す`() {
        val zone = ZoneId.of("Asia/Tokyo")
        val at = ZonedDateTime.of(2026, 11, 8, 9, 5, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("11/8 09:05", inboxTime(at, zone))
    }
}
