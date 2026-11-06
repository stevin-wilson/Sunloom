package com.sunloom.app.platform.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.core.content.edit
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.DisplayQualityTier
import com.sunloom.app.domain.model.RememberedDisplayRecord
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Articles IV §1–2, IV §6 & XVIII §1:
 * Hardware display probe measuring native physical mode resolution, live display orientation,
 * and calibrated EDID physical PPI (`xdpi`/`ydpi`), rejecting VESA 72/96 placeholders and
 * implausible EDID values, and never inventing made-up fallback resolutions.
 */
class DisplayProbe(
    private val context: Context,
    private val historyStore: DisplayHistoryStore = DisplayHistoryStore(context)
) {

    fun probeConnectedDisplays(recordHistory: Boolean = true): List<DisplayProfile> {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val allDisplays = displayManager?.displays.orEmpty()
            .filter { d -> (d.flags and Display.FLAG_PRIVATE) == 0 && d.state != Display.STATE_OFF }

        val measured = allDisplays.mapIndexedNotNull { idx, d ->
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            d.getRealMetrics(metrics)

            val mode = runCatching { d.mode }.getOrNull()
            val rotation = runCatching { d.rotation }.getOrDefault(Surface.ROTATION_0)
            val resolvedSize = resolveModeDimensions(
                modeWidth = mode?.physicalWidth ?: 0,
                modeHeight = mode?.physicalHeight ?: 0,
                rotation = rotation,
                fallbackMetricsWidth = metrics.widthPixels,
                fallbackMetricsHeight = metrics.heightPixels
            )
            val w = resolvedSize?.first ?: 0
            val h = resolvedSize?.second ?: 0
            val dpi = metrics.densityDpi.takeIf { it > 0 }
                ?: context.resources.displayMetrics.densityDpi.takeIf { it > 0 }

            if (w <= 0 || h <= 0 || dpi == null) {
                null
            } else {
                // Scale xdpi/ydpi proportionally if Display.Mode physical pixels exceed scaled logical metrics
                val scaleX = if (metrics.widthPixels > 0) w.toFloat() / metrics.widthPixels.toFloat() else 1f
                val scaleY = if (metrics.heightPixels > 0) h.toFloat() / metrics.heightPixels.toFloat() else 1f
                val effectiveXdpi = if (scaleX in 0.95f..4.05f) metrics.xdpi * scaleX else metrics.xdpi
                val effectiveYdpi = if (scaleY in 0.95f..4.05f) metrics.ydpi * scaleY else metrics.ydpi
                val physicalPpi = resolvePhysicalPpi(w, h, effectiveXdpi, effectiveYdpi)

                DisplayProfile(
                    displayId = d.displayId,
                    name = d.name?.takeIf { it.isNotBlank() }
                        ?: if (idx == 0) "GoogleBook Built-in Display" else "External Monitor #${idx + 1}",
                    widthPx = w,
                    heightPx = h,
                    densityDpi = dpi,
                    isPrimary = d.displayId == Display.DEFAULT_DISPLAY,
                    physicalPpi = physicalPpi
                )
            }
        }

        if (measured.isNotEmpty()) {
            if (recordHistory) {
                historyStore.recordConnectedDisplays(measured)
            }
            return measured
        }

        // Fall back only to real WindowManager.maximumWindowMetrics (never made-up resolutions)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val bounds = runCatching { wm?.maximumWindowMetrics?.bounds }.getOrNull()
            val dm = context.resources.displayMetrics
            val dpi = context.resources.configuration.densityDpi.takeIf { it > 0 }
                ?: dm.densityDpi.takeIf { it > 0 }
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0 && dpi != null) {
                val fallbackDisplay = DisplayProfile(
                    displayId = Display.DEFAULT_DISPLAY,
                    name = "GoogleBook Built-in Display",
                    widthPx = bounds.width(),
                    heightPx = bounds.height(),
                    densityDpi = dpi,
                    isPrimary = true,
                    physicalPpi = resolvePhysicalPpi(bounds.width(), bounds.height(), dm.xdpi, dm.ydpi)
                )
                if (recordHistory) {
                    historyStore.recordConnectedDisplays(listOf(fallbackDisplay))
                }
                return listOf(fallbackDisplay)
            }
        }

        return emptyList()
    }

    companion object {
        private const val MIN_PLAUSIBLE_PPI = 60.0f
        private const val MAX_PLAUSIBLE_PPI = 600.0f
        private const val MAX_ANISOTROPY_RATIO = 1.35f

        /**
         * Resolves calibrated physical panel PPI from EDID-derived `xdpi` / `ydpi` (Article IV §2).
         * Returns `null` when EDID values are outside `[60..600]`, uncalibrated VESA `72`/`96`
         * placeholders, or severely non-square, triggering a safe fallback to `densityDpi`.
         */
        fun resolvePhysicalPpi(
            widthPx: Int,
            heightPx: Int,
            xdpi: Float,
            ydpi: Float
        ): Double? {
            if (widthPx <= 0 || heightPx <= 0) return null
            if (!xdpi.isFinite() || !ydpi.isFinite()) return null
            if (xdpi !in MIN_PLAUSIBLE_PPI..MAX_PLAUSIBLE_PPI || ydpi !in MIN_PLAUSIBLE_PPI..MAX_PLAUSIBLE_PPI) {
                return null
            }
            val isVesa72 = abs(xdpi - 72.0f) <= 1.0f && abs(ydpi - 72.0f) <= 1.0f
            val isVesa96 = abs(xdpi - 96.0f) <= 1.0f && abs(ydpi - 96.0f) <= 1.0f
            if (isVesa72 || isVesa96) {
                return null
            }
            val anisotropy = max(xdpi, ydpi) / min(xdpi, ydpi).coerceAtLeast(1.0f)
            if (anisotropy > MAX_ANISOTROPY_RATIO) {
                return null
            }
            return sqrt(xdpi.toDouble() * ydpi.toDouble())
        }

        /**
         * Resolves physical panel pixel dimensions from `Display.Mode` oriented to match the
         * live display rotation / metrics orientation.
         */
        fun resolveModeDimensions(
            modeWidth: Int,
            modeHeight: Int,
            rotation: Int,
            fallbackMetricsWidth: Int,
            fallbackMetricsHeight: Int
        ): Pair<Int, Int>? {
            if (modeWidth > 0 && modeHeight > 0) {
                if (fallbackMetricsWidth > 0 &&
                    fallbackMetricsHeight > 0 &&
                    fallbackMetricsWidth != fallbackMetricsHeight &&
                    modeWidth != modeHeight
                ) {
                    val longEdge = max(modeWidth, modeHeight)
                    val shortEdge = min(modeWidth, modeHeight)
                    return if (fallbackMetricsWidth > fallbackMetricsHeight) {
                        longEdge to shortEdge
                    } else {
                        shortEdge to longEdge
                    }
                }
                return if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
                    modeHeight to modeWidth
                } else {
                    modeWidth to modeHeight
                }
            }
            if (fallbackMetricsWidth > 0 && fallbackMetricsHeight > 0) {
                return fallbackMetricsWidth to fallbackMetricsHeight
            }
            return null
        }
    }
}

