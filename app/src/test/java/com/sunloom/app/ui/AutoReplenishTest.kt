package com.sunloom.app.ui

import com.sunloom.app.Requirement
import com.sunloom.app.domain.matcher.ReserveBuffer
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.CropRect
import com.sunloom.app.domain.model.ImageEvaluationResult
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.model.WallpaperRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test

private fun unseen(id: String, brightness: BrightnessClass) = WallpaperRecord(
        id = id,
        title = id,
        category = SafeCategory.LANDSCAPES_NATURE,
        localFilePath = "/tmp/$id.jpg",
        croppedFilePath = "",
        fileSizeBytes = 4096L,
        provenance = ProvenanceRecord(
            sourceId = "wikimedia_featured", sourceName = "w", assetId = id,
            originalPageUrl = "https://example.org", creatorName = "c", creatorPageUrl = "",
            licenseType = LicenseType.CC0, attributionText = "CC0", retrievedAtEpochMs = 1L,
            originalWidth = 3840, originalHeight = 2400, renditionWidth = 2560, renditionHeight = 1600,
            safetyClassification = SafetyClassification.SAFE, safetyClassificationSource = "t", sha256Checksum = "x"
        ),
        evaluation = ImageEvaluationResult(
            isEligible = true, rejectionReasonCode = null, globalLuminance = 0.5,
            desktopZoneLuminance = 0.5, lockClockZoneLuminance = 0.5, glareFractionInSafeZones = 0.0,
            contrastStdDev = 0.1, brightnessClass = brightness,
            cropRect = CropRect(0, 0, 2560, 1600, 0.5, 0.5, false, 1.0), explanation = "t"
        )
    )

