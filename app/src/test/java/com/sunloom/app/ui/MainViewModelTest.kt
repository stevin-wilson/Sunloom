package com.sunloom.app.ui

import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.CropRect
import com.sunloom.app.domain.model.ImageEvaluationResult
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.solar.SolarCalculator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MainViewModelTest {

    private lateinit var viewModel: MainViewModel
    private lateinit var fakeGateway: FakeSunloomGateway

    private class FakeSunloomGateway : SunloomGateway {
        private val wall1 = sampleWall("seed-1", "Alpine Mist", BrightnessClass.LIGHT)
        private val wall2 = sampleWall("seed-2", "Orion Nebula", BrightnessClass.DARK)

        var state = SunloomUiState(
            preferences = UserPreferences(isSetupCompleted = false),
            currentWallpaper = wall1,
            previousWallpaperId = "seed-2",
            libraryWallpapers = listOf(wall1, wall2)
        )

        override suspend fun loadInitialSnapshot(): SunloomUiState {
            loadCount++
            return state
        }
        var loadCount = 0

        override suspend fun savePreferences(preferences: UserPreferences): SunloomUiState {
            state = state.copy(preferences = preferences)
            return state
        }

        override suspend fun applyWallpaper(recordId: String): SunloomUiState {
            val found = state.libraryWallpapers.firstOrNull { it.id == recordId } ?: state.currentWallpaper
            state = state.copy(
                previousWallpaperId = state.currentWallpaper?.id,
                currentWallpaper = found
            )
            return state
        }

        var lastExcludeIds: Set<String>? = null
        val importApplyFlags = mutableListOf<Boolean>()

        var failNext: Throwable? = null

        override suspend fun applyNextWallpaper(excludeIds: Set<String>): SunloomUiState {
            failNext?.let { throw it }
            lastExcludeIds = excludeIds
            return applyWallpaper("seed-2")
        }
        override suspend fun applyPreviousWallpaper(): SunloomUiState = applyWallpaper(state.previousWallpaperId ?: "seed-1")
        override suspend fun restoreBaselineWallpaper(): SunloomUiState = applyWallpaper("seed-1")

        var resetCount = 0
        override suspend fun resetSunloom(): SunloomUiState {
            resetCount++
            state = SunloomUiState(preferences = UserPreferences(isSetupCompleted = false))
            return state
        }

        override suspend fun toggleKeepWallpaper(recordId: String): SunloomUiState {
            val cur = state.currentWallpaper ?: return state
            val updated = cur.copy(isFavorite = !cur.isFavorite)
            state = state.copy(currentWallpaper = updated)
            return state
        }

        override suspend fun rejectWallpaperForever(recordId: String): SunloomUiState {
            val remaining = state.libraryWallpapers.filterNot { it.id == recordId }
            state = state.copy(
                libraryWallpapers = remaining,
                currentWallpaper = remaining.firstOrNull()
            )
            return state
        }

        var shouldThrowOnSync: Boolean = false
        var shouldCancelOnSync: Boolean = false

        override suspend fun refreshFromInternet(): SunloomUiState {
            if (shouldCancelOnSync) {
                throw kotlinx.coroutines.CancellationException("Job was cancelled")
            }
            if (shouldThrowOnSync) {
                throw IllegalStateException("Simulated network timeout")
            }
            state = state.copy(statusBannerMessage = "Online sync complete")
            return state
        }

        override suspend fun importLocalUri(uriString: String, asBaselineRestore: Boolean, applyAfterImport: Boolean): SunloomUiState {
            importApplyFlags += applyAfterImport
            state = state.copy(statusBannerMessage = "Imported $uriString")
            return state
        }

        companion object {
            const val SAMPLE_FILE_BYTES = 1024L

            fun sampleWall(id: String, title: String, brightness: BrightnessClass): WallpaperRecord {
                return WallpaperRecord(
                    id = id,
                    title = title,
                    category = SafeCategory.LANDSCAPES_NATURE,
                    localFilePath = "/tmp/$id.png",
                    croppedFilePath = "",
                    fileSizeBytes = SAMPLE_FILE_BYTES,
                    provenance = ProvenanceRecord(
                        sourceId = "seed_reserve",
                        sourceName = "Sunloom Seed Reserve",
                        assetId = id,
                        originalPageUrl = "https://commons.wikimedia.org",
                        creatorName = "Sunloom Studio",
                        creatorPageUrl = "https://commons.wikimedia.org",
                        licenseType = LicenseType.CC0,
                        attributionText = "CC0",
                        retrievedAtEpochMs = 1762280000000L,
                        originalWidth = 3840,
                        originalHeight = 2400,
                        renditionWidth = 2560,
                        renditionHeight = 1600,
                        safetyClassification = SafetyClassification.SAFE,
                        safetyClassificationSource = "Seed",
                        sha256Checksum = "123456"
                    ),
                    evaluation = ImageEvaluationResult(
                        isEligible = true,
                        rejectionReasonCode = null,
                        globalLuminance = 0.5,
                        desktopZoneLuminance = 0.5,
                        lockClockZoneLuminance = 0.5,
                        glareFractionInSafeZones = 0.0,
                        contrastStdDev = 0.1,
                        brightnessClass = brightness,
                        cropRect = CropRect(0, 0, 2560, 1600, 0.5, 0.5, false, 1.0),
                        explanation = "OK"
                    )
                )
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        fakeGateway = FakeSunloomGateway()
        viewModel = MainViewModel(
            gateway = fakeGateway,
            workDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher()
        )
    }

    @Test
    fun `a storage error during an action becomes a status message instead of crashing`() {
        // viewModelScope has no exception handler, so any uncaught IOException / SQLiteException ends the process.
        fakeGateway.failNext = java.io.IOException("No space left on device")
        viewModel.changeToNextWallpaper()
        assertEquals(UiStrings.English.storageProblem, viewModel.uiState.value.statusBannerMessage)
        assertFalse(viewModel.uiState.value.isApplyingWallpaper)
    }

    @Test
    fun `resetting returns to first-run setup with a clean screen`() {
        viewModel.completeFirstRunSetup(UserPreferences(autoChangeEnabled = true))
        viewModel.selectTab(DesktopNavTab.DISPLAYS_AND_SCHEDULE)
        viewModel.reopenSetup()
        assertTrue(viewModel.uiState.value.preferences.isSetupCompleted)

        viewModel.resetSunloom()

        assertEquals(1, fakeGateway.resetCount)
        val state = viewModel.uiState.value
        assertFalse("setup is shown again", state.preferences.isSetupCompleted)
        assertFalse(state.isSetupReopened)
        assertEquals(DesktopNavTab.TODAY_CANVAS, state.activeTab)
        assertNull(state.pendingDiscard)
        assertFalse(state.isApplyingWallpaper)
    }

    @Test
    fun `automatic switching can be turned back on after setup turned it off`() {
        viewModel.completeFirstRunSetup(UserPreferences(scheduleMode = ScheduleMode.SUNRISE_SUNSET, autoChangeEnabled = false))
        assertFalse(viewModel.uiState.value.preferences.autoChangeEnabled)

        viewModel.setAutoChangeEnabled(true)

        assertTrue(viewModel.uiState.value.preferences.autoChangeEnabled)
        assertTrue("the choice is saved, not only shown", fakeGateway.state.preferences.autoChangeEnabled)
    }

    @Test
    fun `reopening setup shows setup again without saving anything`() {
        viewModel.completeFirstRunSetup(
            UserPreferences(scheduleMode = ScheduleMode.FIXED_CLOCK, autoChangeEnabled = true)
        )
        assertFalse(viewModel.uiState.value.isSetupReopened)

        viewModel.reopenSetup()

        val state = viewModel.uiState.value
        assertTrue("setup is shown again", state.isSetupReopened)
        assertTrue("but it is only a screen, not a saved state", state.preferences.isSetupCompleted)
        assertTrue("so a relaunch does not boot into first-run", fakeGateway.state.preferences.isSetupCompleted)
        assertEquals(ScheduleMode.FIXED_CLOCK, state.preferences.scheduleMode)
    }

    @Test
    fun `reopened setup stays open through other updates and closes when completed`() {
        viewModel.completeFirstRunSetup(UserPreferences(autoChangeEnabled = true))
        viewModel.reopenSetup()

        viewModel.changeToNextWallpaper()
        assertTrue("another update must not close setup under the person", viewModel.uiState.value.isSetupReopened)

        viewModel.completeFirstRunSetup(UserPreferences(autoChangeEnabled = false))
        assertFalse(viewModel.uiState.value.isSetupReopened)
        assertFalse(viewModel.uiState.value.preferences.autoChangeEnabled)
    }

    @Test
    fun `completeFirstRunSetup marks setup completed and activates TODAY_CANVAS tab`() {
        assertFalse(viewModel.uiState.value.preferences.isSetupCompleted)
        viewModel.completeFirstRunSetup(
            UserPreferences(scheduleMode = ScheduleMode.SUNRISE_SUNSET, autoChangeEnabled = true)
        )
        assertTrue(viewModel.uiState.value.preferences.isSetupCompleted)
        assertEquals(DesktopNavTab.TODAY_CANVAS, viewModel.uiState.value.activeTab)
    }

    @Test
    fun `changeToNextWallpaper, returnToPreviousWallpaper, and toggleKeepCurrentWallpaper update state`() {
        viewModel.changeToNextWallpaper()
        assertEquals("seed-2", viewModel.uiState.value.currentWallpaper?.id)

        viewModel.toggleKeepCurrentWallpaper()
        assertTrue(viewModel.uiState.value.currentWallpaper!!.isFavorite)

        viewModel.returnToPreviousWallpaper()
        assertEquals("seed-1", viewModel.uiState.value.currentWallpaper?.id)
    }

    @Test
    fun `rejectCurrentWallpaperForever removes rejected item and switches to next`() {
        viewModel.rejectCurrentWallpaperForever()
        assertEquals("seed-2", viewModel.uiState.value.currentWallpaper?.id)
        assertEquals(1, viewModel.uiState.value.libraryWallpapers.size)
    }

    @Test
    fun `selectCity and togglePauseAutomation update preferences`() {
        val tokyo = SolarCalculator.BUNDLED_CITIES.first { it.name.contains("Tokyo") }
        viewModel.selectCity(tokyo)
        assertEquals(tokyo.name, viewModel.uiState.value.preferences.selectedCityName)

        viewModel.togglePauseAutomation()
        assertTrue(viewModel.uiState.value.preferences.isPaused)
    }

    @Test
    fun `syncOnlineCatalogNow updates statusBannerMessage and resets isSyncingOnline`() = runTest {
        viewModel.syncOnlineCatalogNow()
        assertFalse(viewModel.uiState.value.isSyncingOnline)
        assertEquals("Online sync complete", viewModel.uiState.value.statusBannerMessage)
    }

    @Test
    fun `testSyncOnlineFailureResetsIsSyncingOnlineInFinally`() = runTest {
        fakeGateway.shouldThrowOnSync = true
        viewModel.syncOnlineCatalogNow()
        assertFalse(viewModel.uiState.value.isSyncingOnline)
        assertTrue(viewModel.uiState.value.statusBannerMessage.contains("Online sync interrupted"))
    }

    @Test
    fun `selectTab, toggleCategory, toggleSource, toggleLegibilityOverlay, restoreOriginal, and importLocalUri update state`() {
        viewModel.selectTab(DesktopNavTab.SAFETY_AND_DECISION_LOG)
        assertEquals(DesktopNavTab.SAFETY_AND_DECISION_LOG, viewModel.uiState.value.activeTab)

        viewModel.toggleLegibilityOverlay()
        assertTrue(viewModel.uiState.value.showLegibilityZonesOverlay)

        viewModel.toggleCategory(SafeCategory.SPACE_ASTRONOMY)
        assertFalse(viewModel.uiState.value.preferences.enabledCategories.contains(SafeCategory.SPACE_ASTRONOMY))
        viewModel.toggleCategory(SafeCategory.SPACE_ASTRONOMY)
        assertTrue(viewModel.uiState.value.preferences.enabledCategories.contains(SafeCategory.SPACE_ASTRONOMY))

        // NASA is opt-in: it starts off, switches on, and off again.
        assertFalse(viewModel.uiState.value.preferences.enabledSources.contains("nasa_library"))
        viewModel.toggleSource("nasa_library")
        assertTrue(viewModel.uiState.value.preferences.enabledSources.contains("nasa_library"))
        viewModel.toggleSource("nasa_library")
        assertFalse(viewModel.uiState.value.preferences.enabledSources.contains("nasa_library"))

        viewModel.applySpecificWallpaper("seed-2")
        assertEquals("seed-2", viewModel.uiState.value.currentWallpaper?.id)

        viewModel.restoreOriginalBaselineWallpaper()
        assertEquals("seed-1", viewModel.uiState.value.currentWallpaper?.id)

        viewModel.importLocalUri("content://media/external/images/1", asBaselineRestore = true)
        assertEquals("Imported content://media/external/images/1", viewModel.uiState.value.statusBannerMessage)

        assertTrue(viewModel.uiState.value.lightReserveCount >= 1)
        assertTrue(viewModel.uiState.value.darkReserveCount >= 1)
        assertEquals(2 * FakeSunloomGateway.SAMPLE_FILE_BYTES, viewModel.uiState.value.totalCacheBytesUsed)
    }

    @Test
    fun `syncOnlineCatalogNow rethrows CancellationException cleanly without showing error banner`() = runTest {
        fakeGateway.shouldCancelOnSync = true
        var caughtCancel = false
        try {
            viewModel.syncOnlineCatalogNow()
        } catch (_: kotlinx.coroutines.CancellationException) {
            caughtCancel = true
        }
        assertTrue("CancellationException must be rethrown to preserve structured concurrency", caughtCancel)
        assertFalse(viewModel.uiState.value.isSyncingOnline)
        assertFalse(viewModel.uiState.value.statusBannerMessage.contains("Job was cancelled"))
    }

    @Test
    fun `dislike with undo switches away now but only commits after the undo window`() {
        viewModel.dislikeCurrentWithUndo()
        val pending = viewModel.uiState.value.pendingDiscard
        assertEquals("seed-1", pending?.recordId)
        assertEquals(DiscardKind.DISLIKE, pending?.kind)
        assertEquals("seed-2", viewModel.uiState.value.currentWallpaper?.id)
        // Nothing is permanently excluded yet.
        assertEquals(2, viewModel.uiState.value.libraryWallpapers.size)

        viewModel.commitPendingDiscard()
        assertEquals(null, viewModel.uiState.value.pendingDiscard)
        assertEquals(1, viewModel.uiState.value.libraryWallpapers.size)
    }

    @Test
    fun `undo after dislike restores the wallpaper and leaves the reserve intact`() {
        viewModel.dislikeCurrentWithUndo()
        viewModel.undoPendingDiscard()
        assertEquals(null, viewModel.uiState.value.pendingDiscard)
        assertEquals("seed-1", viewModel.uiState.value.currentWallpaper?.id)
        assertEquals(2, viewModel.uiState.value.libraryWallpapers.size)
    }

    @Test
    fun `starting a second discard finalizes the first`() {
        viewModel.removeFromReserveWithUndo("seed-2")
        assertEquals("seed-2", viewModel.uiState.value.pendingDiscard?.recordId)
        assertEquals(2, viewModel.uiState.value.libraryWallpapers.size)

        viewModel.dislikeCurrentWithUndo()
        // seed-2 removal was committed; seed-1 dislike is now the pending one.
        assertEquals("seed-1", viewModel.uiState.value.pendingDiscard?.recordId)
        assertFalse(viewModel.uiState.value.libraryWallpapers.any { it.id == "seed-2" })
    }

    @Test
    fun `removing several reserve items shares one undo and finalizes them together`() {
        viewModel.removeManyFromReserveWithUndo(listOf("seed-1", "seed-2"))
        val pending = viewModel.uiState.value.pendingDiscard
        assertEquals(setOf("seed-1", "seed-2"), pending?.allRecordIds?.toSet())
        assertEquals("2 wallpapers", pending?.title)
        // Nothing is deleted until the undo window ends.
        assertEquals(2, viewModel.uiState.value.libraryWallpapers.size)

        viewModel.commitPendingDiscard()
        assertEquals(0, viewModel.uiState.value.libraryWallpapers.size)
    }

    @Test
    fun `removing the active item never picks another item that is also being removed`() {
        viewModel.removeManyFromReserveWithUndo(listOf("seed-1", "seed-2"))
        assertEquals(setOf("seed-1", "seed-2"), fakeGateway.lastExcludeIds)
    }

    @Test
    fun `a batch import only fills the reserve while a single import applies`() {
        viewModel.importLocalUris(listOf("content://a/1"))
        viewModel.importLocalUris(listOf("content://a/2", "content://a/3"))
        assertEquals(listOf(true, false, false), fakeGateway.importApplyFlags)
    }

    @Test
    fun `undoing a batch removal restores the wallpaper that was active`() {
        viewModel.removeManyFromReserveWithUndo(listOf("seed-2", "seed-1"))
        // seed-1 was active, so the app moved on; undo must bring seed-1 back even though it is not the first id.
        viewModel.undoPendingDiscard()
        assertEquals(null, viewModel.uiState.value.pendingDiscard)
        assertEquals("seed-1", viewModel.uiState.value.currentWallpaper?.id)
        assertEquals(2, viewModel.uiState.value.libraryWallpapers.size)
    }

    @Test
    fun `removing a single id through the batch api behaves like a normal removal`() {
        viewModel.removeManyFromReserveWithUndo(listOf("seed-2"))
        assertEquals("seed-2", viewModel.uiState.value.pendingDiscard?.recordId)
        assertEquals(emptyList<String>(), viewModel.uiState.value.pendingDiscard?.additionalRecordIds)
    }

    @Test
    fun `deleteFromReserve removes specific item from reserve`() {
        assertEquals(2, viewModel.uiState.value.libraryWallpapers.size)
        viewModel.deleteFromReserve("seed-2")
        assertEquals(1, viewModel.uiState.value.libraryWallpapers.size)
        assertEquals("seed-1", viewModel.uiState.value.libraryWallpapers.first().id)
    }

    @Test
    fun `onDisplaysChanged refreshes connectedDisplays while preserving active navigation tab`() {
        viewModel.selectTab(DesktopNavTab.DISPLAYS_AND_SCHEDULE)
        val uwqhdCaps = viewModel.uiState.value.capabilities.copy(
            connectedDisplays = listOf(
                com.sunloom.app.domain.model.DisplayProfile(0, "GoogleBook Built-in Display", 2560, 1600, 240),
                com.sunloom.app.domain.model.DisplayProfile(1, "External UWQHD Monitor", 3440, 1440, 160)
            ),
            capabilityDisclosureNote = "Detected 2 displays (External UWQHD Monitor 3440 × 1440)."
        )
        fakeGateway.state = fakeGateway.state.copy(
            capabilities = uwqhdCaps,
            statusBannerMessage = "Adapted wallpaper for External UWQHD Monitor (3440 × 1440)"
        )

        viewModel.onDisplaysChanged()

        assertEquals(DesktopNavTab.DISPLAYS_AND_SCHEDULE, viewModel.uiState.value.activeTab)
        assertEquals(2, viewModel.uiState.value.capabilities.connectedDisplays.size)
        assertTrue(viewModel.uiState.value.statusBannerMessage.contains("3440 × 1440"))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `MainViewModel starts in Loading state without blocking constructor on loadInitialSnapshot`() = runTest {
        val gateway = FakeSunloomGateway()
        val deferredDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val asyncVm = MainViewModel(gateway = gateway, workDispatcher = deferredDispatcher)

        assertTrue("MainViewModel must start in isInitialLoading=true before background coroutine runs", asyncVm.uiState.value.isInitialLoading)
        assertEquals("Constructor must NOT call loadInitialSnapshot synchronously on main thread", 0, gateway.loadCount)

        testScheduler.advanceUntilIdle()
        assertFalse(asyncVm.uiState.value.isInitialLoading)
        assertEquals(1, gateway.loadCount)
        assertEquals("seed-1", asyncVm.uiState.value.currentWallpaper?.id)
    }
}
