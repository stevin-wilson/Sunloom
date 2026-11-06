package com.sunloom.app.platform

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.sunloom.app.data.repository.LocalLibraryRepository
import com.sunloom.app.domain.model.CropRect
import com.sunloom.app.domain.model.DisplayPolicy
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.LockWorkspacePolicy
import com.sunloom.app.domain.model.PlatformCapabilities
import com.sunloom.app.domain.model.RememberedDisplayRecord
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.model.WallpaperStatus
import com.sunloom.app.domain.renderer.CropRenderer
import com.sunloom.app.domain.renderer.EnvelopePlan
import com.sunloom.app.domain.renderer.EnvelopePlanner
import com.sunloom.app.domain.solar.SolarCalculator
import com.sunloom.app.platform.display.DisplayHistoryStore
import com.sunloom.app.platform.display.DisplayProbe
import com.sunloom.app.platform.scheduler.SunloomWallpaperScheduler
import java.io.File
import java.time.ZonedDateTime
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Factory abstraction around Android's WallpaperManager and DisplayManager
 * so all platform operations are 100% testable in JVM / Robolectric tests while driving the real
 * GoogleBookOS services in production.
 */
interface SystemWallpaperGateway {
    fun isWallpaperSupported(): Boolean
    fun isSetWallpaperAllowed(): Boolean
    fun getConnectedDisplays(): List<DisplayProfile>
    fun getRememberedDisplays(connectedDisplays: List<DisplayProfile> = getConnectedDisplays()): List<RememberedDisplayRecord> = emptyList()
    fun forgetRememberedDisplay(key: String) {}
    fun applyBitmapToDestination(bitmap: Bitmap, flags: Int): Boolean
    fun getWallpaperId(which: Int): Int = 1
    fun getObservedIndependentLockSupport(): Boolean? = null
    fun clearWallpaper(): Boolean = true
    fun isWifiConnected(): Boolean
    fun isBatterySaverActive(): Boolean
}

