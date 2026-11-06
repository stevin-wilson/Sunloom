package com.sunloom.app.ui

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CtrlActionTest {

    private fun action(
        key: Key,
        shift: Boolean = false,
        typing: Boolean = false,
        undo: Boolean = true,
        current: Boolean = true
    ) = ctrlActionFor(key, shift, typing, undo, current)

    @Test
    fun `ctrl z undoes only while an undoable action is pending`() {
        assertEquals(CtrlAction.UNDO, action(Key.Z))
        assertNull(action(Key.Z, undo = false))
        assertNull("redo chord is not claimed", action(Key.Z, shift = true))
    }

    @Test
    fun `text fields keep ctrl z c and s for themselves`() {
        assertNull(action(Key.Z, typing = true))
        assertNull(action(Key.C, typing = true))
        assertNull(action(Key.S, typing = true))
    }

    @Test
    fun `copy and save need a current wallpaper`() {
        assertEquals(CtrlAction.COPY_ATTRIBUTION, action(Key.C))
        assertEquals(CtrlAction.SAVE_AS, action(Key.S))
        assertNull(action(Key.C, current = false))
        assertNull(action(Key.S, current = false))
    }

    @Test
    fun `ctrl comma opens schedule even while typing`() {
        assertEquals(CtrlAction.OPEN_SCHEDULE, action(Key.Comma, typing = true))
    }

    @Test
    fun `unrelated chords pass through`() {
        assertNull(action(Key.N))
        assertNull(action(Key.W))
    }
}
