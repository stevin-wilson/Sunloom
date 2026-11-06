package com.sunloom.app.ui

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReserveSelectionTest {

    private val order = listOf("a", "b", "c", "d", "e", "f")

    @Test
    fun `list navigation moves one row and clamps at the ends`() {
        assertEquals(1, reserveNavTarget(NavMove.DOWN, 0, 6, 1))
        assertEquals(0, reserveNavTarget(NavMove.UP, 0, 6, 1))
        assertEquals(5, reserveNavTarget(NavMove.DOWN, 5, 6, 1))
        assertNull("left/right do nothing in a single column", reserveNavTarget(NavMove.LEFT, 2, 6, 1))
    }

    @Test
    fun `grid navigation moves by column and by row`() {
        assertEquals(1, reserveNavTarget(NavMove.RIGHT, 0, 6, 3))
        assertEquals(3, reserveNavTarget(NavMove.DOWN, 0, 6, 3))
        assertEquals(1, reserveNavTarget(NavMove.UP, 4, 6, 3))
        assertEquals(5, reserveNavTarget(NavMove.DOWN, 4, 6, 3))
    }

    @Test
    fun `home end and paging clamp to valid indices`() {
        assertEquals(0, reserveNavTarget(NavMove.HOME, 4, 6, 1))
        assertEquals(5, reserveNavTarget(NavMove.END, 1, 6, 1))
        assertEquals(5, reserveNavTarget(NavMove.PAGE_DOWN, 3, 6, 1))
        assertEquals(0, reserveNavTarget(NavMove.PAGE_UP, 2, 6, 1))
        assertNull(reserveNavTarget(NavMove.DOWN, 0, 0, 1))
        assertNull(reserveNavTarget(NavMove.DOWN, 9, 6, 1))
    }

    @Test
    fun `navigation keys map to moves`() {
        assertEquals(NavMove.DOWN, navMoveFor(Key.DirectionDown))
        assertEquals(NavMove.END, navMoveFor(Key.MoveEnd))
        assertNull(navMoveFor(Key.A))
    }

    @Test
    fun `ctrl click toggles individual items`() {
        var s = selectionAfterPress(SelectionState(), order, "b", ctrl = true, shift = false, isTouch = false)
        assertEquals(setOf("b"), s.selected)
        s = selectionAfterPress(s, order, "d", ctrl = true, shift = false, isTouch = false)
        assertEquals(setOf("b", "d"), s.selected)
        s = selectionAfterPress(s, order, "b", ctrl = true, shift = false, isTouch = false)
        assertEquals(setOf("d"), s.selected)
    }

    @Test
    fun `shift click selects the range from the anchor in either direction`() {
        var s = selectionAfterPress(SelectionState(), order, "b", ctrl = true, shift = false, isTouch = false)
        s = selectionAfterPress(s, order, "e", ctrl = false, shift = true, isTouch = false)
        assertEquals(setOf("b", "c", "d", "e"), s.selected)
        s = selectionAfterPress(s, order, "a", ctrl = false, shift = true, isTouch = false)
        assertEquals(setOf("a", "b"), s.selected)
    }

    @Test
    fun `ctrl shift click adds a range to the existing selection`() {
        var s = selectionAfterPress(SelectionState(), order, "a", ctrl = true, shift = false, isTouch = false)
        s = selectionAfterPress(s.copy(anchorId = "d"), order, "f", ctrl = true, shift = true, isTouch = false)
        assertEquals(setOf("a", "d", "e", "f"), s.selected)
    }

    @Test
    fun `plain mouse press clears but plain touch tap toggles while selecting`() {
        val selecting = SelectionState(setOf("a", "b"), "b")
        assertEquals(emptySet<String>(), selectionAfterPress(selecting, order, "c", false, false, isTouch = false).selected)
        assertEquals(setOf("a", "b", "c"), selectionAfterPress(selecting, order, "c", false, false, isTouch = true).selected)
        assertEquals(SelectionState(), selectionAfterPress(SelectionState(), order, "c", false, false, isTouch = false))
    }

    @Test
    fun `selection drops items that disappeared`() {
        val pruned = pruneSelection(SelectionState(setOf("a", "x"), "x"), setOf("a", "b"))
        assertEquals(setOf("a"), pruned.selected)
        assertNull(pruned.anchorId)
    }
}
