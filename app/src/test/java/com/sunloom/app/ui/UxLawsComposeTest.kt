package com.sunloom.app.ui

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.R
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.UserPreferences
import kotlinx.coroutines.runBlocking
import com.sunloom.app.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Laws of UX (Constitution Article XII section 1) asserted against the rendered workspace:
 *  - Doherty threshold: slow work shows immediate, visible feedback.
 *  - Hick's law / progressive disclosure: rare options stay hidden until asked for.
 *  - Error prevention: bad input is explained inline and cannot be submitted.
 *  - Empty states offer the next step instead of a dead end.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h2000dp")
@Requirement("XII.1")
class UxLawsComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun base(tab: DesktopNavTab, prefs: UserPreferences = UserPreferences(isSetupCompleted = true)) =
        SunloomUiState(activeTab = tab, preferences = prefs)

    /** A real seeded library so Home has a current wallpaper and its controls render. */
    private fun withWallpaper(tab: DesktopNavTab): SunloomUiState {
        context.getSharedPreferences("sunloom_ui", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val loaded = runBlocking { AndroidSunloomGateway(context).loadInitialSnapshot() }
        val current = loaded.libraryWallpapers.first()
        return loaded.copy(
            activeTab = tab,
            currentWallpaper = current,
            preferences = loaded.preferences.copy(isSetupCompleted = true)
        )
    }

    // --- Hick's law ---

    @Test
    fun `primary navigation stays within five destinations`() {
        assertTrue("Ctrl+1..5 only addresses five tabs", DesktopNavTab.entries.size <= 5)
        composeTestRule.setSunloomApp { base(DesktopNavTab.TODAY_CANVAS) }
        for (tab in DesktopNavTab.entries) {
            composeTestRule.onNodeWithTag("nav_${tab.name}").assertExists()
        }
    }

    @Test
    fun `custom coordinates stay hidden until requested`() {
        val prefs = UserPreferences(isSetupCompleted = true, scheduleMode = ScheduleMode.SUNRISE_SUNSET)
        composeTestRule.setSunloomApp { base(DesktopNavTab.DISPLAYS_AND_SCHEDULE, prefs) }
        composeTestRule.onNodeWithTag("custom_lat_input").assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.custom_coordinates)).performClick()
        composeTestRule.onNodeWithTag("custom_lat_input").assertExists()
        composeTestRule.onNodeWithTag("custom_lon_input").assertExists()
    }

    // --- Error prevention ---

    @Test
    fun `invalid latitude is explained inline and cannot be applied`() {
        val prefs = UserPreferences(isSetupCompleted = true, scheduleMode = ScheduleMode.SUNRISE_SUNSET)
        composeTestRule.setSunloomApp { base(DesktopNavTab.DISPLAYS_AND_SCHEDULE, prefs) }
        composeTestRule.onNodeWithText(context.getString(R.string.custom_coordinates)).performClick()

        composeTestRule.onNodeWithTag("custom_lat_input").performTextReplacement("95")
        composeTestRule.onNodeWithTag("custom_lon_input").performTextReplacement("10")
        composeTestRule.waitForIdle()

        val message = coordinateError("95", -90.0, 90.0)?.message(context.resources)
        assertEquals("Must be between -90 and 90", message)
        composeTestRule.onNodeWithText(message!!).assertExists()
        composeTestRule.onNodeWithTag("apply_custom_coords_button").assertIsNotEnabled()
    }

    @Test
    fun `valid coordinates enable apply`() {
        val prefs = UserPreferences(isSetupCompleted = true, scheduleMode = ScheduleMode.SUNRISE_SUNSET)
        composeTestRule.setSunloomApp { base(DesktopNavTab.DISPLAYS_AND_SCHEDULE, prefs) }
        composeTestRule.onNodeWithText(context.getString(R.string.custom_coordinates)).performClick()

        composeTestRule.onNodeWithTag("custom_lat_input").performTextReplacement("37.77")
        composeTestRule.onNodeWithTag("custom_lon_input").performTextReplacement("-122.42")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("apply_custom_coords_button").assertIsEnabled()
    }

    // --- Doherty threshold ---

    @Test
    fun `applying a wallpaper is acknowledged immediately on the Next button`() {
        composeTestRule.setSunloomApp { withWallpaper(DesktopNavTab.TODAY_CANVAS).copy(isApplyingWallpaper = true) }
        composeTestRule.onNodeWithText(context.getString(R.string.applying)).assertExists()
    }

    @Test
    fun `an idle Home shows Next rather than a busy label`() {
        composeTestRule.setSunloomApp { withWallpaper(DesktopNavTab.TODAY_CANVAS) }
        composeTestRule.onNodeWithText(context.getString(R.string.applying)).assertDoesNotExist()
        composeTestRule.onNodeWithTag("next_wallpaper_button").assertIsEnabled()
    }

    @Test
    fun `catalog sync shows progress and blocks a duplicate request`() {
        composeTestRule.setSunloomApp { base(DesktopNavTab.SOURCES_AND_CATEGORIES).copy(isSyncingOnline = true) }
        composeTestRule.onNodeWithText(context.getString(R.string.syncing)).assertExists()
        composeTestRule.onNodeWithTag("sync_online_button").assertIsNotEnabled()
    }

    // --- Empty states ---

    @Test
    fun `empty Reserve offers fetch and add-photo actions`() {
        composeTestRule.setSunloomApp { base(DesktopNavTab.RESERVE_AND_HISTORY) }
        composeTestRule.onNodeWithTag("reserve_empty_state").assertExists()
        composeTestRule.onNodeWithTag("reserve_empty_fetch").assertIsEnabled()
        composeTestRule.onNode(
            hasText(context.getString(R.string.add_photo)) and hasAnyAncestor(hasTestTag("reserve_empty_state"))
        ).assertExists()
    }

    @Test
    fun `empty Home offers a fetch action`() {
        composeTestRule.setSunloomApp { base(DesktopNavTab.TODAY_CANVAS) }
        composeTestRule.onNodeWithTag("home_empty_fetch").assertIsEnabled()
    }

    @Test
    fun `empty Home shows progress while fetching`() {
        composeTestRule.setSunloomApp { base(DesktopNavTab.TODAY_CANVAS).copy(isSyncingOnline = true) }
        composeTestRule.onNodeWithText(context.getString(R.string.fetching)).assertExists()
        composeTestRule.onNodeWithTag("home_empty_fetch").assertIsNotEnabled()
    }
}
