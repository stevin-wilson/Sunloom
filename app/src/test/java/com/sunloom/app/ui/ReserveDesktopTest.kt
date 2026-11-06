package com.sunloom.app.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Reserve desktop behaviour: list/grid toggle, Ctrl/Shift multi-select, batch actions, arrow keys, Ctrl+Z. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp")
class ReserveDesktopTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var ids: List<String>
    private val removedMany = mutableListOf<Collection<String>>()
    private var undoCount = 0
    private var rejectCount = 0

    private fun content(state: SunloomUiState) {
        val holder = mutableStateOf(state)
        composeTestRule.setContent {
            SunloomApp(
                uiState = holder.value,
                onCompleteSetup = {},
                onSelectTab = { holder.value = holder.value.copy(activeTab = it) },
                onNextWallpaper = {},
                onPreviousWallpaper = {},
                onToggleKeep = {},
                onRejectForever = { rejectCount++ },
                onTogglePause = {},
                onRestoreOriginal = {},
                onApplySpecific = {},
                onToggleLegibilityOverlay = {},
                onSyncOnline = {},
                onToggleCategory = {},
                onToggleSource = {},
                onSelectCity = {},
                onUpdateScheduleMode = {},
                onUpdateLockPolicy = {},
                onToggleBlurredFill = {},
                onToggleWifiOnly = {},
                onPickLocalPhoto = {},
                onDeleteManyFromReserve = { removedMany += it },
                onUndoDiscard = { undoCount++ }
            )
        }
        composeTestRule.waitForIdle()
    }

    private fun reserveState(pending: PendingDiscard? = null): SunloomUiState {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("sunloom_ui", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val initial = runBlocking { AndroidSunloomGateway(context).loadInitialSnapshot() }
        ids = initial.libraryWallpapers.map { it.id }
        return initial.copy(
            activeTab = DesktopNavTab.RESERVE_AND_HISTORY,
            preferences = initial.preferences.copy(isSetupCompleted = true),
            pendingDiscard = pending
        )
    }

    private fun ctrlClick(tag: String) {
        // The test input dispatcher shares one meta state between key and pointer injection, so a held
        // Ctrl key shows up in the pointer event's keyboardModifiers.
        composeTestRule.onRoot().performKeyInput { keyDown(Key.CtrlLeft) }
        composeTestRule.onNodeWithTag(tag).performScrollTo().performMouseInput { click() }
        composeTestRule.onRoot().performKeyInput { keyUp(Key.CtrlLeft) }
        composeTestRule.waitForIdle()
    }

    @Before
    fun resetCollectors() {
        removedMany.clear()
        undoCount = 0
        rejectCount = 0
    }

    @Test
    fun `reserve starts as a list and switches to a grid that is remembered`() {
        content(reserveState())
        composeTestRule.onNodeWithTag("reserve_list").assertExists()
        composeTestRule.onNodeWithTag("reserve_view_grid").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("reserve_grid").assertExists()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertTrue(UiPrefs.reserveGridView(context))
        composeTestRule.onNodeWithTag("reserve_view_list").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("reserve_list").assertExists()
    }

    @Test
    fun `ctrl click builds a selection and batch remove hands every id to one undoable action`() {
        content(reserveState())
        composeTestRule.onNodeWithTag("reserve_selection_bar").assertDoesNotExist()
        ctrlClick("reserve_card_${ids[0]}")
        ctrlClick("reserve_card_${ids[1]}")
        composeTestRule.onNodeWithTag("reserve_selection_bar").assertExists()

        composeTestRule.onNodeWithTag("reserve_selection_remove").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, removedMany.size)
        assertEquals(setOf(ids[0], ids[1]), removedMany.single().toSet())
        composeTestRule.onNodeWithTag("reserve_selection_bar").assertDoesNotExist()
    }

    @Test
    fun `clear button and Escape both drop the selection`() {
        content(reserveState())
        ctrlClick("reserve_card_${ids[0]}")
        composeTestRule.onNodeWithTag("reserve_selection_clear").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("reserve_selection_bar").assertDoesNotExist()

        ctrlClick("reserve_card_${ids[0]}")
        composeTestRule.onNodeWithTag("reserve_card_${ids[0]}").assertIsFocused()
        // Ctrl+click moves keyboard focus to the clicked card, so Esc reaches the Reserve pane.
        composeTestRule.onRoot().performKeyInput { pressKey(Key.Escape) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("reserve_selection_bar").assertDoesNotExist()
    }

    @Test
    fun `space toggles selection and arrow down moves focus to the next card`() {
        content(reserveState())
        composeTestRule.onNodeWithTag("reserve_card_${ids[0]}").performScrollTo().requestFocus()
        composeTestRule.onRoot().performKeyInput { pressKey(Key.Spacebar) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("reserve_selection_bar").assertExists()

        composeTestRule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("reserve_card_${ids[1]}").assertIsFocused()
    }

    @Test
    fun `delete with a selection removes the selection and never rejects the current wallpaper`() {
        content(reserveState())
        ctrlClick("reserve_card_${ids[0]}")
        ctrlClick("reserve_card_${ids[1]}")
        composeTestRule.onRoot().performKeyInput { pressKey(Key.Delete) }
        composeTestRule.waitForIdle()
        assertEquals(1, removedMany.size)
        assertEquals(setOf(ids[0], ids[1]), removedMany.single().toSet())
        assertEquals(0, rejectCount)
    }

    @Test
    fun `clicking a card button does not change the selection`() {
        content(reserveState())
        ctrlClick("reserve_card_${ids[0]}")
        composeTestRule.onNodeWithTag("reserve_selection_bar").assertExists()
        // The selection bar's own buttons live outside the cards, so use a card's Keep button instead.
        composeTestRule.onNodeWithTag("favorite_reserve_item_${ids[1]}").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("reserve_selection_bar").assertExists()
        composeTestRule.onNodeWithTag("reserve_selection_clear").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("reserve_selection_bar").assertDoesNotExist()
    }

    @Test
    fun `ctrl z undoes a pending dislike from the keyboard`() {
        val pending = PendingDiscard(1L, "x", "Alpine", DiscardKind.DISLIKE, wasCurrent = false)
        content(reserveState(pending))
        composeTestRule.onRoot().performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.Z)
            keyUp(Key.CtrlLeft)
        }
        composeTestRule.waitForIdle()
        assertEquals(1, undoCount)
    }
}
