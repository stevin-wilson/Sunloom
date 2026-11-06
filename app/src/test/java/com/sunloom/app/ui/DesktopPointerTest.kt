package com.sunloom.app.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.rightClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp")
class DesktopPointerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var applied: String? = null
    private var toggled: String? = null
    private var deleted: String? = null
    private var nextCount = 0
    private var rejectRequested = 0
    private lateinit var firstId: String
    private lateinit var secondId: String

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val initial = runBlocking { AndroidSunloomGateway(context).loadInitialSnapshot() }
        val ids = initial.libraryWallpapers.map { it.id }
        val activeId = initial.currentWallpaper?.id
        // Prefer a non-active first item so "Apply" is offered in the menu.
        firstId = ids.first { it != activeId }
        secondId = ids.first()
        val state = mutableStateOf(
            initial.copy(
                activeTab = DesktopNavTab.RESERVE_AND_HISTORY,
                preferences = initial.preferences.copy(isSetupCompleted = true)
            )
        )
        composeTestRule.setContent {
            SunloomApp(
                uiState = state.value,
                onCompleteSetup = {},
                onSelectTab = { state.value = state.value.copy(activeTab = it) },
                onNextWallpaper = { nextCount++ },
                onPreviousWallpaper = {},
                onToggleKeep = {},
                onRejectForever = {},
                onTogglePause = {},
                onRestoreOriginal = {},
                onApplySpecific = { applied = it },
                onDeleteFromReserve = { deleted = it },
                onToggleKeepSpecific = { toggled = it },
                onToggleLegibilityOverlay = {},
                onSyncOnline = {},
                onToggleCategory = {},
                onToggleSource = {},
                onSelectCity = {},
                onUpdateScheduleMode = {},
                onUpdateLockPolicy = {},
                onToggleBlurredFill = {},
                onToggleWifiOnly = {},
                onPickLocalPhoto = {}
            )
        }
        composeTestRule.waitForIdle()
    }

    private fun rightClickCard() {
        composeTestRule.onNodeWithTag("reserve_card_$firstId").performScrollTo()
            .performMouseInput { rightClick() }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `right click on a reserve card offers apply and runs it`() {
        rightClickCard()
        composeTestRule.onNodeWithTag("ctx_apply_$firstId").performClick()
        composeTestRule.waitForIdle()
        assertEquals(firstId, applied)
    }

    @Test
    fun `right click favorite toggles the right item`() {
        rightClickCard()
        composeTestRule.onNodeWithTag("ctx_favorite_$firstId").performClick()
        composeTestRule.waitForIdle()
        assertEquals(firstId, toggled)
    }

    @Test
    fun `right click remove acts immediately without a blocking dialog`() {
        rightClickCard()
        composeTestRule.onNodeWithTag("ctx_delete_$firstId").performClick()
        composeTestRule.waitForIdle()
        assertEquals(firstId, deleted)
        composeTestRule.onNodeWithTag("confirm_delete_reserve_button").assertDoesNotExist()
    }

    @Test
    fun `right click about image opens provenance dialog`() {
        rightClickCard()
        composeTestRule.onNodeWithTag("ctx_about_$firstId").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("wallpaper_about_dialog").assertExists()
        composeTestRule.onNodeWithTag("wallpaper_about_close").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("wallpaper_about_dialog").assertDoesNotExist()
    }

    @Test
    fun `menu is closed until the card is right clicked`() {
        composeTestRule.onNodeWithTag("ctx_apply_$firstId").assertDoesNotExist()
        composeTestRule.onNodeWithTag("ctx_delete_$firstId").assertDoesNotExist()
        assertNull(applied)
    }
}
