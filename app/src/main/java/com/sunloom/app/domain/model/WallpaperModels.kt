package com.sunloom.app.domain.model

enum class SafetyClassification {
    SAFE,
    UNCERTAIN,
    MISSING,
    CONFLICTING,
    NSFW_OR_UNSAFE
}

enum class SafeCategory(val displayName: String, val description: String) {
    LANDSCAPES_NATURE("Landscapes & Nature", "Mountains, forests, oceans, skies, and calm natural scenery"),
    SPACE_ASTRONOMY("Space & Astronomy", "Nebulae, galaxies, starfields, and deep-space photography"),
    ARCHITECTURE_CITYSCAPES("Architecture & Skylines", "Bridges, minimal geometry, historic structures, and city horizons"),
    ABSTRACT_PATTERNS("Abstract & Minimal", "Geometric textures, calm gradients, and natural mineral patterns"),
    SCENIC_FINE_ART("Scenic Fine Art", "Non-figurative landscape paintings and architectural fine art (CC0)"),
    BOTANICAL_FLORA("Botanical & Flora", "Wildflowers, lush forest foliage, blooming orchards, and botanical art"),
    AERIAL_EARTH("Aerial & Earth", "High-altitude coastlines, orbital Earth views, river deltas, and drone perspectives"),
    USER_LOCAL("My Local Files", "User-provided photos from your GoogleBook storage")
}

enum class LicenseType(val label: String, val permitsWallpaperUse: Boolean, val defaultUrl: String = "") {
    PUBLIC_DOMAIN("Public Domain", true, "https://creativecommons.org/publicdomain/mark/1.0/"),
    CC0("CC0 1.0 Universal", true, "https://creativecommons.org/publicdomain/zero/1.0/"),
    CC_BY("Creative Commons Attribution (CC BY)", true, "https://creativecommons.org/licenses/by/4.0/"),
    CC_BY_SA("Creative Commons Attribution-ShareAlike (CC BY-SA)", true, "https://creativecommons.org/licenses/by-sa/4.0/"),
    USER_OWNED("User-Provided Local File", true, ""),
    UNKNOWN_OR_RESTRICTED("Unknown or Restricted License", false, "")
}

enum class BrightnessClass(val displayName: String) {
    LIGHT("Light (Daytime)"),
    DARK("Dark (Evening / Night)"),
    NEUTRAL("Balanced")
}

enum class ScheduleMode(val label: String, val plainDescription: String) {
    SUNRISE_SUNSET("Sunrise & sunset", "Switches automatically at local sunrise and sunset"),
    FOLLOW_OS_THEME("Follow system theme", "Matches your device's light and dark appearance"),
    FIXED_CLOCK("Custom schedule", "Switches at the daytime and evening hours you choose"),
    MANUAL_ONLY("Manual only", "Changes only when you tap Next")
}

enum class DisplayPolicy(val label: String) {
    SAME_ON_ALL_DISPLAYS("Share an envelope-fitted wallpaper across all connected displays (Recommended)"),
    OPTIMIZE_PRIMARY_SHARPEST("Optimize crop for the highest-resolution connected display"),
    INDEPENDENT_PER_DISPLAY("Different image per display (Unavailable: Android shares one system wallpaper surface)")
}

enum class DisplayQualityTier(
    val minCoverageRatio: Double,
    val minLongEdgePx: Int,
    val shortLabel: String
) {
    /** Article IV §2 (v0.15): 98%, so a source a few pixels short of the display is enlarged by at most 2%. */
    STANDARD_100(0.98, 0, "98% native floor (<120 PPI)"),
    MID_80(0.80, 0, "80% mid-PPI floor (120–209 PPI)"),
    HIGH_65(0.65, 1600, "65% Retina floor (≥210 PPI, ≥1600px)")
}

enum class LockWorkspacePolicy(val label: String) {
    SAME_ON_BOTH("Home & Lock screen"),
    WORKSPACE_ONLY("Home screen only"),
    LOCK_ONLY("Lock screen only")
}

enum class WallpaperStatus(val label: String) {
    DOWNLOADED("Downloaded"),
    SAFETY_APPROVED("Safety-approved"),
    PREPARED("Prepared for display"),
    APPLIED_SUCCESSFULLY("Applied"),
    WAITING_FOR_PERMISSION("Waiting for permission"),
    UNSUPPORTED_BY_SYSTEM("Unsupported by this system"),
    FAILED_TEMPORARILY("Failed temporarily"),
    REJECTED_SAFETY_OR_QUALITY("Rejected for safety or quality"),
    DISABLED_BY_USER("Disabled by user")
}

enum class ImageFormat(val fileExtension: String, val mimeType: String) {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    WEBP("webp", "image/webp"),
    UNSUPPORTED("bin", "application/octet-stream")
}

enum class SourceHealthState(val label: String) {
    HEALTHY("Healthy"),
    DEGRADED("Degraded"),
    AUTO_DISABLED("Auto-Disabled (Repeated Failures)"),
    REVOKED("Revoked")
}

