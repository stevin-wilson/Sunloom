package com.sunloom.app.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.rightClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.SourceIds
import com.sunloom.app.domain.model.WallpaperRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Someone who finds an inappropriate picture can report it from the places they already use to reject or inspect it:
 * "Dislike and report…" next to Dislike, and "Report this image…" in About and on Reserve cards. Plain Dislike is
 * unchanged, and the user's own photos never offer a report (Constitution Article VII §11, XII §3).
 */
@Requirement("VII.11", "XII.3")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h2000dp")
class ReportImageComposeTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val reported = mutableListOf<String>()
    private var rejected = 0

    private fun loaded(): SunloomUiState = runBlocking { AndroidSunloomGateway(context).loadInitialSnapshot() }

    private fun show(tab: DesktopNavTab, current: WallpaperRecord?, library: List<WallpaperRecord>? = null) {
        val base = loaded()
        val state = base.copy(
            activeTab = tab,
            currentWallpaper = current,
            libraryWallpapers = library ?: base.libraryWallpapers,
            preferences = base.preferences.copy(isSetupCompleted = true)
        )
        composeTestRule.setContent {
            SunloomApp(
                uiState = state,
                actions = SunloomActions.None.copy(
                    onRejectForever = { rejected++ },
                    onReportImage = { reported += it.id }
                ),
                darkTheme = false
            )
        }
        composeTestRule.waitForIdle()
    }

    private fun fromSource(record: WallpaperRecord) = record.copy(provenance = record.provenance.copy(sourceId = "met_museum_cc0"))
    private fun usersOwn(record: WallpaperRecord) = record.copy(provenance = record.provenance.copy(sourceId = SourceIds.USER_LOCAL))

    @Test
    fun `the More menu offers dislike and report and hands over the current picture`() {
        val current = fromSource(loaded().libraryWallpapers.first())
        show(DesktopNavTab.TODAY_CANVAS, current)

        composeTestRule.onNodeWithTag("today_more_button").performClick()
        composeTestRule.onNodeWithTag("more_dislike_and_report").performClick()

        assertEquals(listOf(current.id), reported)
        assertEquals("the report does the rejecting itself, once", 0, rejected)
    }

    @Test
    fun `the right-click menu on the current picture offers dislike and report`() {
        val current = fromSource(loaded().libraryWallpapers.first())
        show(DesktopNavTab.TODAY_CANVAS, current)

        composeTestRule.onNodeWithTag("today_preview").performMouseInput { rightClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("ctx_dislike").assertExists()
        composeTestRule.onNodeWithTag("ctx_dislike_and_report").performClick()

        assertEquals(listOf(current.id), reported)
    }

    @Test
    fun `About this image offers to report a picture from a source`() {
        val current = fromSource(loaded().libraryWallpapers.first())
        show(DesktopNavTab.TODAY_CANVAS, current)

        composeTestRule.onNodeWithTag("today_more_button").performClick()
        composeTestRule.onNodeWithTag("more_about").performClick()
        composeTestRule.onNodeWithTag("wallpaper_about_report").performClick()

        assertEquals(listOf(current.id), reported)
        composeTestRule.onNodeWithTag("wallpaper_about_dialog").assertDoesNotExist()
    }

    @Test
    fun `a Reserve card offers to report its picture`() {
        val base = loaded()
        val item = fromSource(base.libraryWallpapers.first())
        show(DesktopNavTab.RESERVE_AND_HISTORY, current = null, library = listOf(item))

        composeTestRule.onNodeWithTag("reserve_card_${item.id}").performScrollTo().performMouseInput { rightClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("ctx_report_${item.id}").performClick()

        assertEquals(listOf(item.id), reported)
    }

    @Test
    fun `the user's own photo offers no report anywhere`() {
        val own = usersOwn(loaded().libraryWallpapers.first())
        show(DesktopNavTab.TODAY_CANVAS, own)

        composeTestRule.onNodeWithTag("today_preview").performMouseInput { rightClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("ctx_dislike").assertExists()
        composeTestRule.onNodeWithTag("ctx_dislike_and_report").assertDoesNotExist()
    }

    @Test
    fun `the user's own photo has no report in the More menu or in About`() {
        val own = usersOwn(loaded().libraryWallpapers.first())
        show(DesktopNavTab.TODAY_CANVAS, own)

        composeTestRule.onNodeWithTag("today_more_button").performClick()
        composeTestRule.onNodeWithTag("more_dislike_and_report").assertDoesNotExist()
        composeTestRule.onNodeWithTag("more_about").performClick()
        composeTestRule.onNodeWithTag("wallpaper_about_dialog").assertExists()
        composeTestRule.onNodeWithTag("wallpaper_about_report").assertDoesNotExist()
    }

    @Test
    fun `a Reserve card for the user's own photo offers no report`() {
        val own = usersOwn(loaded().libraryWallpapers.first())
        show(DesktopNavTab.RESERVE_AND_HISTORY, current = null, library = listOf(own))

        composeTestRule.onNodeWithTag("reserve_card_${own.id}").performScrollTo().performMouseInput { rightClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("ctx_delete_${own.id}").assertExists()
        composeTestRule.onNodeWithTag("ctx_report_${own.id}").assertDoesNotExist()
    }
}