class DefaultSystemWallpaperGateway(private val context: Context) : SystemWallpaperGateway {
    companion object {
        internal const val PREF_OBSERVED_LOCK_SUPPORT = "observed_independent_lock_support"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("sunloom_preferences", Context.MODE_PRIVATE)
    private val wallpaperManager: WallpaperManager = WallpaperManager.getInstance(context)
    private val historyStore = DisplayHistoryStore(appContext)
    private val displayProbe = DisplayProbe(context, historyStore)

    override fun isWallpaperSupported(): Boolean {
        return runCatching { wallpaperManager.isWallpaperSupported }.getOrDefault(true)
    }

    override fun isSetWallpaperAllowed(): Boolean {
        return runCatching { wallpaperManager.isSetWallpaperAllowed }.getOrDefault(true)
    }

    override fun getObservedIndependentLockSupport(): Boolean? {
        return if (prefs.contains(PREF_OBSERVED_LOCK_SUPPORT)) {
            prefs.getBoolean(PREF_OBSERVED_LOCK_SUPPORT, false)
        } else {
            null
        }
    }

    /**
     * Article IV §1–2 (Constitution v0.6): Queries real hardware mode & calibrated EDID PPI
     * via [DisplayProbe] — never invents fallback resolutions.
     */
    override fun getConnectedDisplays(): List<DisplayProfile> {
        return displayProbe.probeConnectedDisplays(recordHistory = true)
    }

    override fun getRememberedDisplays(connectedDisplays: List<DisplayProfile>): List<RememberedDisplayRecord> {
        return historyStore.getRememberedDisplays(connectedDisplays)
    }

    override fun forgetRememberedDisplay(key: String) {
        historyStore.forgetDisplay(key)
    }

    override fun applyBitmapToDestination(bitmap: Bitmap, flags: Int): Boolean {
        return runCatching {
            val wantsSystem = (flags and WallpaperManager.FLAG_SYSTEM) != 0
            val wantsLock = (flags and WallpaperManager.FLAG_LOCK) != 0
            val beforeSysId = if (wantsSystem) getWallpaperId(WallpaperManager.FLAG_SYSTEM) else -1
            val beforeLockId = if (wantsLock) getWallpaperId(WallpaperManager.FLAG_LOCK) else -1

            // Disable launcher parallax scrolling so system center-crop preserves our multi-display envelope
            runCatching { wallpaperManager.setWallpaperOffsetSteps(0f, 0f) }
            val result = wallpaperManager.setBitmap(bitmap, null, true, flags)

            val afterSysId = if (wantsSystem) getWallpaperId(WallpaperManager.FLAG_SYSTEM) else -1
            val afterLockId = if (wantsLock) getWallpaperId(WallpaperManager.FLAG_LOCK) else -1

            val sysChanged = wantsSystem && afterSysId > 0 && afterSysId != beforeSysId
            val lockChanged = wantsLock && afterLockId > 0 && afterLockId != beforeLockId

            if (wantsLock) {
                val lockVerified = afterLockId >= 0 && (lockChanged || result > 0)
                prefs.edit { putBoolean(PREF_OBSERVED_LOCK_SUPPORT, lockVerified) }
            }

            when {
                wantsSystem && !wantsLock -> result > 0 || sysChanged
                wantsLock && !wantsSystem -> (result > 0 && afterLockId >= 0) || lockChanged
                wantsSystem && wantsLock -> result > 0 || sysChanged
                else -> result > 0
            }
        }.getOrDefault(false)
    }

    override fun getWallpaperId(which: Int): Int {
        return runCatching { wallpaperManager.getWallpaperId(which) }.getOrDefault(-1)
    }

    override fun clearWallpaper(): Boolean {
        return runCatching {
            wallpaperManager.clear(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
            true
        }.getOrDefault(false)
    }

    override fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    override fun isBatterySaverActive(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isPowerSaveMode
    }
}

/**
 * Articles IV, V, IX, XI & XVIII:
 * GoogleBookOS Platform Adapter handling capability probing, focal-point-guided crop rendering,
 * blurred frame fill rendering, atomic wallpaper application, and system default restore.
 */
class GoogleBookPlatformAdapter(
    private val context: Context,
    private val gateway: SystemWallpaperGateway = DefaultSystemWallpaperGateway(context),
    private val cropRenderer: CropRenderer = CropRenderer(),
    private val solarCalculator: SolarCalculator = SolarCalculator(),
    private val envelopePlanner: EnvelopePlanner = EnvelopePlanner(cropRenderer)
) {
    private val scheduler = SunloomWallpaperScheduler(context, solarCalculator)

    fun getConnectedDisplays(): List<DisplayProfile> = gateway.getConnectedDisplays()

    fun forgetRememberedDisplay(key: String) {
        gateway.forgetRememberedDisplay(key)
    }

    fun probeCapabilities(displayPolicy: DisplayPolicy = DisplayPolicy.SAME_ON_ALL_DISPLAYS): PlatformCapabilities {
        val displays = gateway.getConnectedDisplays()
        val remembered = gateway.getRememberedDisplays(displays)
        val supported = gateway.isWallpaperSupported()
        val allowed = gateway.isSetWallpaperAllowed()
        val observedLock = gateway.getObservedIndependentLockSupport()
        val supportsLock = supported && allowed &&
            (observedLock ?: (gateway.getWallpaperId(WallpaperManager.FLAG_LOCK) >= 0))

        var excludedNote = ""
        val disclosureNote = when {
            displays.isEmpty() -> {
                "Display dimensions unknown — waiting for an active display before applying wallpapers."
            }
            displays.size > 1 -> {
                val sharpest = getPrimaryOrSharpestDisplayOrNull(displays)
                if (displayPolicy == DisplayPolicy.OPTIMIZE_PRIMARY_SHARPEST) {
                    "Detected ${displays.size} displays (${displays.joinToString { "${it.name} ${it.resolutionLabel}" }}). " +
                        "Android shares one WallpaperManager surface; optimizing crop for ${sharpest?.name ?: "sharpest display"} " +
                        "(${sharpest?.resolutionLabel.orEmpty()}) without stretching."
                } else {
                    val syntheticCheck = envelopePlanner.planEnvelope(
                        sourceWidth = 10_000,
                        sourceHeight = 10_000,
                        focalX = 0.5,
                        focalY = 0.5,
                        connectedDisplays = displays,
                        policy = DisplayPolicy.SAME_ON_ALL_DISPLAYS,
                        allowBlurredFrameFill = false
                    )
                    if (syntheticCheck != null && syntheticCheck.excludedDisplays.isNotEmpty()) {
                        excludedNote = "Excluded from envelope target: " +
                            syntheticCheck.excludedDisplays.joinToString { "${it.name} (${it.resolutionLabel})" } +
                            " — incompatible orientation or exceeds 16 MP system wallpaper cap."
                    }
                    "Detected ${displays.size} displays (${displays.joinToString { "${it.name} ${it.resolutionLabel}" }}). " +
                        "Android shares one WallpaperManager surface; Sunloom renders a shared native-resolution envelope " +
                        "so each compatible monitor's system center-crop stays crisp and subject-aligned without stretching."
                }
            }
            else -> {
                val d = displays.first()
                val ppiNote = d.physicalPpi?.let { " • ${it.roundToInt()} PPI" }.orEmpty()
                val lockClause = when (observedLock) {
                    true -> "Independent Workspace and Lock Screen wallpapers verified."
                    false -> "System shares one wallpaper across Workspace and Lock Screen on this device."
                    null -> if (supportsLock) {
                        "Lock Screen destination capability will be verified on first apply."
                    } else {
                        "System shares one wallpaper across Workspace and Lock Screen."
                    }
                }
                "Detected ${d.name} (${d.resolutionLabel}$ppiNote, ${d.qualityTier.shortLabel}). $lockClause"
            }
        }

        return PlatformCapabilities(
            isWallpaperSupported = supported,
            isSetWallpaperAllowed = allowed,
            supportsIndependentLockWallpaper = supportsLock,
            supportsPerDisplayWallpaper = false,
            connectedDisplays = displays,
            capabilityDisclosureNote = disclosureNote,
            rememberedDisplays = remembered,
            excludedDisplaysNote = excludedNote
        )
    }

    fun getPrimaryOrSharpestDisplayOrNull(
        displays: List<DisplayProfile> = gateway.getConnectedDisplays()
    ): DisplayProfile? {
        return displays.maxByOrNull { it.widthPx.toLong() * it.heightPx.toLong() }
    }

    fun computeDisplayGeometryKey(
        displays: List<DisplayProfile> = gateway.getConnectedDisplays(),
        displayPolicy: DisplayPolicy = DisplayPolicy.SAME_ON_ALL_DISPLAYS
    ): String {
        if (displays.isEmpty()) return "unknown"
        val perDisplay = displays
            .sortedBy { it.displayId }
            .joinToString("+") { "${it.displayId}:${it.geometryKey}" }
        return "$perDisplay|policy=${displayPolicy.name}"
    }

    /**
     * Restores the OS default wallpaper via `WallpaperManager.clear(FLAG_SYSTEM or FLAG_LOCK)`.
     */
    fun restoreSystemDefaultWallpaper(dryRunPreviewOnly: Boolean = false): Boolean {
        if (!gateway.isWallpaperSupported() || !gateway.isSetWallpaperAllowed()) {
            return false
        }
        return if (dryRunPreviewOnly) true else gateway.clearWallpaper()
    }

    /**
     * Article IX §3 & IV §1–2 (Constitution v0.6): Atomic Multi-Display Envelope Application.
     * Loads the validated local image, computes the [EnvelopePlan] across [connectedDisplays]
     * (or [targetDisplay] when single-display), renders without stretching, and applies to the
     * configured destinations. Refuses to apply when display dimensions are unknown.
     */
    fun applyWallpaperAtomically(
        record: WallpaperRecord,
        policy: LockWorkspacePolicy,
        targetDisplay: DisplayProfile? = getPrimaryOrSharpestDisplayOrNull(),
        dryRunPreviewOnly: Boolean = false,
        connectedDisplays: List<DisplayProfile>? = null,
        displayPolicy: DisplayPolicy = DisplayPolicy.SAME_ON_ALL_DISPLAYS
    ): WallpaperStatus {
        if (!gateway.isWallpaperSupported() || !gateway.isSetWallpaperAllowed()) {
            return WallpaperStatus.UNSUPPORTED_BY_SYSTEM
        }
        val validConnected = connectedDisplays?.filter { it.widthPx > 0 && it.heightPx > 0 }
        val effectiveDisplays = when {
            !validConnected.isNullOrEmpty() -> validConnected
            targetDisplay != null && targetDisplay.widthPx > 0 && targetDisplay.heightPx > 0 -> listOf(targetDisplay)
            else -> return WallpaperStatus.FAILED_TEMPORARILY
        }
        val effectiveDisplay = effectiveDisplays.maxByOrNull { it.widthPx.toLong() * it.heightPx.toLong() }
            ?: return WallpaperStatus.FAILED_TEMPORARILY

        if (record.isRejected || (!record.isBaselineRestore && !record.evaluation.isEligible)) {
            return WallpaperStatus.FAILED_TEMPORARILY
        }

        val allowBlurred = record.provenance.sourceId == "user_local" ||
            record.evaluation.cropRect?.usesBlurredFrameFill == true

        val envelopePlan: EnvelopePlan? = if (
            !record.isBaselineRestore &&
            record.provenance.sourceId != "seed_reserve" &&
            record.provenance.originalWidth > 0 &&
            record.provenance.originalHeight > 0 &&
            !(record.provenance.sourceId == "user_local" && record.evaluation.cropRect?.usesBlurredFrameFill == true)
        ) {
            envelopePlanner.planEnvelope(
                sourceWidth = record.provenance.originalWidth,
                sourceHeight = record.provenance.originalHeight,
                focalX = record.evaluation.cropRect?.focalX ?: 0.5,
                focalY = record.evaluation.cropRect?.focalY ?: 0.5,
                connectedDisplays = effectiveDisplays,
                policy = displayPolicy,
                allowBlurredFrameFill = allowBlurred
            ) ?: if (record.provenance.sourceId == "user_local") {
                null
            } else {
                return WallpaperStatus.FAILED_TEMPORARILY
            }
        } else {
            null
        }

        val file = File(record.localFilePath)
        if (!file.exists() || file.length() == 0L) {
            return WallpaperStatus.FAILED_TEMPORARILY
        }

        val maxDecodeW = effectiveDisplays.maxOf { it.widthPx }
        val maxDecodeH = effectiveDisplays.maxOf { it.heightPx }
        val sourceBitmap = decodeBitmapBounded(file, maxDecodeW, maxDecodeH)
            ?: return WallpaperStatus.FAILED_TEMPORARILY

        val preparedBitmap = if (envelopePlan != null) {
            prepareEnvelopeBitmap(source = sourceBitmap, plan = envelopePlan, fallbackDisplay = effectiveDisplay)
        } else {
            prepareNonStretchedBitmap(
                source = sourceBitmap,
                display = effectiveDisplay,
                cropRect = record.evaluation.cropRect
            )
        }

        val flags = when (policy) {
            LockWorkspacePolicy.SAME_ON_BOTH -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            LockWorkspacePolicy.WORKSPACE_ONLY -> WallpaperManager.FLAG_SYSTEM
            LockWorkspacePolicy.LOCK_ONLY -> WallpaperManager.FLAG_LOCK
        }

        val applied = if (dryRunPreviewOnly) {
            true
        } else {
            gateway.applyBitmapToDestination(preparedBitmap, flags)
        }
        if (preparedBitmap != sourceBitmap) {
            preparedBitmap.recycle()
        }
        sourceBitmap.recycle()

        return if (applied) WallpaperStatus.APPLIED_SUCCESSFULLY else WallpaperStatus.FAILED_TEMPORARILY
    }

    @SuppressLint("ExifInterface")
    private fun decodeBitmapBounded(file: File, targetWidthPx: Int, targetHeightPx: Int): Bitmap? {
        return try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
            val decodeOpts = BitmapFactory.Options()
            if (boundsOpts.outWidth > 0 && boundsOpts.outHeight > 0) {
                val maxTargetW = (targetWidthPx * 2).coerceAtLeast(2560)
                val maxTargetH = (targetHeightPx * 2).coerceAtLeast(1600)
                var sampleSize = 1
                while ((boundsOpts.outWidth / sampleSize) > maxTargetW ||
                    (boundsOpts.outHeight / sampleSize) > maxTargetH
                ) {
                    sampleSize *= 2
                }
                decodeOpts.inSampleSize = sampleSize
            }
            val raw = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null
            applyExifOrientationIfNeeded(file, raw)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    @SuppressLint("ExifInterface")
    private fun applyExifOrientationIfNeeded(file: File, bitmap: Bitmap): Bitmap {
        return runCatching {
            val exif = android.media.ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL
            )
            val matrix = Matrix()
            when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                android.media.ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                android.media.ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.postScale(-1f, 1f)
                }
                else -> return bitmap
            }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            rotated
        }.getOrDefault(bitmap)
    }

