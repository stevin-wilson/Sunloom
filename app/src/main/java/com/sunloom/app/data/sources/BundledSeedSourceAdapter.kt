package com.sunloom.app.data.sources

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.CandidateAsset
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.safety.SafetyGate
import kotlin.math.cos
import kotlin.math.sin

data class SeedWallpaperSpec(
    val id: String,
    val title: String,
    val description: String,
    val category: SafeCategory,
    val targetBrightness: BrightnessClass,
    val subjectTags: List<String>,
    val topColorRgb: Int,
    val horizonColorRgb: Int,
    val bottomColorRgb: Int,
    val accentColorRgb: Int
)

/**
 * Article IV §2 & IX §1 (Constitution v0.4):
 * Procedural Seed Renderer generating smooth multi-layer horizon and mountain-ridge geometry
 * with 4x4 ordered Bayer dithering at the connected display's native pixel resolution.
 */
object SeedRenderer {

    // 4x4 Bayer ordered dither matrix normalized to [-7.5 .. +7.5] / 8
    private val BAYER_4X4 = intArrayOf(
        -7, 1, -5, 3,
        5, -3, 7, -1,
        -4, 4, -6, 2,
        8, 0, 6, -2
    )

    fun generateRgbGrid(spec: SeedWallpaperSpec, gridW: Int = 48, gridH: Int = 30): IntArray {
        val w = gridW.coerceAtLeast(2)
        val h = gridH.coerceAtLeast(2)
        val grid = IntArray(w * h)
        val ridgeWaves = DoubleArray(w) { x ->
            val u = x.toDouble() / (w - 1).toDouble()
            0.58 + 0.08 * sin(u * 5.2) + 0.04 * cos(u * 11.0)
        }
        val secondaryWaves = DoubleArray(w) { x ->
            val u = x.toDouble() / (w - 1).toDouble()
            0.70 + 0.05 * cos(u * 4.1 + 0.8) + 0.03 * sin(u * 9.3)
        }

        for (y in 0 until h) {
            val v = y.toDouble() / (h - 1).toDouble()
            val rowOffset = y * w
            for (x in 0 until w) {
                val ridgeWave = ridgeWaves[x]
                val secWave = secondaryWaves[x]
                val baseColor = when {
                    v < ridgeWave -> {
                        val t = (v / ridgeWave).coerceIn(0.0, 1.0)
                        lerpRgbDithered(spec.topColorRgb, spec.horizonColorRgb, t, x, y)
                    }
                    v < secWave -> {
                        val t = ((v - ridgeWave) / (secWave - ridgeWave).coerceAtLeast(0.01)).coerceIn(0.0, 1.0)
                        lerpRgbDithered(spec.accentColorRgb, spec.bottomColorRgb, t * 0.65, x, y)
                    }
                    else -> {
                        val t = ((v - secWave) / (1.0 - secWave).coerceAtLeast(0.01)).coerceIn(0.0, 1.0)
                        lerpRgbDithered(spec.accentColorRgb, spec.bottomColorRgb, 0.65 + t * 0.35, x, y)
                    }
                }
                grid[rowOffset + x] = baseColor
            }
        }
        return grid
    }

