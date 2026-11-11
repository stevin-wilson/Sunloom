package com.sunloom.app.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Someone who hits a problem can copy diagnostics or open a report in one click each (Article VIII §2, XII §1). */
@Requirement("XII.1")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h2000dp")
class ReportProblemComposeTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var copies = 0
    private var reports = 0

    private fun show() = composeTestRule.setContent {
        SunloomApp(
            uiState = SunloomUiState(activeTab = DesktopNavTab.SAFETY_AND_DECISION_LOG, preferences = UserPreferences(isSetupCompleted = true)),
            onCompleteSetup = {}, onSelectTab = {}, onNextWallpaper = {}, onPreviousWallpaper = {},
            onToggleKeep = {}, onRejectForever = {}, onTogglePause = {}, onRestoreOriginal = {},
            onApplySpecific = {}, onToggleLegibilityOverlay = {}, onSyncOnline = {}, onToggleCategory = {},
            onToggleSource = {}, onSelectCity = {}, onUpdateScheduleMode = {}, onUpdateLockPolicy = {},
            onToggleBlurredFill = {}, onToggleWifiOnly = {}, onPickLocalPhoto = {},
            onCopyDiagnostics = { copies++ }, onReportProblem = { reports++ },
            darkTheme = false
        )
    }

    @Test
    fun `copy diagnostics asks for the report`() {
        show()
        composeTestRule.onNodeWithTag("copy_diagnostics_button").performScrollTo().performClick()
        assertEquals(1, copies)
        assertEquals(0, reports)
    }

    @Test
    fun `report a problem opens the report`() {
        show()
        composeTestRule.onNodeWithTag("report_problem_button").performScrollTo().performClick()
        assertEquals(1, reports)
        assertEquals(0, copies)
    }
}