/**
 * Article X §3 (v0.14): the reserve keeps a buffer of unseen images for each mode and tops it up in rounds, at
 * launch, after setup and after Next, instead of waiting until it is nearly empty or for the background worker.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Requirement("X.3")
class AutoReplenishTest {

    private class CountingGateway(var state: SunloomUiState, private val perRefresh: (Int) -> Int = { 0 }) : SunloomGateway {
        var refreshCalls = 0
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
        override suspend fun refreshFromInternet(): SunloomUiState {
            refreshCalls++
            val added = perRefresh(refreshCalls)
            if (added > 0) {
                val fresh = (1..added).flatMap { listOf(unseen("n${refreshCalls}l$it", BrightnessClass.LIGHT), unseen("n${refreshCalls}d$it", BrightnessClass.DARK)) }
                state = state.copy(libraryWallpapers = state.libraryWallpapers + fresh)
            }
            return state
        }
        override suspend fun importLocalUri(uriString: String, asBaselineRestore: Boolean, applyAfterImport: Boolean) = state
    }

    /** [unseenCount] unseen Light and the same number of unseen Dark images. */
    private fun stateWith(
        unseenCount: Int,
        setupCompleted: Boolean = true,
        wifiOnly: Boolean = false,
        maxCacheBytes: Long = 1024L * 1024L * 1024L
    ) = SunloomUiState(
        preferences = UserPreferences(isSetupCompleted = setupCompleted, wifiOnlyDownloads = wifiOnly, maxCacheBytes = maxCacheBytes),
        targetBrightnessNow = BrightnessClass.LIGHT,
        libraryWallpapers = (1..unseenCount).flatMap { listOf(unseen("l$it", BrightnessClass.LIGHT), unseen("d$it", BrightnessClass.DARK)) }
    )

    private var now = 1_000_000L

    private fun launch(gateway: CountingGateway) = MainViewModel(
        gateway = gateway,
        workDispatcher = UnconfinedTestDispatcher(),
        clockMs = { now },
        roundPauseMs = 0L
    )

    private val target = ReserveBuffer.TARGET_UNSEEN_PER_MODE

    @Test
    fun `launch fetches while either mode is below the target and stops once both reach it`() {
        val low = CountingGateway(stateWith(unseenCount = target - 1))
        launch(low)
        assertEquals(1, low.refreshCalls)

        val healthy = CountingGateway(stateWith(unseenCount = target))
        launch(healthy)
        assertEquals(0, healthy.refreshCalls)
    }

    @Test
    fun `a fetch keeps going in rounds while images arrive and the target is not met`() {
        val gateway = CountingGateway(stateWith(unseenCount = 0)) { 5 } // 5 Light + 5 Dark per round
        launch(gateway)
        assertEquals("0 -> 5 -> 10 -> 15 reaches 12 after three rounds", 3, gateway.refreshCalls)
    }

    @Test
    fun `rounds stop as soon as one adds nothing`() {
        val gateway = CountingGateway(stateWith(unseenCount = 0)) { round -> if (round == 1) 4 else 0 }
        launch(gateway)
        assertEquals(2, gateway.refreshCalls)
    }

    @Test
    fun `rounds are capped so a slow trickle cannot loop for long`() {
        val gateway = CountingGateway(stateWith(unseenCount = 0)) { 1 }
        launch(gateway)
        assertEquals(MainViewModel.MAX_TOP_UP_ROUNDS, gateway.refreshCalls)
    }

    @Test
    fun `launch leaves fetching to the scheduler on Wi-Fi-only setups`() {
        val gateway = CountingGateway(stateWith(unseenCount = 0, wifiOnly = true))
        launch(gateway)
        assertEquals(0, gateway.refreshCalls)
    }

    @Test
    fun `launch before setup completes does not touch the network`() {
        val gateway = CountingGateway(stateWith(unseenCount = 0, setupCompleted = false))
        launch(gateway)
        assertEquals(0, gateway.refreshCalls)
    }

    @Test
    fun `no automatic fetch when the cache is nearly full`() {
        // 2 images of 4096 bytes against a 8 KB cap.
        val gateway = CountingGateway(stateWith(unseenCount = 1, maxCacheBytes = 8_192L))
        launch(gateway)
        assertEquals(0, gateway.refreshCalls)
    }

    @Test
    fun `Next fetches when the buffer is low and not when it is full`() = kotlinx.coroutines.test.runTest {
        val low = CountingGateway(stateWith(unseenCount = 2, setupCompleted = false))
        launch(low).advanceToNextWallpaperSmart()
        assertEquals(1, low.refreshCalls)

        val healthy = CountingGateway(stateWith(unseenCount = target, setupCompleted = false))
        launch(healthy).advanceToNextWallpaperSmart()
        assertEquals(0, healthy.refreshCalls)
    }

    @Test
    fun `finishing setup fetches a first batch unless downloads are Wi-Fi only`() {
        val open = CountingGateway(stateWith(unseenCount = 0, setupCompleted = false))
        launch(open).completeFirstRunSetup(UserPreferences())
        assertEquals(1, open.refreshCalls)

        val wifi = CountingGateway(stateWith(unseenCount = 0, setupCompleted = false))
        launch(wifi).completeFirstRunSetup(UserPreferences(wifiOnlyDownloads = true))
        assertEquals(0, wifi.refreshCalls)
    }

    @Test
    fun `after a fetch that added images the next automatic fetch waits ten minutes`() = kotlinx.coroutines.test.runTest {
        val gateway = CountingGateway(stateWith(unseenCount = 0)) { round -> if (round == 1) 1 else 0 }
        val vm = launch(gateway)
        val afterLaunch = gateway.refreshCalls // round 1 added one image, round 2 added none
        vm.advanceToNextWallpaperSmart()
        assertEquals("inside the interval", afterLaunch, gateway.refreshCalls)

        now += 11 * 60 * 1000L
        vm.advanceToNextWallpaperSmart()
        assertEquals(afterLaunch + 1, gateway.refreshCalls)
    }

    @Test
    fun `after a fetch that found nothing the next automatic fetch waits an hour`() = kotlinx.coroutines.test.runTest {
        val gateway = CountingGateway(stateWith(unseenCount = 0))
        val vm = launch(gateway)
        assertEquals(1, gateway.refreshCalls)

        now += 20 * 60 * 1000L
        vm.advanceToNextWallpaperSmart()
        assertEquals("still inside the hour", 1, gateway.refreshCalls)

        now += 41 * 60 * 1000L
        vm.advanceToNextWallpaperSmart()
        assertEquals(2, gateway.refreshCalls)
    }

    @Test
    fun `Next does not fetch on Wi-Fi-only setups`() = kotlinx.coroutines.test.runTest {
        val gateway = CountingGateway(stateWith(unseenCount = 0, setupCompleted = false, wifiOnly = true))
        launch(gateway).advanceToNextWallpaperSmart()
        assertEquals(0, gateway.refreshCalls)
    }

    @Test
    fun `the Refresh button always fetches and keeps going while below the target`() {
        val gateway = CountingGateway(stateWith(unseenCount = 0, wifiOnly = true)) { 5 }
        val vm = launch(gateway)
        assertEquals(0, gateway.refreshCalls)
        vm.triggerOnlineSync()
        assertEquals(3, gateway.refreshCalls)
    }

    @Test
    fun `a second trigger while a top up is running does not start another or arm the idle timer`() = kotlinx.coroutines.test.runTest {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gateway = object : SunloomGateway by CountingGateway(stateWith(unseenCount = 0)) {
            var calls = 0
            val inner = CountingGateway(stateWith(unseenCount = 0))
            override suspend fun refreshFromInternet(): SunloomUiState {
                calls++
                if (calls == 1) release.await()
                return inner.state
            }
            override suspend fun loadInitialSnapshot() = inner.state
        }
        val vm = MainViewModel(gateway = gateway, workDispatcher = UnconfinedTestDispatcher(), clockMs = { now }, roundPauseMs = 0L)
        // The launch top up is now suspended inside the first fetch.
        vm.advanceToNextWallpaperSmart()
        vm.advanceToNextWallpaperSmart()
        assertEquals("overlapping triggers must not start more fetches", 1, gateway.calls)
        release.complete(Unit)
    }
}
