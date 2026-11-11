package com.sunloom.app.ui

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.update.UpdateCheckFeature
import com.sunloom.app.update.UpdateCheckResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Article XVIII §1: a person can ask the GitHub build to look for updates when they open it, at most once a week. Off unless
 * they turn it on; never in the background (that would need a notification permission, which Sunloom never asks for).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Requirement("XVIII.1")
class WeeklyUpdateCheckTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    private class Gateway(var state: SunloomUiState) : SunloomGateway {
        override suspend fun loadInitialSnapshot() = state
        override suspend fun savePreferences(preferences: UserPreferences): SunloomUiState {
            state = state.copy(preferences = preferences)
            return state
        }
        override suspend fun applyWallpaper(recordId: String) = state
        override suspend fun applyNextWallpaper(excludeIds: Set<String>) = state
        override suspend fun applyPreviousWallpaper() = state
        override suspend fun restoreBaselineWallpaper() = state
        override suspend fun toggleKeepWallpaper(recordId: String) = state
        override suspend fun rejectWallpaperForever(recordId: String) = state
        override suspend fun refreshFromInternet() = state
        override suspend fun importLocalUri(uriString: String, asBaselineRestore: Boolean, applyAfterImport: Boolean) = state
    }

    private fun open(
        weekly: Boolean = true,
        last: Long = 0L,
        setup: Boolean = true,
        result: UpdateCheckResult = UpdateCheckResult.UpToDate("2.0.0"),
        feature: Boolean = true
    ): Triple<MainViewModel, Gateway, IntArray> {
        val calls = intArrayOf(0)
        val gateway = Gateway(
            SunloomUiState(preferences = UserPreferences(isSetupCompleted = setup, weeklyUpdateCheck = weekly, lastUpdateCheckEpochMs = last))
        )
        val vm = MainViewModel(
            gateway = gateway,
            workDispatcher = UnconfinedTestDispatcher(),
            clockMs = { now },
            updateCheck = if (feature) UpdateCheckFeature { calls[0]++; result } else null,
            appInfo = { com.sunloom.app.diagnostics.AppInfo("2.0.0", 200009, "github", 34, "Test") }
        )
        return Triple(vm, gateway, calls)
    }

    @Test
    fun `it is off unless the person turned it on`() {
        val (_, _, calls) = open(weekly = false)
        assertEquals(0, calls[0])
    }

    @Test
    fun `opening the app after a week without a check asks, and remembers when`() {
        val (_, gateway, calls) = open(last = now - 8 * day)

        assertEquals(1, calls[0])
        assertEquals(now, gateway.state.preferences.lastUpdateCheckEpochMs)
    }

    @Test
    fun `the very first time it asks at once`() {
        val (_, _, calls) = open(last = 0L)
        assertEquals(1, calls[0])
    }

    @Test
    fun `within the week it does not ask again`() {
        val (_, _, calls) = open(last = now - 6 * day)
        assertEquals(0, calls[0])
    }

    @Test
    fun `it waits for setup to be finished`() {
        val (_, _, calls) = open(setup = false)
        assertEquals(0, calls[0])
    }

    @Test
    fun `a build with no update check never asks`() {
        val (vm, _, calls) = open(feature = false)
        assertEquals(0, calls[0])
        assertEquals(UpdateCheckUiState.Idle, vm.uiState.value.updateCheck)
    }

    @Test
    fun `an available update is announced, once, with where to find it`() {
        val (vm, _, _) = open(result = UpdateCheckResult.Available("2.1.0", "https://github.com/stevin-wilson/Sunloom/releases/tag/v2.1.0"))

        assertEquals(UiStrings.English.updateAvailableNotice("2.1.0"), vm.uiState.value.statusBannerMessage)
        assertTrue(vm.uiState.value.updateCheck is UpdateCheckUiState.Done)
    }

    @Test
    fun `being up to date says nothing`() {
        val (vm, _, _) = open(result = UpdateCheckResult.UpToDate("2.0.0"))
        assertFalse(vm.uiState.value.statusBannerMessage.contains("available"))
    }

    @Test
    fun `a check that could not be made is tried again at the next opening, not in a week`() {
        val (_, gateway, calls) = open(last = now - 8 * day, result = UpdateCheckResult.Failed)

        assertEquals(1, calls[0])
        assertEquals("the time is not recorded for a check that did not happen", now - 8 * day, gateway.state.preferences.lastUpdateCheckEpochMs)
    }
}
