package com.sunloom.app.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.R
import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.UserPreferences
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Article XII §1: first-run setup is a short walk with a visible sense of progress, in plain words, where a person who
 * wants none of the choices can finish from the first step (Hick's law, the goal gradient, progressive disclosure).
 */
@Requirement("XII.1")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h2400dp")
class SetupStepperComposeTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var completed: UserPreferences? = null

    private fun show(prefs: UserPreferences = UserPreferences(isSetupCompleted = false), zone: String = "Europe/Berlin", reopened: Boolean = false) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDeviceZone provides ZoneId.of(zone)) {
                SunloomApp(
                    uiState = SunloomUiState(preferences = prefs, isSetupReopened = reopened),
                    onCompleteSetup = { completed = it }, onSelectTab = {}, onNextWallpaper = {}, onPreviousWallpaper = {},
                    onToggleKeep = {}, onRejectForever = {}, onTogglePause = {}, onRestoreOriginal = {},
                    onApplySpecific = {}, onToggleLegibilityOverlay = {}, onSyncOnline = {}, onToggleCategory = {},
                    onToggleSource = {}, onSelectCity = {}, onUpdateScheduleMode = {}, onUpdateLockPolicy = {},
                    onToggleBlurredFill = {}, onToggleWifiOnly = {}, onPickLocalPhoto = {},
                    darkTheme = false
                )
            }
        }
    }

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun tap(tag: String) = composeTestRule.onNodeWithTag(tag).performScrollTo().performClick()
    private fun see(id: Int, vararg args: Any) = composeTestRule.onNodeWithText(text(id, *args)).assertExists()
    private fun notSee(id: Int, vararg args: Any) = composeTestRule.onNodeWithText(text(id, *args)).assertDoesNotExist()

    // --- step 1: what it is, in plain words ---

    @Test
    fun `it opens with step one of four and a progress bar`() {
        show()

        see(R.string.setup_step_progress, 1, 4)
        composeTestRule.onNodeWithTag("setup_progress").assertExists()
        composeTestRule.onNodeWithTag("setup_back_button").assertDoesNotExist()
    }

    @Test
    fun `the first step says how it works, where pictures come from, the network use and privacy`() {
        show()

        see(R.string.setup_intro_how)
        see(R.string.setup_intro_sources)
        see(R.string.setup_intro_network)
        see(R.string.setup_intro_privacy)
    }

    @Test
    fun `the first step holds no schedule or category controls`() {
        show()

        composeTestRule.onNodeWithTag("setup_auto_switch").assertDoesNotExist()
        notSee(R.string.categories)
    }

    // --- the one-click way through ---

    @Test
    fun `start with defaults finishes at once, with the city guessed from the time zone`() {
        show(zone = "Europe/Berlin")

        tap("start_with_defaults_button")

        assertNotNull(completed)
        val prefs = completed!!
        assertEquals("Berlin, Germany", prefs.selectedCityName)
        assertTrue(prefs.autoChangeEnabled)
        assertTrue("downloads stay on unmetered networks", prefs.wifiOnlyDownloads)
        assertEquals(UserPreferences().enabledCategories, prefs.enabledCategories)
        assertFalse(prefs.weeklyUpdateCheck)
    }

    @Test
    fun `a city the person already chose is not replaced by the guess`() {
        show(UserPreferences(isSetupCompleted = false, selectedCityName = "Tokyo, Japan", latitude = 35.68, longitude = 139.69))

        tap("start_with_defaults_button")

        assertEquals("Tokyo, Japan", completed!!.selectedCityName)
    }

    // --- the walk ---

    @Test
    fun `customize leads to the schedule step, and back returns`() {
        show()

        tap("setup_customize_button")
        see(R.string.setup_step_progress, 2, 4)
        composeTestRule.onNodeWithTag("setup_auto_switch").assertExists()

        tap("setup_back_button")
        see(R.string.setup_step_progress, 1, 4)
    }

    @Test
    fun `the schedule step shows the guessed city and says where the guess came from`() {
        show(zone = "Europe/Berlin")

        tap("setup_customize_button")

        composeTestRule.onNodeWithText("Berlin, Germany", substring = true).assertExists()
        see(R.string.setup_city_guess_note)
    }

    @Test
    fun `the content step offers categories and where to apply, and explains why NASA is off`() {
        show()
        tap("setup_customize_button")
        tap("setup_next_button")

        see(R.string.setup_step_progress, 3, 4)
        see(R.string.categories)
        see(R.string.apply_wallpaper_to)
        see(R.string.setup_nasa_note)
    }

    @Test
    fun `the last step previews and starts, and the choices made on the way are what is saved`() {
        show()
        tap("setup_customize_button")
        composeTestRule.onNodeWithText(ScheduleMode.FIXED_CLOCK.label).performScrollTo().performClick()
        tap("setup_next_button")
        composeTestRule.onNodeWithText(SafeCategory.SPACE_ASTRONOMY.displayName).performScrollTo().performClick()
        tap("setup_next_button")

        see(R.string.setup_step_progress, 4, 4)
        see(R.string.initial_preview)
        see(R.string.start_sunloom)
        tap("enable_sunloom_button")

        val prefs = completed!!
        assertEquals(ScheduleMode.FIXED_CLOCK, prefs.scheduleMode)
        assertFalse("the category the person turned off", SafeCategory.SPACE_ASTRONOMY in prefs.enabledCategories)
        assertTrue(prefs.enabledCategories.isNotEmpty())
    }

    @Test
    fun `automatic switching turned off in setup changes the last button to open Sunloom`() {
        show()
        tap("setup_customize_button")
        tap("setup_auto_switch")
        tap("setup_next_button")
        tap("setup_next_button")

        see(R.string.open_sunloom)
        notSee(R.string.start_sunloom)
    }

    @Test
    fun `the last chosen category cannot be removed`() {
        show(UserPreferences(isSetupCompleted = false, enabledCategories = setOf(SafeCategory.LANDSCAPES_NATURE)))
        tap("setup_customize_button")
        tap("setup_next_button")

        composeTestRule.onNodeWithText(SafeCategory.LANDSCAPES_NATURE.displayName).performScrollTo().performClick()
        tap("setup_next_button")
        tap("enable_sunloom_button")

        assertEquals(setOf(SafeCategory.LANDSCAPES_NATURE), completed!!.enabledCategories)
    }

    @Test
    fun `reopened setup starts from the person's own choices`() {
        show(UserPreferences(isSetupCompleted = true, scheduleMode = ScheduleMode.FIXED_CLOCK, selectedCityName = "Tokyo, Japan"), reopened = true)

        tap("start_with_defaults_button")

        assertEquals("their schedule is kept, not reset to the default", ScheduleMode.FIXED_CLOCK, completed!!.scheduleMode)
        assertEquals("Tokyo, Japan", completed!!.selectedCityName)
    }
}
