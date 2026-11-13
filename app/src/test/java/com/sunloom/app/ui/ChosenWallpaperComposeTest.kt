package com.sunloom.app.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.rightClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.R
import com.sunloom.app.Requirement
import com.sunloom.app.domain.matcher.ChosenSlot
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.UserPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Article III §1 / XII (v0.59): the Schedule shows a "Daytime wallpaper" and an "Evening wallpaper" card, each
 * Automatic or a chosen picture, and any picture's menu can set them.
 */
@Requirement("XII.1")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h2000dp")
class ChosenWallpaperComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var initial: SunloomUiState
    private val calls = mutableListOf<Pair<ChosenSlot, String?>>()

    @Before
    fun setUp() {
        initial = runBlocking { AndroidSunloomGateway(context).loadInitialSnapshot() }
    }

    private fun setApp(
        tab: DesktopNavTab = DesktopNavTab.DISPLAYS_AND_SCHEDULE,
        day: String? = null,
        evening: String? = null,
        auto: Boolean = true,
        mode: ScheduleMode = ScheduleMode.SUNRISE_SUNSET
    ) = composeTestRule.setContent {
        SunloomApp(
            uiState = initial.copy(
                activeTab = tab,
                preferences = UserPreferences(
                    isSetupCompleted = true, autoChangeEnabled = auto, scheduleMode = mode,
                    daytimePickId = day, eveningPickId = evening
                )
            ),
            onCompleteSetup = {}, onSelectTab = {}, onNextWallpaper = {}, onPreviousWallpaper = {},
            onToggleKeep = {}, onRejectForever = {}, onTogglePause = {}, onRestoreOriginal = {},
            onApplySpecific = {}, onToggleLegibilityOverlay = {}, onSyncOnline = {}, onToggleCategory = {},
            onToggleSource = {}, onSelectCity = {}, onUpdateScheduleMode = {}, onUpdateLockPolicy = {},
            onToggleBlurredFill = {}, onToggleWifiOnly = {}, onPickLocalPhoto = {},
            onSetChosenWallpaper = { slot, id -> calls += slot to id },
            darkTheme = false
        )
    }

    private fun node(tag: String) = composeTestRule.onNodeWithTag(tag).also { it.performScrollTo() }

    // --- the two cards ---

    @Test
    fun `both cards are shown and say Automatic when nothing is chosen`() {
        setApp()

        node("chosen_title_daytime").assertTextContains(context.getString(R.string.chosen_daytime_title))
        node("chosen_title_evening").assertTextContains(context.getString(R.string.chosen_evening_title))
        node("chosen_state_daytime").assertTextContains(context.getString(R.string.chosen_automatic), substring = true)
        node("chosen_state_evening").assertTextContains(context.getString(R.string.chosen_automatic), substring = true)
        composeTestRule.onNodeWithTag("chosen_clear_daytime").assertDoesNotExist()
    }

    @Test
    fun `a chosen picture is named on its card and can be cleared`() {
        val pick = initial.libraryWallpapers.first()
        setApp(day = pick.id)

        node("chosen_state_daytime").assertTextContains(pick.title, substring = true)
        node("chosen_clear_daytime").assertIsDisplayed().performClick()

        assertEquals(listOf(ChosenSlot.DAYTIME to null), calls)
    }

    @Test
    fun `clearing the evening card clears the evening slot`() {
        val pick = initial.libraryWallpapers.first()
        setApp(evening = pick.id)

        node("chosen_clear_evening").performClick()

        assertEquals(listOf(ChosenSlot.EVENING to null), calls)
    }

    @Test
    fun `a chosen picture that is no longer there says so and can be cleared`() {
        setApp(day = "gone-forever")

        node("chosen_state_daytime").assertTextContains(context.getString(R.string.chosen_unavailable), substring = true)
        node("chosen_clear_daytime").performClick()

        assertEquals(listOf(ChosenSlot.DAYTIME to null), calls)
    }

    @Test
    fun `each card says when its picture is shown for the current schedule`() {
        setApp(mode = ScheduleMode.SUNRISE_SUNSET)
        node("chosen_when_daytime").assertTextContains(context.getString(R.string.chosen_when_daytime_sun), substring = true)
        node("chosen_when_evening").assertTextContains(context.getString(R.string.chosen_when_evening_sun), substring = true)
    }

    @Test
    fun `following the system theme the cards say so`() {
        setApp(mode = ScheduleMode.FOLLOW_OS_THEME)
        node("chosen_when_daytime").assertTextContains(context.getString(R.string.chosen_when_daytime_os), substring = true)
        node("chosen_when_evening").assertTextContains(context.getString(R.string.chosen_when_evening_os), substring = true)
    }

    @Test
    fun `with automatic switching off the cards say why a picture is not used yet`() {
        setApp(auto = false)

        node("chosen_note").assertTextContains(context.getString(R.string.change_every_needs_automatic), substring = true)
    }

    @Test
    fun `when it can work no reason is shown, and small pictures are mentioned once`() {
        setApp()

        composeTestRule.onNodeWithTag("chosen_note").assertDoesNotExist()
        node("chosen_small_picture_hint").assertTextContains(context.getString(R.string.chosen_small_picture_hint), substring = true)
    }

    // --- the entry points ---

    private fun reserveMenu(): String {
        val id = initial.libraryWallpapers.first().id
        composeTestRule.onNodeWithTag("reserve_card_$id").performScrollTo().performMouseInput { rightClick() }
        composeTestRule.waitForIdle()
        return id
    }

    @Test
    fun `a reserve picture's menu sets the daytime wallpaper`() {
        setApp(tab = DesktopNavTab.RESERVE_AND_HISTORY)
        val id = reserveMenu()

        composeTestRule.onNodeWithTag("ctx_use_daytime_$id").performClick()

        assertEquals(listOf(ChosenSlot.DAYTIME to id), calls)
    }

    @Test
    fun `a reserve picture's menu sets the evening wallpaper`() {
        setApp(tab = DesktopNavTab.RESERVE_AND_HISTORY)
        val id = reserveMenu()

        composeTestRule.onNodeWithTag("ctx_use_evening_$id").performClick()

        assertEquals(listOf(ChosenSlot.EVENING to id), calls)
    }

    @Test
    fun `the Today picture's menu sets the evening wallpaper to the current picture`() {
        setApp(tab = DesktopNavTab.TODAY_CANVAS)
        val current = initial.currentWallpaper!!
        composeTestRule.onNodeWithTag("today_preview").performMouseInput { rightClick() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("ctx_use_evening").performClick()

        assertEquals(listOf(ChosenSlot.EVENING to current.id), calls)
    }

    @Test
    fun `nothing is reported until a menu item is used`() {
        setApp()
        assertNull(calls.firstOrNull())
    }
}
