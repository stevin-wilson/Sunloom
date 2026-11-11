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

    // --- nothing that names a file or a place on the device ---

    @Test
    fun `scrubbing removes file paths and content or file URIs`() {
        val text = "java.io.FileNotFoundException: /data/user/0/com.sunloom.app/files/wallpapers/local-1a2b.png (No such file) " +
            "while reading content://com.android.providers.media/external/images/media/42 and file:///sdcard/Pictures/holiday.jpg"

        val clean = DiagnosticsReport.scrub(text)

        assertFalse(clean, clean.contains("/data/user") || clean.contains("local-1a2b") || clean.contains("holiday"))
        assertFalse(clean, clean.contains("content://") || clean.contains("providers.media") || clean.contains("file:///"))
        assertTrue("the kind of error is kept", clean.contains("FileNotFoundException") && clean.contains("No such file"))
    }

    @Test
    fun `scrubbing keeps a web address's host but not its path or query`() {
        val clean = DiagnosticsReport.scrub("HTTP 503 from https://api.artic.edu/api/v1/artworks/search?q=cat&token=abc")

        assertTrue(clean, clean.contains("https://api.artic.edu"))
        assertFalse(clean, clean.contains("artworks") || clean.contains("token"))
    }

    @Test
    fun `scrubbing leaves stack frames and ordinary text alone`() {
        val frames = "at java.base/java.lang.Thread.run(Thread.java:1012)\nat com.sunloom.app.ui.MainActivity.onCreate(MainActivity.kt:94)"

        assertEquals(frames, DiagnosticsReport.scrub(frames))
        assertEquals("Connection error", DiagnosticsReport.scrub("Connection error"))
    }

    @Test
    fun `the report is scrubbed wherever it carries text from the device`() {
        val dirty = state.copy(
            statusBannerMessage = "Couldn't read /storage/emulated/0/DCIM/private.jpg",
            sourceDiagnostics = listOf(
                SourceDiagnostics("artic_cc0", "Art Institute", isAdmitted = true, isEnabled = true,
                    lastError = "failed on content://media/external/42", lastSyncSummary = "fetched 8")
            )
        )
        val crash = CrashEntry("c.txt", 1L, "java.io.IOException: /data/user/0/com.sunloom.app/files/wallpapers/local-9.png\nat x.Y.z(Y.kt:1)")

        val text = DiagnosticsReport.build(info, dirty, listOf(crash))

        assertFalse(text, text.contains("private.jpg") || text.contains("local-9") || text.contains("content://media") || text.contains("/data/user"))
        assertTrue("and still says what failed", text.contains("IOException") && text.contains("artic_cc0"))
    }
}