    /**
     * Renders the multi-display [EnvelopePlan] bitmap from [source] without stretching.
     */
    fun prepareEnvelopeBitmap(
        source: Bitmap,
        plan: EnvelopePlan,
        fallbackDisplay: DisplayProfile
    ): Bitmap {
        if (plan.envelopeCrop.usesBlurredFrameFill) {
            return renderBlurredFrameFillBitmap(source, fallbackDisplay)
        }
        val srcW = source.width.coerceAtLeast(1)
        val srcH = source.height.coerceAtLeast(1)
        val targetAspect = plan.envelopeCrop.width.coerceAtLeast(1).toDouble() /
            plan.envelopeCrop.height.coerceAtLeast(1).toDouble()
        val bounds = cropRenderer.computeAspectCropBounds(
            sourceWidth = srcW,
            sourceHeight = srcH,
            focalX = plan.envelopeCrop.focalX,
            focalY = plan.envelopeCrop.focalY,
            targetAspect = targetAspect
        )
        val cropped = Bitmap.createBitmap(source, bounds.left, bounds.top, bounds.width, bounds.height)
        val (budgetW, budgetH) = CropRenderer.budgetedSize(
            plan.outputWidthPx.coerceAtLeast(1),
            plan.outputHeightPx.coerceAtLeast(1)
        )
        if (bounds.width > budgetW && bounds.height > budgetH) {
            val scaled = cropped.scale(budgetW, budgetH)
            if (scaled != cropped && cropped != source) {
                cropped.recycle()
            }
            return scaled
        }
        return cropped
    }

