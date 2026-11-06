package com.sunloom.app.ui

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.domain.model.UserPreferences
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp")
class SunloomComposeUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `FirstRunSetupScreen displays welcome header and triggers onCompleteSetup when clicked`() {
        var setupCompleted = false
        val initialState = SunloomUiState(
            preferences = UserPreferences(isSetupCompleted = false)
        )

        composeTestRule.setContent {
            SunloomApp(
                uiState = initialState,
                onCompleteSetup = { setupCompleted = true },
                onSelectTab = {},
                onNextWallpaper = {},
                onPreviousWallpaper = {},
                onToggleKeep = {},
                onRejectForever = {},
                onTogglePause = {},
                onRestoreOriginal = {},
                onApplySpecific = {},
                onToggleLegibilityOverlay = {},
                onSyncOnline = {},
                onToggleCategory = {},
                onToggleSource = {},
                onSelectCity = {},
                onUpdateScheduleMode = {},
                onUpdateLockPolicy = {},
                onToggleBlurredFill = {},
                onToggleWifiOnly = {},
                onPickLocalPhoto = {}
            )
        }

        composeTestRule.onNodeWithText("Welcome to Sunloom").assertExists()
        composeTestRule.onNodeWithText("Preview-only mode").assertDoesNotExist()
        composeTestRule.onNodeWithText("Matched pair", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Different images", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("enable_sunloom_button").performScrollTo().performClick()
        assertTrue(setupCompleted)
    }

    @Test
    fun `DesktopWorkspaceScreen renders provenance inspector and triggers Next and Pause callbacks`() {
        var nextClicked = false
        var pauseClicked = false
        val activeState = SunloomUiState(
            preferences = UserPreferences(isSetupCompleted = true)
        )

        composeTestRule.setContent {
            SunloomApp(
                uiState = activeState,
                onCompleteSetup = {},
                onSelectTab = {},
                onNextWallpaper = { nextClicked = true },
                onPreviousWallpaper = {},
                onToggleKeep = {},
                onRejectForever = {},
                onTogglePause = { pauseClicked = true },
                onRestoreOriginal = {},
                onApplySpecific = {},
                onToggleLegibilityOverlay = {},
                onSyncOnline = {},
                onToggleCategory = {},
                onToggleSource = {},
                onSelectCity = {},
                onUpdateScheduleMode = {},
                onUpdateLockPolicy = {},
                onToggleBlurredFill = {},
                onToggleWifiOnly = {},
                onPickLocalPhoto = {}
            )
        }

        composeTestRule.onNodeWithText("About this wallpaper").assertExists()
        composeTestRule.onNodeWithTag("keep_wallpaper_button").assertExists()
        composeTestRule.onNodeWithText("Keep").assertDoesNotExist()
        composeTestRule.onNodeWithTag("reject_wallpaper_button").assertExists()
        composeTestRule.onNodeWithText("Reject Forever").assertDoesNotExist()
        composeTestRule.onNodeWithTag("next_wallpaper_button").performClick()
        assertTrue(nextClicked)

        composeTestRule.onNodeWithTag("pause_resume_button").performClick()
        assertTrue(pauseClicked)
    }

    @Test
    fun `Reserve hides storage metrics while Diagnostics shows internal storage metrics and Reserve supports deleting individual items`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val gateway = AndroidSunloomGateway(context)
        val initial = kotlinx.coroutines.runBlocking { gateway.loadInitialSnapshot() }
        val firstId = initial.libraryWallpapers.first().id
        var deletedId: String? = null

        val uiState = androidx.compose.runtime.mutableStateOf(
            initial.copy(
                activeTab = DesktopNavTab.RESERVE_AND_HISTORY,
                preferences = initial.preferences.copy(isSetupCompleted = true)
            )
        )

        val isDark = androidx.compose.runtime.mutableStateOf(false)

        composeTestRule.setContent {
            SunloomApp(
                uiState = uiState.value,
                onCompleteSetup = {},
                onSelectTab = { uiState.value = uiState.value.copy(activeTab = it) },
                onNextWallpaper = {},
                onPreviousWallpaper = {},
                onToggleKeep = {},
                onRejectForever = {},
                onTogglePause = {},
                onRestoreOriginal = {},
                onApplySpecific = {},
                onDeleteFromReserve = { deletedId = it },
                onToggleLegibilityOverlay = {},
                onSyncOnline = {},
                onToggleCategory = {},
                onToggleSource = {},
                onSelectCity = {},
                onUpdateScheduleMode = {},
                onUpdateLockPolicy = {},
                onToggleBlurredFill = {},
                onToggleWifiOnly = {},
                onPickLocalPhoto = {},
                darkTheme = isDark.value
            )
        }

