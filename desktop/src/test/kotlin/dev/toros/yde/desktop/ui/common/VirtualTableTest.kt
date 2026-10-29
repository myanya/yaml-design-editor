package dev.toros.yde.desktop.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VirtualTableTest {
    private val ten = List(10) { 100 }

    @Test
    fun `visible range follows the scroll position with one column of overscan`() {
        assertEquals(0..3, visibleColumnRange(ten, scrollPx = 0, viewportPx = 250))
        assertEquals(2..6, visibleColumnRange(ten, scrollPx = 320, viewportPx = 250))
        assertEquals(6..9, visibleColumnRange(ten, scrollPx = 750, viewportPx = 250))
    }

    @Test
    fun `unmeasured viewport composes every column and empty columns give an empty range`() {
        assertEquals(0..9, visibleColumnRange(ten, scrollPx = 0, viewportPx = 0))
        assertTrue(visibleColumnRange(emptyList(), scrollPx = 0, viewportPx = 300).isEmpty())
    }

    @Test
    fun `scrolling past the end keeps the last column`() {
        assertEquals(8..9, visibleColumnRange(ten, scrollPx = 5000, viewportPx = 250))
    }

    @Test
    fun `uneven widths`() {
        val widths = listOf(50, 300, 20, 400)
        assertEquals(0..2, visibleColumnRange(widths, scrollPx = 0, viewportPx = 200))
        assertEquals(1..3, visibleColumnRange(widths, scrollPx = 360, viewportPx = 100))
    }
}