    /**
     * Article IV §4:
     * Prepares a target bitmap matching [display]'s aspect ratio without any non-uniform scaling,
     * honoring the persisted [cropRect] focal point (or rendering a darkened blurred frame fill
     * when [CropRect.usesBlurredFrameFill] is `true`).
     */
    fun prepareNonStretchedBitmap(
        source: Bitmap,
        display: DisplayProfile,
        cropRect: CropRect? = null
    ): Bitmap {
        val srcW = source.width.coerceAtLeast(1)
        val srcH = source.height.coerceAtLeast(1)

        if (cropRect != null && cropRect.usesBlurredFrameFill) {
            return renderBlurredFrameFillBitmap(source, display)
        }

        val bounds = cropRenderer.computeAspectCropBounds(
            sourceWidth = srcW,
            sourceHeight = srcH,
            focalX = cropRect?.focalX ?: 0.5,
            focalY = cropRect?.focalY ?: 0.5,
            targetAspect = display.aspectRatio
        )

        val cropped = Bitmap.createBitmap(source, bounds.left, bounds.top, bounds.width, bounds.height)
        val (budgetW, budgetH) = CropRenderer.budgetedSize(
            display.widthPx.coerceAtLeast(1),
            display.heightPx.coerceAtLeast(1)
        )
        // Downscale uniformly if the cropped region exceeds the budgeted target display dimensions
        if (bounds.width > budgetW && bounds.height > budgetH) {
            val scaled = cropped.scale(budgetW, budgetH)
            if (scaled != cropped && cropped != source) {
                cropped.recycle()
            }
            return scaled
        }
        return cropped
    }

