package com.sunloom.app.data.repository

import android.content.SharedPreferences
import androidx.core.content.edit
import com.sunloom.app.data.sources.SourceRegistry
import com.sunloom.app.domain.model.DisplayPolicy
import com.sunloom.app.domain.model.LockWorkspacePolicy
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.SourceIds
import com.sunloom.app.domain.model.UserPreferences

/**
 * The person's choices (Articles II, III, V, X §3), kept in the app's preferences file: reading them with safe
 * fallbacks, writing them, and the two one-time migrations. It knows nothing of the library; the repository does what
 * a change of preferences means for stored pictures.
 */
internal class PreferencesStore(private val prefs: SharedPreferences) {

    fun load(): UserPreferences {
        migrateLegacyCacheDefaultIfNeeded()
        val catsRaw = prefs.getStringSet("enabled_categories", null)
        val categories = if (catsRaw.isNullOrEmpty()) {
            UserPreferences().enabledCategories
        } else {
            catsRaw.mapNotNull { name -> SafeCategory.entries.firstOrNull { it.name == name } }.toSet()
        }.ifEmpty { UserPreferences().enabledCategories }

        val currentAdmittedIds = SourceRegistry.admittedSources().map { it.sourceId }.toSet()
        val sourcesRaw = prefs.getStringSet("enabled_sources", null)
        val sources = if (sourcesRaw.isNullOrEmpty()) {
            UserPreferences().enabledSources
        } else {
            val knownRaw = prefs.getStringSet("known_admitted_sources", null) ?: LEGACY_V1_3_SOURCES
            val newlyAdmitted = currentAdmittedIds - knownRaw
            if (newlyAdmitted.isNotEmpty()) {
                val merged = (sourcesRaw + newlyAdmitted).filter { SourceRegistry.isSourceAdmitted(it) }.toSet()
                prefs.edit {
                    putStringSet("enabled_sources", merged)
                    putStringSet("known_admitted_sources", currentAdmittedIds)
                }
                merged
            } else {
                sourcesRaw.filter { SourceRegistry.isSourceAdmitted(it) }.toSet()
            }
        }.ifEmpty { UserPreferences().enabledSources }

        return UserPreferences(
            isSetupCompleted = prefs.getBoolean("setup_completed", false),
            autoChangeEnabled = prefs.getBoolean("auto_change_enabled", true),
            isPaused = prefs.getBoolean("is_paused", false),
            scheduleMode = ScheduleMode.entries.firstOrNull {
                it.name == prefs.getString("schedule_mode", ScheduleMode.SUNRISE_SUNSET.name)
            } ?: ScheduleMode.SUNRISE_SUNSET,
            selectedCityName = prefs.getString("selected_city", "San Francisco, United States") ?: "San Francisco, United States",
            latitude = Double.fromBits(prefs.getLong("latitude_bits", (37.7749).toRawBits())),
            longitude = Double.fromBits(prefs.getLong("longitude_bits", (-122.4194).toRawBits())),
            fixedLightHour = prefs.getInt("fixed_light_hour", 7).coerceIn(0, 23),
            fixedDarkHour = prefs.getInt("fixed_dark_hour", 19).coerceIn(0, 23),
            rotationIntervalHours = prefs.getInt("rotation_interval_hours", 0).coerceIn(0, 24),
            enabledCategories = categories,
            enabledSources = sources,
            displayPolicy = DisplayPolicy.entries.firstOrNull {
                it.name == prefs.getString("display_policy", DisplayPolicy.SAME_ON_ALL_DISPLAYS.name)
            } ?: DisplayPolicy.SAME_ON_ALL_DISPLAYS,
            lockWorkspacePolicy = LockWorkspacePolicy.entries.firstOrNull {
                it.name == prefs.getString("lock_workspace_policy", LockWorkspacePolicy.SAME_ON_BOTH.name)
            } ?: LockWorkspacePolicy.SAME_ON_BOTH,
            allowBlurredFrameFill = prefs.getBoolean("allow_blurred_fill", false),
            wifiOnlyDownloads = prefs.getBoolean("wifi_only", false),
            deferOnBatterySaver = prefs.getBoolean("defer_battery_saver", true),
            maxCacheBytes = prefs.getLong("max_cache_bytes", UserPreferences.DEFAULT_MAX_CACHE_BYTES),
            baselineRestorePath = prefs.getString("baseline_restore_path", null),
            dryRunPreviewOnly = false
        )
    }