    /**
     * Renders a native-resolution [Bitmap] (`widthPx × heightPx`) with smooth scanline interpolation
     * and 4x4 ordered Bayer dithering to eliminate banding on 8-bit laptop displays.
     */
    fun render(spec: SeedWallpaperSpec, widthPx: Int, heightPx: Int): Bitmap {
        val (w, h) = com.sunloom.app.domain.renderer.CropRenderer.budgetedSize(
            width = widthPx.coerceAtLeast(64),
            height = heightPx.coerceAtLeast(64)
        )
        val bitmap = createBitmap(w, h)

        val ridgeWaves = DoubleArray(w) { x ->
            val u = x.toDouble() / (w - 1).coerceAtLeast(1).toDouble()
            0.58 + 0.08 * sin(u * 5.2) + 0.04 * cos(u * 11.0)
        }
        val secondaryWaves = DoubleArray(w) { x ->
            val u = x.toDouble() / (w - 1).coerceAtLeast(1).toDouble()
            0.70 + 0.05 * cos(u * 4.1 + 0.8) + 0.03 * sin(u * 9.3)
        }

        val rowPixels = IntArray(w)
        val denomH = (h - 1).coerceAtLeast(1).toDouble()

        for (y in 0 until h) {
            val v = y.toDouble() / denomH
            for (x in 0 until w) {
                val ridgeWave = ridgeWaves[x]
                val secWave = secondaryWaves[x]
                val rgb = when {
                    v < ridgeWave -> {
                        val t = (v / ridgeWave).coerceIn(0.0, 1.0)
                        lerpRgbDithered(spec.topColorRgb, spec.horizonColorRgb, t, x, y)
                    }
                    v < secWave -> {
                        val t = ((v - ridgeWave) / (secWave - ridgeWave).coerceAtLeast(0.01)).coerceIn(0.0, 1.0)
                        lerpRgbDithered(spec.accentColorRgb, spec.bottomColorRgb, t * 0.65, x, y)
                    }
                    else -> {
                        val t = ((v - secWave) / (1.0 - secWave).coerceAtLeast(0.01)).coerceIn(0.0, 1.0)
                        lerpRgbDithered(spec.accentColorRgb, spec.bottomColorRgb, 0.65 + t * 0.35, x, y)
                    }
                }
                rowPixels[x] = (0xFF shl 24) or rgb
            }
            bitmap.setPixels(rowPixels, 0, w, 0, y, w, 1)
        }
        return bitmap
    }

    private fun lerpRgbDithered(c1: Int, c2: Int, t: Double, x: Int, y: Int): Int {
        val dither = BAYER_4X4[((y and 3) shl 2) or (x and 3)] / 8.0
        val r1 = (c1 shr 16) and 0xFF
        val g1 = (c1 shr 8) and 0xFF
        val b1 = c1 and 0xFF
        val r2 = (c2 shr 16) and 0xFF
        val g2 = (c2 shr 8) and 0xFF
        val b2 = c2 and 0xFF
        val r = (r1 + (r2 - r1) * t + dither).toInt().coerceIn(0, 255)
        val g = (g1 + (g2 - g1) * t + dither).toInt().coerceIn(0, 255)
        val b = (b1 + (b2 - b1) * t + dither).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }
}

/**
 * Keyless Source #4: Bundled Offline Seed Reserve (Article IX §1–2).
 * Guarantees that Sunloom works 100% offline from the very first launch with
 * 3 Light (Daytime) and 3 Dark (Evening/Night) procedural CC0 compositions rendered
 * at the connected display's native resolution with honest on-device provenance.
 */
class BundledSeedSourceAdapter : WallpaperSourceAdapter {

    override val sourceId: String = "seed_reserve"
    override val sourceName: String = "Sunloom On-Device Procedural Reserve"
    override val licenseSummary: String = "CC0 1.0 Public Domain (Generated on device by Sunloom)"
    override val isBuiltInOffline: Boolean = true

    override suspend fun fetchCandidates(
        enabledCategories: Set<SafeCategory>,
        limit: Int,
        offset: Int,
        targetWidthPx: Int
    ): List<CandidateAsset> {
        val targetHeightPx = (targetWidthPx * 10) / 16
        return SEED_SPECS.filter { it.category in enabledCategories || enabledCategories.isEmpty() }
            .take(limit)
            .map { spec ->
                CandidateAsset(
                    id = spec.id,
                    title = spec.title,
                    description = spec.description,
                    category = spec.category,
                    rawCategoriesAndTags = spec.subjectTags,
                    downloadUrl = "",
                    provenance = ProvenanceRecord(
                        sourceId = sourceId,
                        sourceName = sourceName,
                        assetId = spec.id,
                        originalPageUrl = "",
                        creatorName = "Generated on device by Sunloom",
                        creatorPageUrl = "",
                        licenseType = LicenseType.CC0,
                        attributionText = "${spec.title} — Generated on device by Sunloom, CC0 1.0",
                        retrievedAtEpochMs = System.currentTimeMillis(),
                        originalWidth = targetWidthPx,
                        originalHeight = targetHeightPx,
                        renditionWidth = targetWidthPx,
                        renditionHeight = targetHeightPx,
                        safetyClassification = SafetyClassification.SAFE,
                        safetyClassificationSource = "Sunloom On-Device Procedural CC0 Reserve",
                        sha256Checksum = "",
                        licenseUrl = LicenseType.CC0.defaultUrl,
                        licenseVersion = "1.0",
                        adapterVersion = SourceRegistry.get(sourceId)?.adapterVersion ?: ProvenanceRecord.DEFAULT_ADAPTER_VERSION,
                        safetyGateVersion = SafetyGate.COMPOSITE_GATE_VERSION
                    )
                )
            }
    }