    /**
     * Renders a blurred, darkened ambient frame extension behind the unstretched foreground image
     * when `usesBlurredFrameFill == true` (Article IV §4).
     */
    fun renderBlurredFrameFillBitmap(source: Bitmap, display: DisplayProfile): Bitmap {
        val (outW, outH) = CropRenderer.budgetedSize(
            display.widthPx.coerceAtLeast(64),
            display.heightPx.coerceAtLeast(64)
        )
        val canvasBmp = createBitmap(outW, outH)
        val canvas = Canvas(canvasBmp)

        // 1. Create low-res blurred + darkened background fill matching display aspect ratio
        val (rawBlurW, rawBlurH) = CropRenderer.budgetedSize(outW, outH, maxPixels = 2304)
        val blurW = rawBlurW.coerceAtLeast(16)
        val blurH = rawBlurH.coerceAtLeast(16)
        val tiny = source.scale(blurW, blurH)
        boxBlurAndDarkenInPlace(tiny, radius = 2, darkenFactor = 0.55f)
        val bgRect = Rect(0, 0, outW, outH)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(tiny, null, bgRect, paint)
        if (tiny != source) tiny.recycle()

        // 2. Draw unstretched foreground centered proportionally inside outW x outH
        val scale = min(outW.toDouble() / source.width.coerceAtLeast(1), outH.toDouble() / source.height.coerceAtLeast(1))
        val fgW = (source.width * scale).roundToInt().coerceIn(1, outW)
        val fgH = (source.height * scale).roundToInt().coerceIn(1, outH)
        val fgLeft = (outW - fgW) / 2
        val fgTop = (outH - fgH) / 2
        val fgRect = Rect(fgLeft, fgTop, fgLeft + fgW, fgTop + fgH)
        canvas.drawBitmap(source, null, fgRect, paint)

        return canvasBmp
    }

