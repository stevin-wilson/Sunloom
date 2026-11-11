package com.sunloom.app.platform

import com.sunloom.app.labels.title
import com.sunloom.app.domain.model.SourceIds
import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
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
import com.sunloom.app.appContainer
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
import com.sunloom.app.domain.model.displaySetGeometryKey
import com.sunloom.app.domain.renderer.CropRenderer
import com.sunloom.app.domain.renderer.DecodeSampling
import com.sunloom.app.domain.renderer.DecodeTarget
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

    /**
     * True when the most recent [applyBitmapToDestination] failed because the host environment refused
     * the change (for example a `SecurityException` on a managed or restricted desktop), as opposed to a
     * transient failure worth retrying. Lets the UI be honest per Article V §2.
     */
    fun lastApplyRefusedByHost(): Boolean = false
    fun getWallpaperId(which: Int): Int = 1
    fun getObservedIndependentLockSupport(): Boolean? = null
    fun clearWallpaper(): Boolean = true
    /**
     * True when the active network is one the user does not pay for by the byte. This is the network's own flag
     * (`NET_CAPABILITY_NOT_METERED`), not the radio in use: a phone hotspot is Wi-Fi but metered.
     */
    fun isUnmeteredNetwork(): Boolean

    /** True when Android's Data Saver is on and would hold back background data on a metered network. */
    fun isDataSaverRestricting(): Boolean = false
    fun isBatterySaverActive(): Boolean
}

