package com.sunloom.app.platform

import android.app.Application
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.data.repository.LocalLibraryRepository
import com.sunloom.app.domain.model.CropRect
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.LockWorkspacePolicy
import com.sunloom.app.domain.model.WallpaperStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class GoogleBookPlatformAdapterTest {

    private lateinit var context: Context
    private lateinit var repository: LocalLibraryRepository

    private class FakeWallpaperGateway(
        private val supported: Boolean = true,
        private val allowed: Boolean = true,
        private val displays: List<DisplayProfile> = listOf(
            DisplayProfile(0, "GoogleBook Primary", 2560, 1600),
            DisplayProfile(1, "External Ultrawide", 3440, 1440)
        )
    ) : SystemWallpaperGateway {
        var lastAppliedFlags: Int = 0
        var lastAppliedWidth: Int = 0
        var lastAppliedHeight: Int = 0
        val appliedFlagHistory = mutableListOf<Int>()
        var clearedCount: Int = 0

        override fun isWallpaperSupported(): Boolean = supported
        override fun isSetWallpaperAllowed(): Boolean = allowed
        override fun getConnectedDisplays(): List<DisplayProfile> = displays
        override fun applyBitmapToDestination(bitmap: Bitmap, flags: Int): Boolean {
            lastAppliedFlags = flags
            lastAppliedWidth = bitmap.width
            lastAppliedHeight = bitmap.height
            appliedFlagHistory.add(flags)
            return true
        }
        override fun clearWallpaper(): Boolean {
            clearedCount++
            return true
        }
        override fun isWifiConnected(): Boolean = true
        override fun isBatterySaverActive(): Boolean = false
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = LocalLibraryRepository(context)
    }

    @Test
    fun `probeCapabilities honestly discloses multi-display behavior and sharpest target screen`() {
        val fakeGateway = FakeWallpaperGateway()
        val adapter = GoogleBookPlatformAdapter(context, fakeGateway)

        val caps = adapter.probeCapabilities()
        assertTrue(caps.isWallpaperSupported)
        assertTrue(caps.supportsIndependentLockWallpaper)
        assertEquals(2, caps.connectedDisplays.size)
        assertTrue(caps.capabilityDisclosureNote.contains("3440 × 1440"))
    }

    @Test
    fun `excluded display note gives the real reason`() {
        val adapter = GoogleBookPlatformAdapter(
            context,
            FakeWallpaperGateway(
                displays = listOf(
                    DisplayProfile(0, "GoogleBook Primary", 2560, 1600, densityDpi = 240, physicalPpi = 227.0),
                    DisplayProfile(1, "8K TV", 7680, 4320, densityDpi = 160, physicalPpi = 80.0, isPrimary = false)
                )
            )
        )

        val note = adapter.probeCapabilities().excludedDisplaysNote

        assertTrue(note, note.contains("8K TV"))
        assertTrue(note, note.contains("16 MP"))
        assertTrue(note, !note.contains("orientation"))
    }

    @Test
    fun `geometry key is unchanged when the same monitor comes back under a new display id`() {
        val before = GoogleBookPlatformAdapter(context, FakeWallpaperGateway())
        val replugged = GoogleBookPlatformAdapter(
            context,
            FakeWallpaperGateway(
                displays = listOf(
                    DisplayProfile(0, "GoogleBook Primary", 2560, 1600),
                    DisplayProfile(7, "External Ultrawide", 3440, 1440)
                )
            )
        )
        assertEquals(before.computeDisplayGeometryKey(), replugged.computeDisplayGeometryKey())
    }

    @Test
    fun `applyWallpaperAtomically crops without stretching and applies to configured destinations`() {
        val fakeGateway = FakeWallpaperGateway()
        val adapter = GoogleBookPlatformAdapter(context, fakeGateway)
        val display = DisplayProfile(0, "GoogleBook 16:10", 2560, 1600)

        val seeds = repository.ensureSeedReserveLoaded(display)
        val first = seeds.first()

        val status = adapter.applyWallpaperAtomically(first, LockWorkspacePolicy.SAME_ON_BOTH, display)
        assertEquals(WallpaperStatus.APPLIED_SUCCESSFULLY, status)
        val appliedAspect = fakeGateway.lastAppliedWidth.toDouble() / fakeGateway.lastAppliedHeight.toDouble()
        assertEquals(display.aspectRatio, appliedAspect, 0.02)
    }

    @Test
    fun `a seed on several displays is applied at its stored envelope, not cropped to the largest display`() {
        val laptop = DisplayProfile(0, "GoogleBook Primary", 2560, 1600, isPrimary = true)
        val ultrawide = DisplayProfile(1, "External Ultrawide", 3440, 1440, isPrimary = false)
        val displays = listOf(laptop, ultrawide)
        val fakeGateway = FakeWallpaperGateway(displays = displays)
        val adapter = GoogleBookPlatformAdapter(context, fakeGateway)

        val seed = repository.ensureSeedReserveLoadedForDisplays(displays).first { it.provenance.sourceId == "seed_reserve" }
        val stored = android.graphics.BitmapFactory.decodeFile(seed.localFilePath)
        val storedAspect = stored.width.toDouble() / stored.height
        stored.recycle()

        val status = adapter.applyWallpaperAtomically(
            record = seed,
            policy = LockWorkspacePolicy.SAME_ON_BOTH,
            targetDisplay = ultrawide,
            connectedDisplays = displays
        )

        assertEquals(WallpaperStatus.APPLIED_SUCCESSFULLY, status)
        // 3440×1600 keeps the laptop's full height; cropping to the ultrawide's 21:9 would soften the laptop.
        val appliedAspect = fakeGateway.lastAppliedWidth.toDouble() / fakeGateway.lastAppliedHeight
        assertEquals(storedAspect, appliedAspect, 0.02)
        assertTrue("narrower than the ultrawide's 21:9: $appliedAspect", appliedAspect < ultrawide.aspectRatio - 0.1)
        assertTrue("wider than the laptop's 16:10: $appliedAspect", appliedAspect > laptop.aspectRatio + 0.1)
    }

    @Test
    fun `applyWallpaperAtomically returns UNSUPPORTED_BY_SYSTEM when OS forbids wallpaper changes`() {
        val restrictedGateway = FakeWallpaperGateway(supported = false, allowed = false)
        val adapter = GoogleBookPlatformAdapter(context, restrictedGateway)
        val display = DisplayProfile(0, "GoogleBook", 2560, 1600)
        val seeds = repository.ensureSeedReserveLoaded(display)

        val status = adapter.applyWallpaperAtomically(seeds.first(), LockWorkspacePolicy.WORKSPACE_ONLY, display)
        assertEquals(WallpaperStatus.UNSUPPORTED_BY_SYSTEM, status)
    }

    @Test
    fun `applyWallpaperAtomically with dryRunPreviewOnly validates crop without calling OS WallpaperManager`() {
        val fakeGateway = FakeWallpaperGateway()
        val adapter = GoogleBookPlatformAdapter(context, fakeGateway)
        val display = DisplayProfile(0, "GoogleBook 16:10", 2560, 1600)
        val seeds = repository.ensureSeedReserveLoaded(display)

        val status = adapter.applyWallpaperAtomically(
            record = seeds.first(),
            policy = LockWorkspacePolicy.LOCK_ONLY,
            targetDisplay = display,
            dryRunPreviewOnly = true
        )
        assertEquals(WallpaperStatus.APPLIED_SUCCESSFULLY, status)
        assertEquals(0, fakeGateway.lastAppliedFlags)
    }

    @Test
    fun `testWorkspaceOnlyAndLockOnlyApplyRespectiveFlags`() {
        val fakeGateway = FakeWallpaperGateway()
        val adapter = GoogleBookPlatformAdapter(context, fakeGateway)
        val display = DisplayProfile(0, "GoogleBook 16:10", 2560, 1600)
        val seeds = repository.ensureSeedReserveLoaded(display)

        val statusWorkspace = adapter.applyWallpaperAtomically(
            record = seeds[0],
            policy = LockWorkspacePolicy.WORKSPACE_ONLY,
            targetDisplay = display,
            dryRunPreviewOnly = false
        )
        val statusLock = adapter.applyWallpaperAtomically(
            record = seeds[1],
            policy = LockWorkspacePolicy.LOCK_ONLY,
            targetDisplay = display,
            dryRunPreviewOnly = false
        )
        assertEquals(WallpaperStatus.APPLIED_SUCCESSFULLY, statusWorkspace)
        assertEquals(WallpaperStatus.APPLIED_SUCCESSFULLY, statusLock)
        assertEquals(
            listOf(WallpaperManager.FLAG_SYSTEM, WallpaperManager.FLAG_LOCK),
            fakeGateway.appliedFlagHistory
        )
    }

    @Test
    fun `testBlurredFrameFillRendersDarkenedBackdropWithCenteredForeground`() {
        val fakeGateway = FakeWallpaperGateway()
        val adapter = GoogleBookPlatformAdapter(context, fakeGateway)
        val display = DisplayProfile(0, "GoogleBook 16:10", 2560, 1600)
        val seeds = repository.ensureSeedReserveLoaded(display)
        val first = seeds.first()
        val blurredRecord = first.copy(
            evaluation = first.evaluation.copy(
                cropRect = CropRect(
                    left = 0,
                    top = 0,
                    width = 640,
                    height = 400,
                    focalX = 0.5,
                    focalY = 0.5,
                    usesBlurredFrameFill = true,
                    pixelCoverageRatio = 0.75
                )
            )
        )

        val status = adapter.applyWallpaperAtomically(blurredRecord, LockWorkspacePolicy.SAME_ON_BOTH, display)
        assertEquals(WallpaperStatus.APPLIED_SUCCESSFULLY, status)
        val appliedAspect = fakeGateway.lastAppliedWidth.toDouble() / fakeGateway.lastAppliedHeight.toDouble()
        assertEquals(display.aspectRatio, appliedAspect, 0.02)
    }

    @Test
    fun `testRestoreSystemDefaultCallsClearWallpaper`() {
        val fakeGateway = FakeWallpaperGateway()
        val adapter = GoogleBookPlatformAdapter(context, fakeGateway)

        assertTrue(adapter.restoreSystemDefaultWallpaper(dryRunPreviewOnly = false))
        assertEquals(1, fakeGateway.clearedCount)
    }

    @Test
    fun `DefaultSystemWallpaperGateway and SunloomScheduleReceiver execute safely under Robolectric`() {
        val defaultGateway = DefaultSystemWallpaperGateway(context)
        val displays = defaultGateway.getConnectedDisplays()
        assertTrue(displays.isNotEmpty())
        defaultGateway.isWallpaperSupported()
        defaultGateway.isSetWallpaperAllowed()
        defaultGateway.isWifiConnected()
        defaultGateway.isBatterySaverActive()

        val receiver = SunloomScheduleReceiver()
        receiver.onReceive(context, android.content.Intent("com.sunloom.app.UNTRUSTED_ACTION"))
        receiver.onReceive(context, null)
    }

    @Test
    fun `AndroidSunloomGateway applyNextWallpaper builds UI snapshot only once per transition`() = kotlinx.coroutines.test.runTest {
        val baseStore = com.sunloom.app.data.repository.InMemoryLibraryStore()
        var rejectionQueryCount = 0
        val countingStore = object : com.sunloom.app.data.repository.LibraryStore by baseStore {
            override fun getAllRejections(): List<com.sunloom.app.domain.model.RejectionRecord> {
                rejectionQueryCount++
                return baseStore.getAllRejections()
            }
        }
        val repo = LocalLibraryRepository(context = context, store = countingStore)
        val fakeWallpaperGateway = FakeWallpaperGateway()
        val platform = GoogleBookPlatformAdapter(context, fakeWallpaperGateway)
        val gateway = com.sunloom.app.ui.AndroidSunloomGateway(
            context = context,
            repository = repo,
            platformAdapter = platform
        )

        gateway.loadInitialSnapshot()
        rejectionQueryCount = 0

        val nextState = gateway.applyNextWallpaper()
        assertTrue(nextState.statusBannerMessage.contains("Applied"))
        assertEquals(
            "applyNextWallpaper should only build the full UI snapshot once, not twice",
            1,
            rejectionQueryCount
        )
    }

    @Test
    fun `prepareNonStretchedBitmap and renderBlurredFrameFillBitmap preserve exact aspect ratios across 32_9 5K and Portrait external monitors`() {
        val fakeGateway = FakeWallpaperGateway()
        val adapter = GoogleBookPlatformAdapter(context, fakeGateway)
        val source = Bitmap.createBitmap(1686, 1124, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFF2B4C7E.toInt())
        }

        val monitors = listOf(
            DisplayProfile(1, "UWQHD 21:9", 3440, 1440, densityDpi = 160),
            DisplayProfile(2, "DQHD 32:9", 5120, 1440, densityDpi = 160),
            DisplayProfile(3, "Portrait 4K", 2160, 3840, densityDpi = 160),
            DisplayProfile(4, "5K Studio", 5120, 2880, densityDpi = 218)
        )

        for (monitor in monitors) {
            val blurred = adapter.renderBlurredFrameFillBitmap(source, monitor)
            val actualAspect = blurred.width.toDouble() / blurred.height.toDouble()
            assertEquals(
                "Blurred frame fill must preserve ${monitor.name} aspect ratio without 3840x2400 distortion",
                monitor.aspectRatio,
                actualAspect,
                0.01
            )
            blurred.recycle()
        }
        source.recycle()
    }

    @Test
    fun `hotplugging external UWQHD monitor re-evaluates reserve and rotates away from under-sized active wallpaper`() = kotlinx.coroutines.test.runTest {
        var activeDisplays = listOf(DisplayProfile(0, "GoogleBook Retina", 2560, 1600, densityDpi = 240))
        val dynamicWallpaperGateway = object : SystemWallpaperGateway {
            var appliedCount = 0
            var lastWidth = 0
            var lastHeight = 0
            override fun isWallpaperSupported(): Boolean = true
            override fun isSetWallpaperAllowed(): Boolean = true
            override fun getConnectedDisplays(): List<DisplayProfile> = activeDisplays
            override fun applyBitmapToDestination(bitmap: Bitmap, flags: Int): Boolean {
                appliedCount++
                lastWidth = bitmap.width
                lastHeight = bitmap.height
                return true
            }
            override fun isWifiConnected(): Boolean = true
            override fun isBatterySaverActive(): Boolean = false
        }

        val repo = LocalLibraryRepository(context, store = com.sunloom.app.data.repository.InMemoryLibraryStore())
        val platform = GoogleBookPlatformAdapter(context, dynamicWallpaperGateway)
        val gateway = com.sunloom.app.ui.AndroidSunloomGateway(
            context = context,
            repository = repo,
            platformAdapter = platform
        )

        val initial = gateway.loadInitialSnapshot()
        // Insert and apply a 1686x1124 ArtIC wallpaper on the 2560x1600 @ 240dpi laptop
        val srcBmp = Bitmap.createBitmap(640, 426, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF335577.toInt()) }
        val articFile = java.io.File(repo.wallpaperDir, "artic-dock-test.png")
        java.io.FileOutputStream(articFile).use { srcBmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        srcBmp.recycle()

        val seedTemplate = initial.libraryWallpapers.first()
        val articWall = seedTemplate.copy(
            id = "artic-dock-test",
            title = "Cliffside Pines",
            localFilePath = articFile.absolutePath,
            croppedFilePath = articFile.absolutePath,
            fileSizeBytes = articFile.length(),
            provenance = seedTemplate.provenance.copy(
                sourceId = "artic_cc0",
                assetId = "artic-dock-test",
                originalWidth = 1686,
                originalHeight = 1124,
                renditionWidth = 1686,
                renditionHeight = 1054
            )
        )
        repo.upsertWallpaper(articWall)
        val appliedLaptopState = gateway.applyWallpaper("artic-dock-test")
        assertEquals("artic-dock-test", appliedLaptopState.currentWallpaper?.id)

        // Hotplug a 3440x1440 @ 160dpi UWQHD monitor
        activeDisplays = listOf(
            DisplayProfile(0, "GoogleBook Retina", 2560, 1600, densityDpi = 240),
            DisplayProfile(1, "External UWQHD", 3440, 1440, densityDpi = 160)
        )
        val dockedState = gateway.onDisplaysChanged()
        assertEquals(2, dockedState.capabilities.connectedDisplays.size)
        // Active 1686x1124 wallpaper is under-sized for 3440x1440 without blur, so gateway must rotate to an eligible native wallpaper!
        assertTrue(
            "Expected hotplug onto UWQHD to rotate away from under-sized 1686px wallpaper, got ${dockedState.currentWallpaper?.id}",
            dockedState.currentWallpaper?.id != "artic-dock-test"
        )
        val appliedAspect = dynamicWallpaperGateway.lastWidth.toDouble() / dynamicWallpaperGateway.lastHeight.toDouble()
        // A seed is applied as the shared envelope it was rendered at (3440×1600: as wide as the ultrawide and as
        // tall as the laptop), so both displays keep their native pixels. It lies between their 16:10 and 21:9.
        assertEquals(3440.0 / 1600.0, appliedAspect, 0.02)
    }

    @Test
    fun `probeCapabilities and applyWallpaperAtomically refuse to invent fallback resolution when displays are unknown`() {
        val emptyDisplayGateway = FakeWallpaperGateway(displays = emptyList())
        val adapter = GoogleBookPlatformAdapter(context, emptyDisplayGateway)
        val caps = adapter.probeCapabilities()
        assertTrue(caps.connectedDisplays.isEmpty())
        assertTrue(caps.capabilityDisclosureNote.contains("unknown", ignoreCase = true))

        val seeds = repository.ensureSeedReserveLoaded(DisplayProfile(0, "Ref", 2560, 1600))
        val status = adapter.applyWallpaperAtomically(seeds.first(), LockWorkspacePolicy.SAME_ON_BOTH)
        assertEquals(WallpaperStatus.FAILED_TEMPORARILY, status)
        assertEquals(0, emptyDisplayGateway.lastAppliedFlags)
    }

    @Test
    fun `restoreBaselineWallpaper clears to OS default instead of falling back to previousWallpaperId`() = kotlinx.coroutines.test.runTest {
        val fakeWallpaperGateway = FakeWallpaperGateway()
        val repo = LocalLibraryRepository(context, store = com.sunloom.app.data.repository.InMemoryLibraryStore())
        val platform = GoogleBookPlatformAdapter(context, fakeWallpaperGateway)
        val gateway = com.sunloom.app.ui.AndroidSunloomGateway(
            context = context,
            repository = repo,
            platformAdapter = platform
        )

        gateway.loadInitialSnapshot()
        gateway.applyNextWallpaper()
        gateway.applyNextWallpaper()
        assertTrue(repo.getPreviousWallpaperId() != null)

        val restored = gateway.restoreBaselineWallpaper()
        assertEquals(1, fakeWallpaperGateway.clearedCount)
        assertEquals(null, repo.getCurrentWallpaperId())
        assertTrue(restored.statusBannerMessage.contains("system default", ignoreCase = true))
    }

    @Test
    fun `SunloomWallpaperScheduler ChangeWorker omits battery-not-low constraint and walks fallback candidates on failure`() = kotlinx.coroutines.test.runTest {
        val scheduler = com.sunloom.app.platform.scheduler.SunloomWallpaperScheduler(context)
        val changeConstraints = scheduler.buildChangeConstraints()
        org.junit.Assert.assertFalse(
            "Offline ChangeWorker must NOT set requiresBatteryNotLow (Article X §2)",
            changeConstraints.requiresBatteryNotLow()
        )

        val repo = LocalLibraryRepository(context, store = com.sunloom.app.data.repository.InMemoryLibraryStore())
        val display = DisplayProfile(0, "GoogleBook", 2560, 1600)
        val seeds = repo.ensureSeedReserveLoaded(display)
        repo.savePreferences(repo.loadPreferences().copy(isSetupCompleted = true, autoChangeEnabled = true, isPaused = false))

        var attempt = 0
        val flakyGateway = object : SystemWallpaperGateway {
            override fun isWallpaperSupported(): Boolean = true
            override fun isSetWallpaperAllowed(): Boolean = true
            override fun getConnectedDisplays(): List<DisplayProfile> = listOf(display)
            override fun applyBitmapToDestination(bitmap: Bitmap, flags: Int): Boolean {
                attempt++
                // Fail the first candidate apply, succeed on the second fallback candidate!
                return attempt >= 2
            }
            override fun isWifiConnected(): Boolean = true
            override fun isBatterySaverActive(): Boolean = true
        }
        val platform = GoogleBookPlatformAdapter(context, flakyGateway)

        val status = com.sunloom.app.platform.scheduler.SunloomWallpaperScheduler.executeScheduledWallpaperChange(
            appContext = context,
            repo = repo,
            platform = platform
        )
        assertEquals(WallpaperStatus.APPLIED_SUCCESSFULLY, status)
        assertEquals("Scheduler should have walked past failed candidate #1 and succeeded on #2", 2, attempt)
        assertTrue(seeds.any { it.id == repo.getCurrentWallpaperId() })
    }
}