    private fun boxBlurAndDarkenInPlace(bmp: Bitmap, radius: Int, darkenFactor: Float) {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val temp = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var rSum = 0
                var gSum = 0
                var bSum = 0
                var count = 0
                for (dy in -radius..radius) {
                    val ny = (y + dy).coerceIn(0, h - 1)
                    for (dx in -radius..radius) {
                        val nx = (x + dx).coerceIn(0, w - 1)
                        val c = pixels[ny * w + nx]
                        rSum += (c shr 16) and 0xFF
                        gSum += (c shr 8) and 0xFF
                        bSum += c and 0xFF
                        count++
                    }
                }
                val r = ((rSum / count) * darkenFactor).roundToInt().coerceIn(0, 255)
                val g = ((gSum / count) * darkenFactor).roundToInt().coerceIn(0, 255)
                val b = ((bSum / count) * darkenFactor).roundToInt().coerceIn(0, 255)
                temp[y * w + x] = Color.rgb(r, g, b)
            }
        }
        bmp.setPixels(temp, 0, w, 0, 0, w, h)
    }

    fun cancelAllScheduledWork() {
        scheduler.cancelAllScheduledWork()
    }

    fun syncPeriodicSchedule(
        autoEnabled: Boolean,
        isPaused: Boolean,
        wifiOnly: Boolean,
        deferBatterySaver: Boolean,
        preferences: UserPreferences? = null,
        nowZoned: ZonedDateTime? = null,
        rescheduleRefreshWork: Boolean = true
    ) {
        scheduler.syncPeriodicSchedule(
            autoEnabled = autoEnabled,
            isPaused = isPaused,
            wifiOnly = wifiOnly,
            deferBatterySaver = deferBatterySaver,
            preferences = preferences,
            nowZoned = nowZoned,
            rescheduleRefreshWork = rescheduleRefreshWork
        )
    }

    companion object {
        const val WORK_NAME = SunloomWallpaperScheduler.WORK_NAME
        const val WORK_NAME_CHANGE = SunloomWallpaperScheduler.WORK_NAME_CHANGE
        const val WORK_NAME_REFRESH = SunloomWallpaperScheduler.WORK_NAME_REFRESH

        suspend fun executeScheduledWallpaperChange(
            appContext: Context,
            repo: LocalLibraryRepository = LocalLibraryRepository(appContext),
            platform: GoogleBookPlatformAdapter = GoogleBookPlatformAdapter(appContext),
            nowZonedOverride: ZonedDateTime? = null
        ): WallpaperStatus? {
            return SunloomWallpaperScheduler.executeScheduledWallpaperChange(
                appContext = appContext,
                repo = repo,
                platform = platform,
                nowZonedOverride = nowZonedOverride
            )
        }

        suspend fun executeScheduledCatalogRefresh(
            appContext: Context,
            repo: LocalLibraryRepository = LocalLibraryRepository(appContext),
            platform: GoogleBookPlatformAdapter = GoogleBookPlatformAdapter(appContext),
            gateway: SystemWallpaperGateway = DefaultSystemWallpaperGateway(appContext)
        ): Int {
            return SunloomWallpaperScheduler.executeScheduledCatalogRefresh(
                appContext = appContext,
                repo = repo,
                platform = platform,
                gateway = gateway
            )
        }
    }
}
