package com.sunloom.app.diagnostics

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.PlatformCapabilities
import com.sunloom.app.domain.model.SourceDiagnostics
import com.sunloom.app.domain.model.SourceHealthState
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.ui.SunloomUiState
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a person can copy into a bug report: enough to see what went wrong, and nothing about who or where they are
 * (Article VIII §2).
 */
@Requirement("VIII.2")
class DiagnosticsReportTest {
    private val info = AppInfo("1.7.0", 13, "github", 34, "Test Book")
    private val prefs = UserPreferences(isSetupCompleted = true, selectedCityName = "Springfield", latitude = 41.2345, longitude = -87.6543)
    private val display = DisplayProfile(0, "Built-in", 2560, 1600, densityDpi = 220)
    private val state = SunloomUiState(
        preferences = prefs,
        capabilities = PlatformCapabilities(
            isWallpaperSupported = true, isSetWallpaperAllowed = true, supportsIndependentLockWallpaper = false,
            supportsPerDisplayWallpaper = false, connectedDisplays = listOf(display), capabilityDisclosureNote = ""
        ),
        sourceDiagnostics = listOf(
            SourceDiagnostics("artic_cc0", "Art Institute", isAdmitted = true, isEnabled = true,
                healthState = SourceHealthState.HEALTHY, lastSyncSummary = "fetched 8, added 3"),
            SourceDiagnostics("nasa_library", "NASA", isAdmitted = true, isEnabled = true,
                healthState = SourceHealthState.AUTO_DISABLED, consecutiveFailures = 4, lastError = "HTTP 503")
        )
    )

    private fun report(crashes: List<CrashEntry> = emptyList()) = DiagnosticsReport.build(info, state, crashes)

    @Test
    fun `it names the build and the device`() {
        val text = report()
        assertTrue(text.contains("1.7.0 (13)"))
        assertTrue(text.contains("github"))
        assertTrue(text.contains("API 34"))
        assertTrue(text.contains("Test Book"))
    }

    @Test
    fun `it lists displays and how each source is doing`() {
        val text = report()
        assertTrue(text.contains("2560x1600"))
        assertTrue(text.contains("artic_cc0"))
        assertTrue(text.contains("fetched 8, added 3"))
        assertTrue(text.contains("nasa_library"))
        assertTrue(text.contains("HTTP 503"))
        assertTrue(text.contains("AUTO_DISABLED"))
    }

    @Test
    fun `it never reveals where the person is`() {
        val text = report()
        assertFalse("city", text.contains("Springfield"))
        assertFalse("latitude", text.contains("41.2345") || text.contains("41.23"))
        assertFalse("longitude", text.contains("87.65"))
    }

    @Test
    fun `it includes the start of recent crashes`() {
        val crash = CrashEntry("crash-1.txt", 1L, (1..40).joinToString("\n") { "line $it" })

        val text = report(listOf(crash))

        assertTrue(text.contains("line 1"))
        assertTrue("a crash is shortened to its first lines", text.contains("line 12"))
        assertFalse(text.contains("line 30"))
    }

    @Test
    fun `it says so when there are no crashes`() {
        assertTrue(report().contains("none"))
    }

    @Test
    fun `the problem link opens a new issue on the project with the build in it`() {
        val url = ReportProblem.issueUrl(info)

        assertTrue(url.startsWith("https://github.com/stevin-wilson/Sunloom/issues/new?"))
        assertTrue("short enough for a browser", url.length < 2000)
        val body = URLDecoder.decode(url.substringAfter("body="), "UTF-8")
        assertTrue(body.contains("1.7.0 (13)"))
        assertTrue(body.contains("github"))
        assertTrue("it asks the person to paste the diagnostics", body.contains("Copy diagnostics"))
    }

    @Test
    fun `the flavor is part of the issue so a Play build is told apart`() {
        assertEquals(true, URLDecoder.decode(ReportProblem.issueUrl(info.copy(flavor = "play")), "UTF-8").contains("play"))
    }
}
