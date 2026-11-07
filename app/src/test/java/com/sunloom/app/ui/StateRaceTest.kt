package com.sunloom.app.ui

import com.sunloom.app.domain.model.UserPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ViewModel changes state from the main thread (tab, banner, optimistic toggles) while background
 * mutations rebuild it. A mutation that finishes late must not undo what the user did meanwhile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StateRaceTest {

    /** A gateway whose preference saves finish only when the test says so. */
    private class SlowGateway : SunloomGateway {
        var saved = UserPreferences(isSetupCompleted = true)
        val pendingSaves = ArrayDeque<CompletableDeferred<Unit>>()

        override suspend fun loadInitialSnapshot() = SunloomUiState(preferences = saved)

        override suspend fun savePreferences(preferences: UserPreferences): SunloomUiState {
            val gate = CompletableDeferred<Unit>()
            pendingSaves.addLast(gate)
            gate.await()
            saved = preferences
            return SunloomUiState(preferences = preferences, statusBannerMessage = "Saved")
        }

        override suspend fun applyWallpaper(recordId: String) = SunloomUiState(preferences = saved)
        override suspend fun applyNextWallpaper(excludeIds: Set<String>) = SunloomUiState(preferences = saved)
        override suspend fun applyPreviousWallpaper() = SunloomUiState(preferences = saved)
        override suspend fun restoreBaselineWallpaper() = SunloomUiState(preferences = saved)
        override suspend fun toggleKeepWallpaper(recordId: String) = SunloomUiState(preferences = saved)
        override suspend fun rejectWallpaperForever(recordId: String) = SunloomUiState(preferences = saved)
        override suspend fun refreshFromInternet() = SunloomUiState(preferences = saved)
        override suspend fun importLocalUri(uriString: String, asBaselineRestore: Boolean, applyAfterImport: Boolean) =
            SunloomUiState(preferences = saved)
    }

    @Test
    fun `a tab picked while a save is in flight is kept when the save finishes`() = runTest {
        val gateway = SlowGateway()
        val vm = MainViewModel(gateway, StandardTestDispatcher(testScheduler), roundPauseMs = 0L)
        advanceUntilIdle()

        vm.updatePreferences { it.copy(wifiOnlyDownloads = true) }
        advanceUntilIdle()
        vm.selectTab(DesktopNavTab.SAFETY_AND_DECISION_LOG)
        gateway.pendingSaves.removeFirst().complete(Unit)
        advanceUntilIdle()

        assertEquals(DesktopNavTab.SAFETY_AND_DECISION_LOG, vm.uiState.value.activeTab)
    }

    @Test
    fun `a second toggle is not flipped back when the first save finishes`() = runTest {
        val gateway = SlowGateway()
        val vm = MainViewModel(gateway, StandardTestDispatcher(testScheduler), roundPauseMs = 0L)
        advanceUntilIdle()

        vm.updatePreferences { it.copy(wifiOnlyDownloads = true) }
        advanceUntilIdle() // the first save is now waiting on the gateway
        vm.updatePreferences { it.copy(deferOnBatterySaver = !it.deferOnBatterySaver) }
        val secondValue = vm.uiState.value.preferences.deferOnBatterySaver

        gateway.pendingSaves.removeFirst().complete(Unit) // the FIRST save lands while the second is queued
        advanceUntilIdle()

        val shown = vm.uiState.value.preferences
        assertTrue("first toggle kept", shown.wifiOnlyDownloads)
        assertEquals("second toggle must not flip back while its save is pending", secondValue, shown.deferOnBatterySaver)
    }

    @Test
    fun `after every save finishes the persisted preferences are shown`() = runTest {
        val gateway = SlowGateway()
        val vm = MainViewModel(gateway, StandardTestDispatcher(testScheduler), roundPauseMs = 0L)
        advanceUntilIdle()

        vm.updatePreferences { it.copy(wifiOnlyDownloads = true) }
        advanceUntilIdle()
        vm.updatePreferences { it.copy(deferOnBatterySaver = !it.deferOnBatterySaver) }
        while (true) {
            val next = gateway.pendingSaves.removeFirstOrNull() ?: break
            next.complete(Unit)
            advanceUntilIdle()
        }

        assertEquals(gateway.saved, vm.uiState.value.preferences)
        assertFalse(vm.uiState.value.isApplyingWallpaper)
    }
}