data class SourceDiagnostics(
    val sourceId: String,
    val sourceName: String,
    val isAdmitted: Boolean,
    val isEnabled: Boolean,
    val healthState: SourceHealthState = SourceHealthState.HEALTHY,
    val consecutiveFailures: Int = 0,
    val lastError: String = "",
    val backoffUntilEpochMs: Long = 0L,
    /** One line describing the most recent sync, e.g. "fetched 8, added 3, rejected 4, HTTP errors 0". */
    val lastSyncSummary: String = ""
)

data class ProvenanceRecord(
    val sourceId: String,
    val sourceName: String,
    val assetId: String,
    val originalPageUrl: String,
    val creatorName: String,
    val creatorPageUrl: String,
    val licenseType: LicenseType,
    val attributionText: String,
    val retrievedAtEpochMs: Long,
    val originalWidth: Int,
    val originalHeight: Int,
    val renditionWidth: Int,
    val renditionHeight: Int,
    val safetyClassification: SafetyClassification,
    val safetyClassificationSource: String,
    val sha256Checksum: String,
    val licenseUrl: String = licenseType.defaultUrl,
    val licenseVersion: String = "",
    val adapterVersion: String = DEFAULT_ADAPTER_VERSION,
    val safetyGateVersion: String = DEFAULT_GATE_VERSION,
    val perceptualHash: String = "",
    val gateLayerOutcomes: String = ""
) {
    companion object {
        const val DEFAULT_ADAPTER_VERSION = "1.4.0"
        const val DEFAULT_GATE_VERSION = "v0.6.0:2026.11.04-v3:eval-v4:bl-v2"
    }
}

data class CandidateAsset(
    val id: String,
    val title: String,
    val description: String,
    val category: SafeCategory,
    val rawCategoriesAndTags: List<String>,
    val downloadUrl: String,
    val provenance: ProvenanceRecord,
    val isUserLocal: Boolean = false,
    val fallbackDownloadUrls: List<String> = emptyList()
)

sealed class SafetyVerdict {
    data class Approved(
        val sha256Checksum: String,
        val detectedFormat: ImageFormat,
        val auditNotes: List<String>,
        val gateLayerOutcomes: String = "L1:PASS;L2:PASS;L3:PASS"
    ) : SafetyVerdict()

    data class Rejected(
        val reasonCode: String,
        val explanation: String,
        val failedLayer: String = "L2"
    ) : SafetyVerdict()
}

data class BlocklistEntry(
    val assetId: String,
    val sha256: String,
    val dHash: String,
    val sourceId: String,
    val reason: String,
    val createdAtEpochMs: Long
)

data class RejectionRecord(
    val assetId: String,
    val sourceId: String,
    val reasonCode: String,
    val explanation: String,
    val timestampEpochMs: Long,
    val isPermanent: Boolean = false,
    val displaySignature: String = ""
)

data class DisplayProfile(
    val displayId: Int,
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int = 160,
    val isPrimary: Boolean = true,
    val physicalPpi: Double? = null
) {
    val aspectRatio: Double
        get() = if (heightPx > 0) widthPx.toDouble() / heightPx.toDouble() else 16.0 / 10.0

    val resolutionLabel: String
        get() = "${widthPx} × ${heightPx}"

    val qualityTier: DisplayQualityTier
        get() {
            val ppi = physicalPpi
            return if (ppi != null) {
                when {
                    ppi >= 210.0 -> DisplayQualityTier.HIGH_65
                    ppi >= 120.0 -> DisplayQualityTier.MID_80
                    else -> DisplayQualityTier.STANDARD_100
                }
            } else {
                when {
                    densityDpi >= 210 -> DisplayQualityTier.HIGH_65
                    densityDpi >= 180 -> DisplayQualityTier.MID_80
                    else -> DisplayQualityTier.STANDARD_100
                }
            }
        }

    /**
     * What a display is, not which id Android gave it: Android hands out a new [displayId] when the
     * same monitor is re-plugged, and that must not look like a different display (Article IV §6).
     */
    val signature: String
        get() = "${widthPx}x${heightPx}@${densityDpi}:${qualityTier.name}"

    val isHighDensityOrRetina: Boolean
        get() = qualityTier == DisplayQualityTier.HIGH_65

    /**
     * Identifies everything about a display that affects crop/quality decisions
     * (excludes [displayId] and [name] so re-plugging the same monitor is not a change).
     */
    val geometryKey: String
        get() = "${widthPx}x${heightPx}@${qualityTier.name}"
}

private fun displaySetKey(displays: List<DisplayProfile>, perDisplay: (DisplayProfile) -> String): String =
    displays.map { (if (it.isPrimary) "*" else "") + perDisplay(it) }.sorted().joinToString("+")

/**
 * Identifies a whole display set for display-scoped rejections; independent of ids and order. The
 * primary display is marked because the envelope anchors on it, so a rejection made for one anchor
 * is not reused for another.
 */
fun displaySetSignature(displays: List<DisplayProfile>): String = displaySetKey(displays) { it.signature }

/** Identifies a whole display set's crop geometry; independent of ids and order (primary marked, as above). */
fun displaySetGeometryKey(displays: List<DisplayProfile>): String = displaySetKey(displays) { it.geometryKey }