/**
 * Persists up to [MAX_REMEMBERED_DISPLAYS] recently connected displays for diagnostics and
 * user inspection, with explicit `forgetDisplay` support (Constitution v0.6 Article IV §1).
 * Disconnected displays never constrain active wallpaper crops.
 */
class DisplayHistoryStore(context: Context) {

    companion object {
        const val MAX_REMEMBERED_DISPLAYS = 6
        private const val PREFS_NAME = "sunloom_display_history"
        private const val KEY_RECORDS_JSON = "remembered_displays_v1"

        fun computeHistoryKey(display: DisplayProfile): String {
            return "${display.name.trim()}|${display.widthPx}x${display.heightPx}@${display.qualityTier.name}"
        }
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun recordConnectedDisplays(
        displays: List<DisplayProfile>,
        nowEpochMs: Long = System.currentTimeMillis()
    ) {
        if (displays.isEmpty()) return
        val existingByKey = LinkedHashMap<String, StoredDisplayEntry>()
        for (entry in loadStoredEntries()) {
            existingByKey[entry.key] = entry
        }
        displays.forEachIndexed { idx, d ->
            val key = computeHistoryKey(d)
            existingByKey[key] = StoredDisplayEntry(
                key = key,
                name = d.name,
                widthPx = d.widthPx,
                heightPx = d.heightPx,
                densityDpi = d.densityDpi,
                physicalPpi = d.physicalPpi,
                qualityTier = d.qualityTier,
                lastSeenEpochMs = nowEpochMs + idx
            )
        }
        val capped = existingByKey.values
            .sortedByDescending { it.lastSeenEpochMs }
            .take(MAX_REMEMBERED_DISPLAYS)
        saveStoredEntries(capped)
    }

    fun getRememberedDisplays(connectedDisplays: List<DisplayProfile>): List<RememberedDisplayRecord> {
        val activeKeys = connectedDisplays.map { computeHistoryKey(it) }.toSet()
        return loadStoredEntries()
            .sortedByDescending { it.lastSeenEpochMs }
            .take(MAX_REMEMBERED_DISPLAYS)
            .map { entry ->
                RememberedDisplayRecord(
                    key = entry.key,
                    name = entry.name,
                    widthPx = entry.widthPx,
                    heightPx = entry.heightPx,
                    densityDpi = entry.densityDpi,
                    physicalPpi = entry.physicalPpi,
                    qualityTier = entry.qualityTier,
                    lastSeenEpochMs = entry.lastSeenEpochMs,
                    isCurrentlyConnected = entry.key in activeKeys
                )
            }
    }

    fun forgetDisplay(key: String) {
        val remaining = loadStoredEntries().filterNot { it.key == key }
        saveStoredEntries(remaining)
    }

    fun clearAll() {
        prefs.edit { clear() }
    }

    private data class StoredDisplayEntry(
        val key: String,
        val name: String,
        val widthPx: Int,
        val heightPx: Int,
        val densityDpi: Int,
        val physicalPpi: Double?,
        val qualityTier: DisplayQualityTier,
        val lastSeenEpochMs: Long
    )

    private fun loadStoredEntries(): List<StoredDisplayEntry> {
        val raw = prefs.getString(KEY_RECORDS_JSON, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val result = ArrayList<StoredDisplayEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val key = obj.optString("key", "")
                val name = obj.optString("name", "")
                val widthPx = obj.optInt("widthPx", 0)
                val heightPx = obj.optInt("heightPx", 0)
                val densityDpi = obj.optInt("densityDpi", 160)
                val ppi = if (obj.has("physicalPpi") && !obj.isNull("physicalPpi")) {
                    obj.optDouble("physicalPpi").takeIf { it.isFinite() && it > 0.0 }
                } else {
                    null
                }
                val tierName = obj.optString("qualityTier", DisplayQualityTier.STANDARD_100.name)
                val tier = runCatching { DisplayQualityTier.valueOf(tierName) }
                    .getOrDefault(DisplayQualityTier.STANDARD_100)
                val lastSeen = obj.optLong("lastSeenEpochMs", 0L)
                if (key.isNotBlank() && widthPx > 0 && heightPx > 0) {
                    result.add(
                        StoredDisplayEntry(
                            key = key,
                            name = name.ifBlank { "Display" },
                            widthPx = widthPx,
                            heightPx = heightPx,
                            densityDpi = densityDpi,
                            physicalPpi = ppi,
                            qualityTier = tier,
                            lastSeenEpochMs = lastSeen
                        )
                    )
                }
            }
            result
        }.getOrDefault(emptyList())
    }

    private fun saveStoredEntries(entries: List<StoredDisplayEntry>) {
        val arr = JSONArray()
        for (entry in entries) {
            val obj = JSONObject()
                .put("key", entry.key)
                .put("name", entry.name)
                .put("widthPx", entry.widthPx)
                .put("heightPx", entry.heightPx)
                .put("densityDpi", entry.densityDpi)
                .put("qualityTier", entry.qualityTier.name)
                .put("lastSeenEpochMs", entry.lastSeenEpochMs)
            if (entry.physicalPpi != null) {
                obj.put("physicalPpi", entry.physicalPpi)
            }
            arr.put(obj)
        }
        prefs.edit { putString(KEY_RECORDS_JSON, arr.toString()) }
    }
}