class DefaultSystemWallpaperGateway(private val context: Context) : SystemWallpaperGateway {
    companion object {
        /**
         * Data Saver holds background data back only when it is on and the app is not exempt. "Whitelisted" means it is on
         * but the person allowed this app to use data in the background, which is exactly what they did to make it work.
         */
        internal fun dataSaverRestricts(status: Int): Boolean = status == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED

        internal const val PREF_OBSERVED_LOCK_SUPPORT = "observed_independent_lock_support"
        private const val TAG = "SunloomWallpaper"
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

    @Volatile
    private var hostRefusedLastApply = false

    override fun lastApplyRefusedByHost(): Boolean = hostRefusedLastApply

    override fun applyBitmapToDestination(bitmap: Bitmap, flags: Int): Boolean {
        hostRefusedLastApply = false
        return try {
            applyBitmapUnchecked(bitmap, flags)
        } catch (e: SecurityException) {
            hostRefusedLastApply = true
            Log.w(TAG, "Host refused wallpaper change", e)
            false
        } catch (e: Throwable) {
            // Includes OutOfMemoryError from large multi-display envelopes: a failed apply must never crash the app or worker.
            Log.w(TAG, "Wallpaper change failed", e)
            false
        }
    }

    private fun applyBitmapUnchecked(bitmap: Bitmap, flags: Int): Boolean {
        return run {
            val wantsSystem = (flags and WallpaperManager.FLAG_SYSTEM) != 0
            val wantsLock = (flags and WallpaperManager.FLAG_LOCK) != 0
            val beforeSysId = if (wantsSystem) getWallpaperId(WallpaperManager.FLAG_SYSTEM) else -1
            val beforeLockId = if (wantsLock) getWallpaperId(WallpaperManager.FLAG_LOCK) else -1

            // Only the offsets this process reports are zeroed; a launcher's own parallax setting is not ours to change.
            runCatching { wallpaperManager.setWallpaperOffsetSteps(0f, 0f) }
            val result = wallpaperManager.setBitmap(bitmap, null, true, flags)

            val afterSysId = if (wantsSystem) getWallpaperId(WallpaperManager.FLAG_SYSTEM) else -1
            val afterLockId = if (wantsLock) getWallpaperId(WallpaperManager.FLAG_LOCK) else -1

            val sysChanged = wantsSystem && afterSysId > 0 && afterSysId != beforeSysId
            val lockChanged = wantsLock && afterLockId > 0 && afterLockId != beforeLockId

            LockSupportEvidence.observe(wantsSystem, wantsLock, afterLockId, lockChanged, result)?.let { independent ->
                prefs.edit { putBoolean(PREF_OBSERVED_LOCK_SUPPORT, independent) }
            }

            when {
                wantsSystem && !wantsLock -> result > 0 || sysChanged
                wantsLock && !wantsSystem -> (result > 0 && afterLockId >= 0) || lockChanged
                wantsSystem && wantsLock -> result > 0 || sysChanged
                else -> result > 0
            }
        }
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

    override fun isUnmeteredNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    override fun isDataSaverRestricting(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return dataSaverRestricts(cm.restrictBackgroundStatus)
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

    fun isUnmeteredNetwork(): Boolean = gateway.isUnmeteredNetwork()

    fun isDataSaverRestricting(): Boolean = gateway.isDataSaverRestricting()

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
                        "Android uses one wallpaper for every screen, so Sunloom crops it for ${sharpest?.name ?: "sharpest display"} " +
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
                        excludedNote = "Left out of the shared wallpaper: " +
                            syntheticCheck.excludedDisplays.joinToString("; ") {
                                val reason = syntheticCheck.exclusionReasons[it.displayId]?.let { r -> " — $r" }.orEmpty()
                                "${it.name} (${it.resolutionLabel})$reason"
                            } + "."
                    }
                    "Detected ${displays.size} displays (${displays.joinToString { "${it.name} ${it.resolutionLabel}" }}). " +
                        "Android uses one wallpaper for every screen, so Sunloom makes one picture " +
                        "that stays sharp and well framed on each compatible monitor, without stretching."
                }
            }
            else -> {
                val d = displays.first()
                val ppiNote = d.physicalPpi?.let { " • ${it.roundToInt()} pixels per inch" }.orEmpty()
                val lockClause = when (observedLock) {
                    true -> "Independent Workspace and Lock Screen wallpapers verified."
                    false -> "System shares one wallpaper across Workspace and Lock Screen on this device."
                    null -> if (supportsLock) {
                        "Lock Screen destination capability will be verified on first apply."
                    } else {
                        "System shares one wallpaper across Workspace and Lock Screen."
                    }
                }
                "Detected ${d.name} (${d.resolutionLabel}$ppiNote, ${d.qualityTier.title(context.resources)}). $lockClause"
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
        return "${displaySetGeometryKey(displays)}|policy=${displayPolicy.name}"
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
        displayPolicy: DisplayPolicy = DisplayPolicy.SAME_ON_ALL_DISPLAYS,
        onApplied: () -> Unit = {}
    ): WallpaperStatus = ApplyLock.run {
        // [onApplied] runs inside the lock, so the app and the background worker cannot interleave an apply with the
        // record of it and leave the wallpaper on screen different from the one recorded as active (Article V §2).
        applyWallpaperLocked(record, policy, targetDisplay, dryRunPreviewOnly, connectedDisplays, displayPolicy)
            .also { if (it == WallpaperStatus.APPLIED_SUCCESSFULLY) onApplied() }
    }

