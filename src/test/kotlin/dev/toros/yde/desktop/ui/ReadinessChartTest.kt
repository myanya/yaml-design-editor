package dev.toros.yde.desktop.ui

import dev.toros.yde.app.validate.Readiness
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadinessChartTest {
    @Test
    fun `groups become categories with ready and pending series`() {
        val data = readinessChartData(listOf(
            Readiness.GroupRow("domain", total = 5, ready = 3),
            Readiness.GroupRow("table", total = 2, ready = 2),
            Readiness.GroupRow("-", total = 1, ready = 0),
        ))
        assertEquals(listOf("domain", "table", "（なし）"), data.categories)
        assertEquals(listOf(3.0, 2.0, 0.0), data.ready)
        assertEquals(listOf(2.0, 0.0, 1.0), data.pending)
    }

    @Test
    fun `empty groups give empty series`() {
        val data = readinessChartData(emptyList())
        assertEquals(emptyList(), data.categories)
        assertEquals(emptyList(), data.ready)
    }
}
