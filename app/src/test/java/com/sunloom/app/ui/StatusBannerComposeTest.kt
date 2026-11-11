package com.sunloom.app.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import com.sunloom.app.domain.model.ScheduleMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A status message that does not fit its line must not be cut off for good: the person can open it and read all of it,
 * and a sync that failed can be tried again from the same place (Constitution Article XII §1).
 */
@Requirement("XII.1")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w420dp-h900dp")
class StatusBannerComposeTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val longMessage = "Online sync interrupted: " + "the connection dropped while reading the catalogue. ".repeat(6)

    private fun show(state: SunloomUiState, onSyncOnline: () -> Unit = {}, onApplySpecific: (String) -> Unit = {}) = composeTestRule.setContent {
        SunloomApp(
            uiState = state,
            onCompleteSetup = {}, onSelectTab = {}, onNextWallpaper = {}, onPreviousWallpaper = {},
            onToggleKeep = {}, onRejectForever = {}, onTogglePause = {}, onRestoreOriginal = {},
            onApplySpecific = onApplySpecific, onToggleLegibilityOverlay = {}, onSyncOnline = onSyncOnline, onToggleCategory = {},
            onToggleSource = {}, onSelectCity = {}, onUpdateScheduleMode = {}, onUpdateLockPolicy = {},
            onToggleBlurredFill = {}, onToggleWifiOnly = {}, onPickLocalPhoto = {},
            darkTheme = false
        )
    }

    private fun state(message: String = "Ready", canRetrySync: Boolean = false, applyOffer: String? = null) = SunloomUiState(
        applyOfferRecordId = applyOffer,
        activeTab = DesktopNavTab.TODAY_CANVAS,
        preferences = UserPreferences(isSetupCompleted = true),
        statusBannerMessage = message,
        canRetrySync = canRetrySync
    )

    private fun messageState() = composeTestRule.onNodeWithTag("status_banner_message")
        .fetchSemanticsNode().config[SemanticsProperties.StateDescription]

    @Test
    fun `a message opens when tapped and closes again, and says which it is`() {
        show(state(longMessage))
        val collapsed = messageState()

        composeTestRule.onNodeWithTag("status_banner_message").performClick()
        val expanded = messageState()
        assertNotEquals(collapsed, expanded)

        composeTestRule.onNodeWithTag("status_banner_message").performClick()
        assertEquals(collapsed, messageState())
    }

    @Test
    fun `a new message starts closed`() {
        val message = mutableStateOf(longMessage)
        composeTestRule.setContent {
            SunloomApp(
                uiState = state(message.value),
                onCompleteSetup = {}, onSelectTab = {}, onNextWallpaper = {}, onPreviousWallpaper = {},
                onToggleKeep = {}, onRejectForever = {}, onTogglePause = {}, onRestoreOriginal = {},
                onApplySpecific = {}, onToggleLegibilityOverlay = {}, onSyncOnline = {}, onToggleCategory = {},
                onToggleSource = {}, onSelectCity = {}, onUpdateScheduleMode = {}, onUpdateLockPolicy = {},
                onToggleBlurredFill = {}, onToggleWifiOnly = {}, onPickLocalPhoto = {},
                darkTheme = false
            )
        }
        val collapsed = messageState()
        composeTestRule.onNodeWithTag("status_banner_message").performClick()
        message.value = "Something else happened."
        composeTestRule.waitForIdle()
        assertEquals(collapsed, messageState())
    }

    @Test
    fun `a failed sync shows Try again and tapping it syncs again`() {
        var syncs = 0
        show(state(canRetrySync = true), onSyncOnline = { syncs++ })

        composeTestRule.onNodeWithTag("retry_sync_button").performClick()
        assertEquals(1, syncs)
    }

    @Test
    fun `a bar shows while photos are being checked`() {
        show(state().copy(importProgress = ImportProgress(1, 3)))
        composeTestRule.onNodeWithTag("import_progress").assertExists()
    }

    @Test
    fun `no bar when nothing is running`() {
        show(state())
        composeTestRule.onNodeWithTag("import_progress").assertDoesNotExist()
    }

    @Test
    fun `a photo that was just added can be applied from the banner`() {
        val applied = mutableListOf<String>()
        show(state(applyOffer = "rec-9"), onApplySpecific = { applied += it })

        composeTestRule.onNodeWithTag("apply_now_button").performClick()
        assertEquals(listOf("rec-9"), applied)
    }

    @Test
    fun `no apply offer unless a photo was just added`() {
        show(state())
        composeTestRule.onNodeWithTag("apply_now_button").assertDoesNotExist()
    }

    private fun noteFor(mode: ScheduleMode) = run {
        show(state().copy(preferences = UserPreferences(isSetupCompleted = true, scheduleMode = mode, fixedLightHour = 7, fixedDarkHour = 19)))
        composeTestRule.onNodeWithTag("status_banner_note").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
    }

    @Test
    fun `the note under the message fits the schedule that is in use`() {
        assertTrue(noteFor(ScheduleMode.SUNRISE_SUNSET).contains("Sunrise"))
    }

    @Test
    fun `follow system theme does not talk about the sun`() {
        val note = noteFor(ScheduleMode.FOLLOW_OS_THEME)
        assertTrue(note, note.contains("system theme") && !note.contains("Sunrise"))
    }

    @Test
    fun `a custom schedule shows its own hours`() {
        val note = noteFor(ScheduleMode.FIXED_CLOCK)
        assertTrue(note, note.contains("07:00") && note.contains("19:00") && !note.contains("Sunrise"))
    }

    @Test
    fun `manual mode says changes happen when asked`() {
        val note = noteFor(ScheduleMode.MANUAL_ONLY)
        assertTrue(note, note.contains("Next") && !note.contains("Sunrise"))
    }

    @Test
    fun `no retry button when the last sync did not fail`() {
        show(state())
        composeTestRule.onNodeWithTag("retry_sync_button").assertDoesNotExist()
    }
}