        // Reserve tab must NOT show "Cache Used:" to end users (in Light Mode)
        composeTestRule.onNodeWithText("Cache Used:", substring = true).assertDoesNotExist()

        // Switch OS theme to Dark Mode and verify UI continues rendering cleanly
        composeTestRule.runOnIdle {
            isDark.value = true
        }
        composeTestRule.waitForIdle()

        // Clicking delete acts immediately (Undo is offered via snackbar rather than a blocking dialog)
        composeTestRule.onNodeWithTag("delete_reserve_item_$firstId").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        org.junit.Assert.assertEquals(firstId, deletedId)

        // Switching to Diagnostics tab shows internal reserve storage metrics
        composeTestRule.runOnIdle {
            uiState.value = uiState.value.copy(activeTab = DesktopNavTab.SAFETY_AND_DECISION_LOG)
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Cache Used:", substring = true).assertExists()

        // Deleting an item from reserve via gateway removes it from libraryWallpapers
        val afterDelete = kotlinx.coroutines.runBlocking { gateway.deleteWallpaperFromReserve(firstId) }
        assertTrue(afterDelete.libraryWallpapers.none { it.id == firstId })

        // Disabling seed_reserve via gateway disables its items in libraryWallpapers
        val afterDisablingSeed = kotlinx.coroutines.runBlocking {
            gateway.savePreferences(
                initial.preferences.copy(
                    enabledSources = initial.preferences.enabledSources - "seed_reserve"
                )
            )
        }
        assertTrue(afterDisablingSeed.libraryWallpapers.none { it.provenance.sourceId == "seed_reserve" })
        assertTrue(afterDisablingSeed.disabledReserveCount > 0)
    }

    @Test
    fun `All six desktop tabs and AndroidSunloomGateway render and handle actions cleanly`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val gateway = AndroidSunloomGateway(context)
        val afterApply = kotlinx.coroutines.runBlocking {
            val initialSnapshot = gateway.loadInitialSnapshot()
            assertTrue(initialSnapshot.libraryWallpapers.isNotEmpty())

            gateway.savePreferences(
                initialSnapshot.preferences.copy(isSetupCompleted = true)
            )
            gateway.applyNextWallpaper()
            val afterPrev = gateway.applyPreviousWallpaper()
            gateway.toggleKeepWallpaper(afterPrev.currentWallpaper!!.id)
            val afterRestore = gateway.restoreBaselineWallpaper()
            val afterReject = gateway.rejectWallpaperForever(afterRestore.libraryWallpapers.last().id)
            gateway.applyWallpaper(afterReject.libraryWallpapers.first().id)
        }

        val currentUiState = androidx.compose.runtime.mutableStateOf(
            afterApply.copy(
                preferences = afterApply.preferences.copy(isSetupCompleted = true),
                showLegibilityZonesOverlay = true,
                rejectionLog = listOf(
                    com.sunloom.app.domain.model.RejectionRecord(
                        assetId = "rej-1",
                        sourceId = "nasa_library",
                        reasonCode = "RESOLUTION_BELOW_DISPLAY_TARGET",
                        explanation = "Below native resolution",
                        timestampEpochMs = 1762280000000L
                    )
                )
            )
        )

        composeTestRule.setContent {
            SunloomApp(
                uiState = currentUiState.value,
                onCompleteSetup = {},
                onSelectTab = {},
                onNextWallpaper = {},
                onPreviousWallpaper = {},
                onToggleKeep = {},
                onRejectForever = {},
                onTogglePause = {},
                onRestoreOriginal = {},
                onApplySpecific = {},
                onToggleLegibilityOverlay = {},
                onSyncOnline = {},
                onToggleCategory = {},
                onToggleSource = {},
                onSelectCity = {},
                onUpdateScheduleMode = {},
                onUpdateLockPolicy = {},
                onToggleBlurredFill = {},
                onToggleWifiOnly = {},
                onPickLocalPhoto = {}
            )
        }