    private fun applyWallpaperLocked(
        record: WallpaperRecord,
        policy: LockWorkspacePolicy,
        targetDisplay: DisplayProfile?,
        dryRunPreviewOnly: Boolean,
        connectedDisplays: List<DisplayProfile>?,
        displayPolicy: DisplayPolicy
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

        val allowBlurred = record.provenance.sourceId == SourceIds.USER_LOCAL ||
            record.evaluation.cropRect?.usesBlurredFrameFill == true

        val envelopePlan: EnvelopePlan? = if (canPlanEnvelope(record)) {
            envelopePlanner.planEnvelope(
                sourceWidth = record.provenance.originalWidth,
                sourceHeight = record.provenance.originalHeight,
                focalX = record.evaluation.cropRect?.focalX ?: 0.5,
                focalY = record.evaluation.cropRect?.focalY ?: 0.5,
                connectedDisplays = effectiveDisplays,
                policy = displayPolicy,
                allowBlurredFrameFill = allowBlurred
            ) ?: if (record.provenance.sourceId == SourceIds.USER_LOCAL) {
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
        val sourceBitmap = decodeBitmapBounded(
            file, maxDecodeW, maxDecodeH, decodeTargetFor(record, envelopePlan, effectiveDisplay, effectiveDisplays)
        ) ?: return WallpaperStatus.FAILED_TEMPORARILY

        var preparedBitmap: Bitmap? = null
        val applied = try {
            preparedBitmap = when {
                // A user's picture stored as blurred fill stays blurred whatever a re-plan would now choose: it was
                // kept whole on purpose. The planner still tells us which displays the fill must be laid out for.
                keepsStoredBlurredFill(record) -> renderBlurredFrameFillBitmap(
                    sourceBitmap,
                    effectiveDisplay,
                    envelopePlan?.satisfiedDisplays?.ifEmpty { null } ?: effectiveDisplays
                )
                envelopePlan != null ->
                    prepareEnvelopeBitmap(source = sourceBitmap, plan = envelopePlan, fallbackDisplay = effectiveDisplay)
                // A bundled seed is rendered for the whole display set (as wide as the widest and as tall as the
                // tallest), so it is applied as stored: cropping it to the largest display's aspect would cut the
                // height other displays need and soften them.
                isSharedSeed(record, effectiveDisplays) -> sourceBitmap
                else -> prepareNonStretchedBitmap(
                    source = sourceBitmap,
                    display = effectiveDisplay,
                    cropRect = record.evaluation.cropRect,
                    displays = effectiveDisplays
                )
            }

            // The prepared bitmap is all that setBitmap needs, and handing it over allocates a lot (it is encoded):
            // release the decoded source first so the two are never held together at that peak.
            if (preparedBitmap !== sourceBitmap) sourceBitmap.recycle()

            val flags = when (policy) {
                LockWorkspacePolicy.SAME_ON_BOTH -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                LockWorkspacePolicy.WORKSPACE_ONLY -> WallpaperManager.FLAG_SYSTEM
                LockWorkspacePolicy.LOCK_ONLY -> WallpaperManager.FLAG_LOCK
            }

            if (dryRunPreviewOnly) true else gateway.applyBitmapToDestination(preparedBitmap, flags)
        } finally {
            // Always release native pixel memory, even if cropping or setBitmap throws.
            if (preparedBitmap != null && preparedBitmap !== sourceBitmap) preparedBitmap.recycle()
            sourceBitmap.recycle()
        }

        return when {
            applied -> WallpaperStatus.APPLIED_SUCCESSFULLY
            // The capability probe fails open; if the host then refuses the change, say so plainly
            // instead of reporting an endlessly "temporary" failure (Article V §2).
            gateway.lastApplyRefusedByHost() -> WallpaperStatus.UNSUPPORTED_BY_SYSTEM
            else -> WallpaperStatus.FAILED_TEMPORARILY
        }
    }

    /** Fetched and imported pictures are planned per display set; seeds and the baseline copy are not. */
    private fun canPlanEnvelope(record: WallpaperRecord): Boolean {
        val rendered = record.isBaselineRestore || record.provenance.sourceId == SourceIds.SEED_RESERVE
        return !rendered && record.provenance.originalWidth > 0 && record.provenance.originalHeight > 0
    }

    private fun keepsStoredBlurredFill(record: WallpaperRecord): Boolean =
        record.provenance.sourceId == SourceIds.USER_LOCAL && record.evaluation.cropRect?.usesBlurredFrameFill == true

    private fun isSharedSeed(record: WallpaperRecord, displays: List<DisplayProfile>): Boolean =
        record.provenance.sourceId == SourceIds.SEED_RESERVE && displays.size > 1

    @SuppressLint("ExifInterface")
    /**
     * What the picture will be cropped and scaled to, so the decoder can shrink it by the right amount; null where the
     * picture is used whole (blurred fill, a shared bundled seed) and the older rule applies.
     */
    private fun decodeTargetFor(
        record: WallpaperRecord,
        plan: EnvelopePlan?,
        display: DisplayProfile,
        displays: List<DisplayProfile>
    ): DecodeTarget? = when {
        keepsStoredBlurredFill(record) || isSharedSeed(record, displays) -> null
        plan != null -> if (plan.envelopeCrop.usesBlurredFrameFill) {
            null
        } else {
            val (w, h) = CropRenderer.budgetedSize(plan.outputWidthPx, plan.outputHeightPx)
            DecodeTarget(
                aspect = plan.envelopeCrop.width.coerceAtLeast(1).toDouble() / plan.envelopeCrop.height.coerceAtLeast(1),
                focalX = plan.envelopeCrop.focalX, focalY = plan.envelopeCrop.focalY, outputWidth = w, outputHeight = h
            )
        }
        record.evaluation.cropRect?.usesBlurredFrameFill == true -> null
        else -> {
            val (w, h) = CropRenderer.budgetedSize(display.widthPx, display.heightPx)
            DecodeTarget(
                aspect = display.aspectRatio,
                focalX = record.evaluation.cropRect?.focalX ?: 0.5,
                focalY = record.evaluation.cropRect?.focalY ?: 0.5,
                outputWidth = w, outputHeight = h
            )
        }
    }

    private fun decodeBitmapBounded(file: File, targetWidthPx: Int, targetHeightPx: Int, target: DecodeTarget?): Bitmap? {
        return try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
            val decodeOpts = BitmapFactory.Options()
            if (boundsOpts.outWidth > 0 && boundsOpts.outHeight > 0) {
                // The crop is planned on the picture as it will be shown, so a picture stored on its side swaps axes.
                val sideways = exifOrientation(file) in SIDEWAYS_ORIENTATIONS
                decodeOpts.inSampleSize = DecodeSampling.sampleSize(
                    sourceWidth = if (sideways) boundsOpts.outHeight else boundsOpts.outWidth,
                    sourceHeight = if (sideways) boundsOpts.outWidth else boundsOpts.outHeight,
                    target = target,
                    cropRenderer = cropRenderer,
                    legacyMaxW = targetWidthPx,
                    legacyMaxH = targetHeightPx
                )
            }
            val raw = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null
            applyExifOrientationIfNeeded(file, raw)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    @SuppressLint("ExifInterface")
    private fun exifOrientation(file: File): Int = runCatching {
        android.media.ExifInterface(file.absolutePath)
            .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)

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
            return renderBlurredFrameFillBitmap(source, fallbackDisplay, plan.satisfiedDisplays)
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
        val (budgetW, budgetH) = CropRenderer.budgetedSize(
            plan.outputWidthPx.coerceAtLeast(1),
            plan.outputHeightPx.coerceAtLeast(1)
        )
        return cropAndScale(source, bounds, budgetW, budgetH)
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
        cropRect: CropRect? = null,
        displays: List<DisplayProfile> = listOf(display)
    ): Bitmap {
        val srcW = source.width.coerceAtLeast(1)
        val srcH = source.height.coerceAtLeast(1)

        if (cropRect != null && cropRect.usesBlurredFrameFill) {
            return renderBlurredFrameFillBitmap(source, display, displays)
        }

        val bounds = cropRenderer.computeAspectCropBounds(
            sourceWidth = srcW,
            sourceHeight = srcH,
            focalX = cropRect?.focalX ?: 0.5,
            focalY = cropRect?.focalY ?: 0.5,
            targetAspect = display.aspectRatio
        )

        val (budgetW, budgetH) = CropRenderer.budgetedSize(
            display.widthPx.coerceAtLeast(1),
            display.heightPx.coerceAtLeast(1)
        )
        return cropAndScale(source, bounds, budgetW, budgetH)
    }

    /**
     * The [bounds] region of [source], scaled uniformly down to [budgetW] x [budgetH] when it is larger than that in both
     * directions. One call does both, so the unscaled crop is never allocated: with the source, the crop and the result
     * all alive at once, a 4K wallpaper from a 24 MP photo held about 210 MB; now it holds the source and the result
     * (about 130 MB). Same pixels, same bilinear filter, same size as cropping first and scaling after.
     */
    private fun cropAndScale(source: Bitmap, bounds: CropRect, budgetW: Int, budgetH: Int): Bitmap {
        if (bounds.width > budgetW && bounds.height > budgetH) {
            val scale = Matrix().apply { setScale(budgetW.toFloat() / bounds.width, budgetH.toFloat() / bounds.height) }
            return Bitmap.createBitmap(source, bounds.left, bounds.top, bounds.width, bounds.height, scale, true)
        }
        return Bitmap.createBitmap(source, bounds.left, bounds.top, bounds.width, bounds.height)
    }

    /**
     * Renders a blurred, darkened ambient frame extension behind the unstretched foreground image
     * when `usesBlurredFrameFill == true` (Article IV §4).
     */
    fun renderBlurredFrameFillBitmap(
        source: Bitmap,
        display: DisplayProfile,
        displays: List<DisplayProfile> = listOf(display)
    ): Bitmap {
        val layout = CropRenderer.blurredFillLayout(source.width, source.height, displays.ifEmpty { listOf(display) })
        val outW = layout.canvasWidth
        val outH = layout.canvasHeight
        val canvasBmp = createBitmap(outW, outH)
        val canvas = Canvas(canvasBmp)

        // 1. Create low-res blurred + darkened background fill matching the canvas aspect ratio
        val (rawBlurW, rawBlurH) = CropRenderer.budgetedSize(outW, outH, maxPixels = 2304)
        val blurW = rawBlurW.coerceAtLeast(16)
        val blurH = rawBlurH.coerceAtLeast(16)
        val tiny = source.scale(blurW, blurH)
        boxBlurAndDarkenInPlace(tiny, radius = 2, darkenFactor = 0.55f)
        val bgRect = Rect(0, 0, outW, outH)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(tiny, null, bgRect, paint)
        if (tiny != source) tiny.recycle()

        // 2. Draw the unstretched foreground where every display's centre-crop still shows all of it
        val fgRect = Rect(layout.fgLeft, layout.fgTop, layout.fgLeft + layout.fgWidth, layout.fgTop + layout.fgHeight)
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

    /** See [SunloomWallpaperScheduler.cancelAllScheduledWorkAndWait]. */
    fun cancelAllScheduledWorkAndWait(): Boolean = scheduler.cancelAllScheduledWorkAndWait()

    fun cancelAllScheduledWork() {
        scheduler.cancelAllScheduledWork()
    }

    fun suspendForUnavailableStore() {
        scheduler.suspendForUnavailableStore()
    }

    fun ensureScheduled(preferences: UserPreferences) {
        scheduler.ensureScheduled(preferences)
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

        /** EXIF orientations that turn the picture a quarter turn, so width and height swap (transpose, 90, transverse, 270). */
        private val SIDEWAYS_ORIENTATIONS = setOf(5, 6, 7, 8)

        suspend fun executeScheduledWallpaperChange(
            appContext: Context,
            repo: LocalLibraryRepository = appContext.appContainer().newRepository(),
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
            repo: LocalLibraryRepository = appContext.appContainer().newRepository(),
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

/** One apply-and-record step at a time in the process (the app and the background worker both apply). */
internal object ApplyLock {
    private val lock = java.util.concurrent.locks.ReentrantLock()
    fun <T> run(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

/**
 * What an apply shows about independent lock-screen support. Only a lock-only apply is evidence: when both are set
 * together the system may share one picture, so a "changed" lock id there proves nothing and must not overwrite what
 * a lock-only apply observed.
 */
internal object LockSupportEvidence {
    fun observe(wantsSystem: Boolean, wantsLock: Boolean, afterLockId: Int, lockChanged: Boolean, result: Int): Boolean? =
        if (wantsLock && !wantsSystem) afterLockId >= 0 && (lockChanged || result > 0) else null
}
