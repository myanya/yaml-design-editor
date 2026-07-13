package ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiffPaneTest {
    @Test
    fun `inserted line does not shift following lines`() {
        val diff = calculateDiff("one\ntwo\nthree", "one\nnew\ntwo\nthree")

        assertFalse(diff[0].changed)
        assertEquals("new", diff[1].edited)
        assertNull(diff[1].disk)
        assertTrue(diff[1].changed)
        assertEquals(listOf("two", "three"), diff.drop(2).map { it.disk })
        assertTrue(diff.drop(2).all { !it.changed })
    }

    @Test
    fun `deleted line does not shift following lines`() {
        val diff = calculateDiff("one\nold\ntwo\nthree", "one\ntwo\nthree")

        assertEquals("old", diff[1].disk)
        assertNull(diff[1].edited)
        assertEquals(listOf("two", "three"), diff.drop(2).map { it.edited })
        assertTrue(diff.drop(2).all { !it.changed })
    }

    @Test
    fun `replacement is displayed on one aligned row`() {
        val diff = calculateDiff("one\nold\nthree", "one\nnew\nthree")

        assertEquals("old", diff[1].disk)
        assertEquals("new", diff[1].edited)
        assertTrue(diff[1].changed)
        assertFalse(diff[2].changed)
    }

    @Test
    fun `duplicate lines remain aligned around insertion`() {
        val diff = calculateDiff("item\nitem\ntail", "item\nadded\nitem\ntail")

        assertEquals(1, diff.count { it.changed })
        assertEquals("added", diff.single { it.changed }.edited)
        assertEquals("tail", diff.last().disk)
        assertFalse(diff.last().changed)
    }

    @Test
    fun `inserted blank line is classified as a quiet change`() {
        val diff = calculateDiff("one\ntwo", "one\n\ntwo")

        val blankLine = diff.single { it.changed }
        assertTrue(blankLine.blankOnlyChange)
        assertNull(blankLine.disk)
        assertEquals("", blankLine.edited)
    }

    @Test
    fun `content replacement is not classified as a blank-only change`() {
        val diff = calculateDiff("one\nold\nthree", "one\n\nthree")

        assertFalse(diff.single { it.changed }.blankOnlyChange)
    }
}