data class RememberedDisplayRecord(
    val key: String,
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val physicalPpi: Double? = null,
    val qualityTier: DisplayQualityTier = DisplayQualityTier.STANDARD_100,
    val lastSeenEpochMs: Long = 0L,
    val isCurrentlyConnected: Boolean = false
) {
    val resolutionLabel: String
        get() = "${widthPx} × ${heightPx}"
}

data class CropRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val focalX: Double,
    val focalY: Double,
    val usesBlurredFrameFill: Boolean,
    val pixelCoverageRatio: Double
)

data class ImageEvaluationResult(
    val isEligible: Boolean,
    val rejectionReasonCode: String?,
    val globalLuminance: Double,
    val desktopZoneLuminance: Double,
    val lockClockZoneLuminance: Double,
    val glareFractionInSafeZones: Double,
    val contrastStdDev: Double,
    val brightnessClass: BrightnessClass,
    val cropRect: CropRect?,
    val explanation: String,
    /** Median CIE L* (0..100) of the cropped picture; -1 when unknown (older records). */
    val lightnessP50: Double = -1.0,
    /** Contrast ratio of white desktop labels over the 90th-percentile brightness of the icon/bar zones. */
    val desktopLabelContrast: Double = -1.0,
    /** Mean (R - B) / 255 of the crop: -1 cool .. +1 warm. */
    val warmth: Double = 0.0,
    /** Dominant hue in 30-degree steps (0..11), or -1 for achromatic/unknown. */
    val hueBucket: Int = -1
)

data class WallpaperRecord(
    val id: String,
    val title: String,
    val category: SafeCategory,
    val localFilePath: String,
    val croppedFilePath: String,
    val provenance: ProvenanceRecord,
    val evaluation: ImageEvaluationResult,
    val isFavorite: Boolean = false,
    val isRejected: Boolean = false,
    val isBaselineRestore: Boolean = false,
    val appliedCount: Int = 0,
    val lastAppliedEpochMs: Long = 0L,
    val fileSizeBytes: Long = 0L,
    val status: WallpaperStatus = WallpaperStatus.PREPARED,
    val decisionExplanation: String = "",
    val description: String = "",
    val rawCategoriesAndTags: List<String> = emptyList()
)

data class PlatformCapabilities(
    val isWallpaperSupported: Boolean,
    val isSetWallpaperAllowed: Boolean,
    val supportsIndependentLockWallpaper: Boolean,
    val supportsPerDisplayWallpaper: Boolean,
    val connectedDisplays: List<DisplayProfile>,
    val capabilityDisclosureNote: String,
    val rememberedDisplays: List<RememberedDisplayRecord> = emptyList(),
    val excludedDisplaysNote: String = ""
)

data class UserPreferences(
    val isSetupCompleted: Boolean = false,
    val autoChangeEnabled: Boolean = true,
    val isPaused: Boolean = false,
    val scheduleMode: ScheduleMode = ScheduleMode.SUNRISE_SUNSET,
    val selectedCityName: String = "San Francisco, United States",
    val latitude: Double = 37.7749,
    val longitude: Double = -122.4194,
    val fixedLightHour: Int = 7,
    val fixedDarkHour: Int = 19,
    val rotationIntervalHours: Int = 0,
    val enabledCategories: Set<SafeCategory> = setOf(
        SafeCategory.LANDSCAPES_NATURE,
        SafeCategory.SPACE_ASTRONOMY,
        SafeCategory.ARCHITECTURE_CITYSCAPES,
        SafeCategory.ABSTRACT_PATTERNS,
        SafeCategory.SCENIC_FINE_ART,
        SafeCategory.BOTANICAL_FLORA,
        SafeCategory.AERIAL_EARTH
    ),
    val enabledSources: Set<String> = setOf(
        "seed_reserve",
        "wikimedia_featured",
        "nasa_library",
        "artic_cc0",
        "met_museum_cc0",
        "cma_cc0",
        "smk_open",
        "user_local"
    ),
    val displayPolicy: DisplayPolicy = DisplayPolicy.SAME_ON_ALL_DISPLAYS,
    val lockWorkspacePolicy: LockWorkspacePolicy = LockWorkspacePolicy.SAME_ON_BOTH,
    val allowBlurredFrameFill: Boolean = false,
    val wifiOnlyDownloads: Boolean = false,
    val deferOnBatterySaver: Boolean = true,
    val maxCacheBytes: Long = DEFAULT_MAX_CACHE_BYTES,
    val baselineRestorePath: String? = null,
    val dryRunPreviewOnly: Boolean = false
) {
    companion object {
        /** Default reserve (cache) ceiling: 1 GiB. Matches the "1 GB cap" choice in Diagnostics. */
        const val DEFAULT_MAX_CACHE_BYTES: Long = 1024L * 1024L * 1024L

        /** Ceiling before v0.14; stored values equal to it are raised once (see the repository). */
        const val LEGACY_MAX_CACHE_BYTES: Long = 250L * 1024L * 1024L
    }
}