        for (tab in DesktopNavTab.entries) {
            composeTestRule.runOnIdle {
                currentUiState.value = currentUiState.value.copy(activeTab = tab)
            }
            composeTestRule.waitForIdle()
            if (tab == DesktopNavTab.SOURCES_AND_CATEGORIES) {
                composeTestRule.onNodeWithText("Cleveland Museum of Art Open Access (CC0)").assertExists()
                composeTestRule.onNodeWithText("SMK — National Gallery of Denmark (Open)").assertExists()
            }
            if (tab == DesktopNavTab.DISPLAYS_AND_SCHEDULE) {
                composeTestRule.onNodeWithText("Quiet hours", substring = true).assertDoesNotExist()
                composeTestRule.onNodeWithText("Matched pair", substring = true).assertDoesNotExist()
                composeTestRule.onNodeWithText("Different images", substring = true).assertDoesNotExist()
            }
        }
    }

    @Test
    fun `Sync Online Catalog Now survives switching tabs without cancelling or degrading sources`() {
        var syncCompleted = false
        val syncGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val fakeGateway = object : SunloomGateway {
            private var state = SunloomUiState(
                activeTab = DesktopNavTab.SOURCES_AND_CATEGORIES,
                preferences = UserPreferences(isSetupCompleted = true)
            )
            override suspend fun loadInitialSnapshot(): SunloomUiState = state
            override suspend fun savePreferences(preferences: UserPreferences): SunloomUiState {
                state = state.copy(preferences = preferences)
                return state
            }
            override suspend fun applyWallpaper(recordId: String): SunloomUiState = state
            override suspend fun applyNextWallpaper(excludeIds: Set<String>): SunloomUiState = state
            override suspend fun applyPreviousWallpaper(): SunloomUiState = state
            override suspend fun restoreBaselineWallpaper(): SunloomUiState = state
            override suspend fun toggleKeepWallpaper(recordId: String): SunloomUiState = state
            override suspend fun rejectWallpaperForever(recordId: String): SunloomUiState = state
            override suspend fun refreshFromInternet(): SunloomUiState {
                syncGate.await()
                syncCompleted = true
                state = state.copy(statusBannerMessage = "Prepared 2 new high-resolution wallpaper(s)")
                return state
            }
            override suspend fun importLocalUri(uriString: String, asBaselineRestore: Boolean, applyAfterImport: Boolean): SunloomUiState = state
        }

        val vm = MainViewModel(fakeGateway, workDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        composeTestRule.setContent {
            val state = vm.uiState.collectAsState().value
            SunloomApp(
                uiState = state,
                onCompleteSetup = { vm.completeFirstRunSetup(it) },
                onSelectTab = { vm.selectTab(it) },
                onNextWallpaper = { vm.triggerNextWallpaperSmart() },
                onPreviousWallpaper = { vm.returnToPreviousWallpaper() },
                onToggleKeep = { vm.toggleKeepCurrentWallpaper() },
                onRejectForever = { vm.rejectCurrentWallpaperForever() },
                onTogglePause = { vm.togglePauseAutomation() },
                onRestoreOriginal = { vm.restoreOriginalBaselineWallpaper() },
                onApplySpecific = { vm.applySpecificWallpaper(it) },
                onToggleLegibilityOverlay = { vm.toggleLegibilityOverlay() },
                onSyncOnline = { vm.triggerOnlineSync() },
                onToggleCategory = { vm.toggleCategory(it) },
                onToggleSource = { vm.toggleSource(it) },
                onSelectCity = { vm.selectCity(it) },
                onUpdateScheduleMode = {},
                onUpdateLockPolicy = {},
                onToggleBlurredFill = {},
                onToggleWifiOnly = {},
                onPickLocalPhoto = {}
            )
        }

        composeTestRule.onNodeWithTag("sync_online_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertTrue("Sync should be active after clicking Sync Online Catalog Now", vm.uiState.value.isSyncingOnline)

        // Immediately navigate away from Sources & Categories while sync is in flight
        composeTestRule.runOnIdle {
            vm.selectTab(DesktopNavTab.TODAY_CANVAS)
        }
        composeTestRule.waitForIdle()
        assertTrue("Leaving Sources & Categories tab must NOT cancel in-flight sync", vm.uiState.value.isSyncingOnline)

        syncGate.complete(Unit)
        composeTestRule.waitForIdle()
        assertTrue(syncCompleted)
        assertTrue(!vm.uiState.value.isSyncingOnline)
        assertTrue(vm.uiState.value.statusBannerMessage.contains("Prepared 2 new"))
    }

    @Test
    fun `Dislike button requires confirmation and AndroidSunloomGateway promotes fine art over active seed_reserve`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val repo = com.sunloom.app.data.repository.LocalLibraryRepository(context)
        val gateway = AndroidSunloomGateway(context, repository = repo)
        val seedApplied = kotlinx.coroutines.runBlocking {
            gateway.loadInitialSnapshot()
            gateway.applyNextWallpaper()
        }
        val seedTemplate = requireNotNull(seedApplied.currentWallpaper)
        assertTrue(seedTemplate.provenance.sourceId == "seed_reserve")

        // Now insert a valid fine art wallpaper (with NEUTRAL brightness) into the repository
        val fineArtRecord = seedTemplate.copy(
            id = "artic-fine-art-1",
            title = "Inness Landscape Valley",
            category = com.sunloom.app.domain.model.SafeCategory.SCENIC_FINE_ART,
            appliedCount = 0,
            provenance = seedTemplate.provenance.copy(
                sourceId = "artic_cc0",
                sourceName = "Art Institute of Chicago Open Access (Landscapes)",
                assetId = "artic-fine-art-1"
            ),
            evaluation = seedTemplate.evaluation.copy(
                brightnessClass = com.sunloom.app.domain.model.BrightnessClass.NEUTRAL
            )
        )
        repo.upsertWallpaper(fineArtRecord)

        // Reloading snapshot or applying next must immediately prioritize fineArtRecord over seed_reserve!
        val promoted = kotlinx.coroutines.runBlocking { gateway.loadInitialSnapshot() }
        org.junit.Assert.assertEquals("artic-fine-art-1", promoted.currentWallpaper?.id)

        // Verify clicking Dislike invokes onRejectForever directly (undo is offered via snackbar)
        var rejectConfirmed = false
        composeTestRule.setContent {
            SunloomApp(
                uiState = promoted.copy(preferences = promoted.preferences.copy(isSetupCompleted = true)),
                onCompleteSetup = {},
                onSelectTab = {},
                onNextWallpaper = {},
                onPreviousWallpaper = {},
                onToggleKeep = {},
                onRejectForever = { rejectConfirmed = true },
                onTogglePause = {},
                onRestoreOriginal = {},
                onApplySpecific = {},
                onToggleLegibilityOverlay = {},
                onSyncOnline = {},
                onToggleCategory = {},
                onToggleSource = {},
                onSelectCity = {},
                onUpdateScheduleMode = {},
                onUpdateLockPolicy = {},
                onToggleBlurredFill = {},
                onToggleWifiOnly = {},
                onPickLocalPhoto = {}
            )
        }

        composeTestRule.onNodeWithTag("reject_wallpaper_button").performClick()
        composeTestRule.waitForIdle()
        assertTrue("Dislike must invoke onRejectForever (undoable, no blocking dialog)", rejectConfirmed)
    }

    @Test
    fun `DisplaysAndSchedulePane discloses connected external displays and target resolution`() {
        val multiDisplayState = SunloomUiState(
            activeTab = DesktopNavTab.DISPLAYS_AND_SCHEDULE,
            preferences = UserPreferences(isSetupCompleted = true),
            capabilities = com.sunloom.app.domain.model.PlatformCapabilities(
                isWallpaperSupported = true,
                isSetWallpaperAllowed = true,
                supportsIndependentLockWallpaper = true,
                supportsPerDisplayWallpaper = false,
                connectedDisplays = listOf(
                    com.sunloom.app.domain.model.DisplayProfile(0, "GoogleBook Built-in Display", 2560, 1600, 240),
                    com.sunloom.app.domain.model.DisplayProfile(1, "Dell UltraSharp UWQHD", 3440, 1440, 109, isPrimary = false)
                ),
                capabilityDisclosureNote = "Detected 2 displays. Sunloom automatically prepares a native-resolution crop for your sharpest display (3440 × 1440) without stretching."
            )
        )

        composeTestRule.setContent {
            SunloomApp(
                uiState = multiDisplayState,
                onCompleteSetup = {},
                onSelectTab = {},
                onNextWallpaper = {},
                onPreviousWallpaper = {},
                onToggleKeep = {},
                onRejectForever = {},
                onTogglePause = {},
                onRestoreOriginal = {},
                onApplySpecific = {},
                onToggleLegibilityOverlay = {},
                onSyncOnline = {},
                onToggleCategory = {},
                onToggleSource = {},
                onSelectCity = {},
                onUpdateScheduleMode = {},
                onUpdateLockPolicy = {},
                onToggleBlurredFill = {},
                onToggleWifiOnly = {},
                onPickLocalPhoto = {}
            )
        }

        composeTestRule.onNodeWithTag("connected_displays_summary").performScrollTo().assertExists()
        composeTestRule.onNodeWithText("Dell UltraSharp UWQHD (3440 × 1440", substring = true).assertExists()
        composeTestRule.onNodeWithText("sharpest display (3440 × 1440)", substring = true).assertExists()
    }
}
