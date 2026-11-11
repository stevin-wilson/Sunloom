package com.sunloom.app.ui

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.update.UpdateCheckFeature
import com.sunloom.app.update.UpdateCheckResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Article XVIII §1: the update check is a person's request; it shows a state while it runs and what it found. */
@OptIn(ExperimentalCoroutinesApi::class)
@Requirement("XVIII.1")
class UpdateCheckViewModelTest {
    private class QuietGateway : SunloomGateway {
        val state = SunloomUiState(preferences = UserPreferences(isSetupCompleted = true))
        override suspend fun loadInitialSnapshot() = state
        override suspend fun savePreferences(preferences: UserPreferences) = state
        override suspend fun applyWallpaper(recordId: String) = state
        override suspend fun applyNextWallpaper(excludeIds: Set<String>) = state
        override suspend fun applyPreviousWallpaper() = state
        override suspend fun restoreBaselineWallpaper() = state
        override suspend fun toggleKeepWallpaper(recordId: String) = state
        override suspend fun rejectWallpaperForever(recordId: String) = state
        override suspend fun refreshFromInternet() = state
        override suspend fun importLocalUri(uriString: String, asBaselineRestore: Boolean, applyAfterImport: Boolean) = state
    }

    private fun vm(feature: UpdateCheckFeature?) = MainViewModel(
        gateway = QuietGateway(),
        workDispatcher = UnconfinedTestDispatcher(),
        updateCheck = feature,
        appInfo = { com.sunloom.app.diagnostics.AppInfo("2.0.0", 200009, "github", 34, "Test") }
    )

    @Test
    fun `nothing is checked until the person asks`() {
        var asked = 0

        val model = vm { asked++; UpdateCheckResult.Failed }

        assertEquals(0, asked)
        assertEquals(UpdateCheckUiState.Idle, model.uiState.value.updateCheck)
    }

    @Test
    fun `asking checks the installed version and shows what was found`() {
        var versionAsked: String? = null
        val model = vm { current -> versionAsked = current; UpdateCheckResult.Available("2.1.0", "https://github.com/x/y/releases/tag/v2.1.0") }

        model.checkForUpdates()

        assertEquals("2.0.0", versionAsked)
        assertEquals(
            UpdateCheckUiState.Done(UpdateCheckResult.Available("2.1.0", "https://github.com/x/y/releases/tag/v2.1.0")),
            model.uiState.value.updateCheck
        )
    }

    @Test
    fun `while it runs the state says checking, and a second tap does not start another`() {
        val release = CompletableDeferred<UpdateCheckResult>()
        var asked = 0
        val model = vm { asked++; release.await() }

        model.checkForUpdates()
        model.checkForUpdates()

        assertEquals(UpdateCheckUiState.Checking, model.uiState.value.updateCheck)
        assertEquals(1, asked)

        release.complete(UpdateCheckResult.UpToDate("2.0.0"))
        assertEquals(UpdateCheckUiState.Done(UpdateCheckResult.UpToDate("2.0.0")), model.uiState.value.updateCheck)
    }

    @Test
    fun `a build without the feature does nothing`() {
        val model = vm(null)

        model.checkForUpdates()

        assertEquals(UpdateCheckUiState.Idle, model.uiState.value.updateCheck)
    }

    @Test
    fun `an unexpected failure becomes a failed check, not a crash`() {
        val model = vm { throw IllegalStateException("boom") }

        model.checkForUpdates()

        assertTrue(model.uiState.value.updateCheck == UpdateCheckUiState.Done(UpdateCheckResult.Failed))
    }
}