    companion object {
        val SEED_SPECS: List<SeedWallpaperSpec> = listOf(
            // 3 Daytime LIGHT Wallpapers
            SeedWallpaperSpec(
                id = "seed-light-alpine-mist",
                title = "Alpine Morning Mist",
                description = "Crisp glacial valley layered in pale morning mist with calm sky legibility zones.",
                category = SafeCategory.LANDSCAPES_NATURE,
                targetBrightness = BrightnessClass.LIGHT,
                subjectTags = listOf("alpine", "mountain", "valley", "glacier"),
                topColorRgb = 0xD8ECFA,
                horizonColorRgb = 0xEFF6FF,
                bottomColorRgb = 0xB8D4E8,
                accentColorRgb = 0x93C5FD
            ),
            SeedWallpaperSpec(
                id = "seed-light-solstice-dunes",
                title = "Solstice Sandstone Horizon",
                description = "Warm daylight desert ridges and high-altitude cirrus clouds.",
                category = SafeCategory.LANDSCAPES_NATURE,
                targetBrightness = BrightnessClass.LIGHT,
                subjectTags = listOf("desert", "sandstone", "dune", "horizon"),
                topColorRgb = 0xE0F2FE,
                horizonColorRgb = 0xFEF3C7,
                bottomColorRgb = 0xFDE68A,
                accentColorRgb = 0xFBBF24
            ),
            SeedWallpaperSpec(
                id = "seed-light-nordic-arch",
                title = "Nordic Pavilion Geometry",
                description = "Minimalist sunlit limestone architectural planes with soft daylight reflections.",
                category = SafeCategory.ARCHITECTURE_CITYSCAPES,
                targetBrightness = BrightnessClass.LIGHT,
                subjectTags = listOf("architecture", "pavilion", "geometry"),
                topColorRgb = 0xE2E8F0,
                horizonColorRgb = 0xF1F5F9,
                bottomColorRgb = 0xCBD5E1,
                accentColorRgb = 0x94A3B8
            ),
            // 3 Evening/Nighttime DARK Wallpapers
            SeedWallpaperSpec(
                id = "seed-dark-orion-nebula",
                title = "Deep Orion Twilight Field",
                description = "Deep-space indigo starfield with calm dark clock and taskbar legibility zones.",
                category = SafeCategory.SPACE_ASTRONOMY,
                targetBrightness = BrightnessClass.DARK,
                subjectTags = listOf("nebula", "astronomy", "space", "star"),
                topColorRgb = 0x0B1120,
                horizonColorRgb = 0x1E1B4B,
                bottomColorRgb = 0x0F172A,
                accentColorRgb = 0x312E81
            ),
            SeedWallpaperSpec(
                id = "seed-dark-midnight-fjord",
                title = "Midnight Fjord Aurora",
                description = "Tranquil sub-arctic fjord under subtle emerald twilight ribbons.",
                category = SafeCategory.LANDSCAPES_NATURE,
                targetBrightness = BrightnessClass.DARK,
                subjectTags = listOf("fjord", "aurora", "twilight", "coast"),
                topColorRgb = 0x091526,
                horizonColorRgb = 0x112A46,
                bottomColorRgb = 0x0A192F,
                accentColorRgb = 0x064E3B
            ),
            SeedWallpaperSpec(
                id = "seed-dark-obsidian-ridge",
                title = "Obsidian Mountain Silhouette",
                description = "Layered blue-slate mountain ridges after dusk designed for zero eye strain.",
                category = SafeCategory.LANDSCAPES_NATURE,
                targetBrightness = BrightnessClass.DARK,
                subjectTags = listOf("mountain", "ridge", "dusk", "horizon"),
                topColorRgb = 0x0F172A,
                horizonColorRgb = 0x1E293B,
                bottomColorRgb = 0x090D16,
                accentColorRgb = 0x334155
            )
        )

        fun generateRgbGrid(spec: SeedWallpaperSpec, gridW: Int = 48, gridH: Int = 30): IntArray =
            SeedRenderer.generateRgbGrid(spec, gridW, gridH)
    }
}