    /** The stored blurred-fill choice, read without loading everything. */
    fun allowsBlurredFill(): Boolean = prefs.getBoolean("allow_blurred_fill", false)

    /**
     * One-time (Article IX §3, v0.14): installs that still hold the old 250 MB default move to the new
     * 1 GB default. Any other stored value is a deliberate choice and is kept; once the flag is set a
     * later explicit choice of 250 MB is respected.
     */
    private fun migrateLegacyCacheDefaultIfNeeded() {
        if (prefs.getBoolean(KEY_CACHE_DEFAULT_MIGRATED, false)) return
        prefs.edit {
            if (prefs.getLong("max_cache_bytes", -1L) == UserPreferences.LEGACY_MAX_CACHE_BYTES) {
                putLong("max_cache_bytes", UserPreferences.DEFAULT_MAX_CACHE_BYTES)
            }
            putBoolean(KEY_CACHE_DEFAULT_MIGRATED, true)
        }
    }

    /** Stores [updated] and returns the enabled sources that are actually admitted (unknown ids are dropped). */
    fun save(updated: UserPreferences): Set<String> {
        val enabledAdmittedSources = updated.enabledSources.filter { SourceRegistry.isSourceAdmitted(it) }.toSet()
        prefs.edit {
            putBoolean("setup_completed", updated.isSetupCompleted)
            putBoolean("auto_change_enabled", updated.autoChangeEnabled)
            putBoolean("is_paused", updated.isPaused)
            putString("schedule_mode", updated.scheduleMode.name)
            putString("selected_city", updated.selectedCityName)
            putLong("latitude_bits", updated.latitude.toRawBits())
            putLong("longitude_bits", updated.longitude.toRawBits())
            putInt("fixed_light_hour", updated.fixedLightHour.coerceIn(0, 23))
            putInt("fixed_dark_hour", updated.fixedDarkHour.coerceIn(0, 23))
            remove("quiet_hours_enabled")
            remove("quiet_hours_start_hour")
            remove("quiet_hours_end_hour")
            putInt("rotation_interval_hours", updated.rotationIntervalHours.coerceIn(0, 24))
            putStringSet("enabled_categories", updated.enabledCategories.map { it.name }.toSet())
            putStringSet("enabled_sources", enabledAdmittedSources)
            putStringSet("known_admitted_sources", SourceRegistry.admittedSources().map { it.sourceId }.toSet())
            putString("display_policy", updated.displayPolicy.name)
            putString("lock_workspace_policy", updated.lockWorkspacePolicy.name)
            putBoolean("allow_blurred_fill", updated.allowBlurredFrameFill)
            putBoolean("wifi_only", updated.wifiOnlyDownloads)
            putBoolean("defer_battery_saver", updated.deferOnBatterySaver)
            putLong("max_cache_bytes", updated.maxCacheBytes)
            putString("baseline_restore_path", updated.baselineRestorePath)
            remove("dry_run_preview_only")
        }

        return enabledAdmittedSources
    }

    private companion object {
        private const val KEY_CACHE_DEFAULT_MIGRATED = "cache_default_migrated_v1"
        private val LEGACY_V1_3_SOURCES = setOf(
            SourceIds.SEED_RESERVE,
            "wikimedia_featured",
            "nasa_library",
            "artic_cc0",
            "met_museum_cc0",
            SourceIds.USER_LOCAL
        )
    }
}
