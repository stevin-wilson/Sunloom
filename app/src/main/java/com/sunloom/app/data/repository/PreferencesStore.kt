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

    /** What a save did: the enabled sources that are admitted, and whether the blurred-fill choice changed. */
    class SaveResult(val enabledSources: Set<String>, val blurredFillChanged: Boolean)

    fun load(): UserPreferences = synchronized(LOCK) { loadLocked() }

    private fun loadLocked(): UserPreferences {
        migrateLegacyCacheDefaultIfNeeded()
        val defaults = UserPreferences()
        val catsRaw = prefs.getStringSet("enabled_categories", null)
        val categories = if (catsRaw.isNullOrEmpty()) {
            defaults.enabledCategories
        } else {
            catsRaw.mapNotNull { name -> SafeCategory.entries.firstOrNull { it.name == name } }.toSet()
        }.ifEmpty { defaults.enabledCategories }

        val sources = loadEnabledSources(defaults)

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
            enabledCategories = categories,
            enabledSources = sources,
            displayPolicy = DisplayPolicy.entries.firstOrNull {
                it.name == prefs.getString("display_policy", DisplayPolicy.SAME_ON_ALL_DISPLAYS.name)
            } ?: DisplayPolicy.SAME_ON_ALL_DISPLAYS,
            lockWorkspacePolicy = LockWorkspacePolicy.entries.firstOrNull {
                it.name == prefs.getString("lock_workspace_policy", LockWorkspacePolicy.SAME_ON_BOTH.name)
            } ?: LockWorkspacePolicy.SAME_ON_BOTH,
            allowBlurredFrameFill = prefs.getBoolean("allow_blurred_fill", false),
            wifiOnlyDownloads = prefs.getBoolean("wifi_only", true),
            weeklyUpdateCheck = prefs.getBoolean("weekly_update_check", false),
            lastUpdateCheckEpochMs = prefs.getLong("last_update_check_ms", 0L),
            deferOnBatterySaver = prefs.getBoolean("defer_battery_saver", true),
            maxCacheBytes = prefs.getLong("max_cache_bytes", UserPreferences.DEFAULT_MAX_CACHE_BYTES),
            baselineRestorePath = prefs.getString("baseline_restore_path", null),
            changeEveryHours = UserPreferences.sanitizeChangeEveryHours(prefs.getInt("change_every_hours", 0)),
            dryRunPreviewOnly = false
        )
    }

    /**
     * One-time (Article IX §3, v0.14): installs that still hold the old 250 MB default move to the new
     * 1 GB default. Any other stored value is a deliberate choice and is kept; once the flag is set a
     * later explicit choice of 250 MB is respected.
     */
    private fun loadEnabledSources(defaults: UserPreferences): Set<String> {
        val sourcesRaw = prefs.getStringSet("enabled_sources", null)
        if (sourcesRaw.isNullOrEmpty()) return defaults.enabledSources

        val currentAdmittedIds = SourceRegistry.admittedSources().map { it.sourceId }.toSet()
        val known = prefs.getStringSet("known_admitted_sources", null) ?: LEGACY_V1_3_SOURCES
        val unseen = currentAdmittedIds - known
        val defaultOn = SourceRegistry.defaultEnabledSourceIds()
        // A source admitted since the last run joins only if it is on by default; an opt-in source waits to be chosen.
        var sources = (sourcesRaw + unseen.filter { it in defaultOn }).filter { SourceRegistry.isSourceAdmitted(it) }.toSet()

        // A source that moved from on-by-default to opt-in is switched off once; the person's later choice is kept.
        val applied = prefs.getStringSet(KEY_SWITCHED_OFF_APPLIED, null) ?: emptySet()
        val due = SourceRegistry.switchedOffOnceSourceIds() - applied
        sources = sources - due

        if (unseen.isNotEmpty() || due.isNotEmpty()) {
            prefs.edit {
                putStringSet("enabled_sources", sources)
                putStringSet("known_admitted_sources", currentAdmittedIds)
                putStringSet(KEY_SWITCHED_OFF_APPLIED, applied + due)
            }
        }
        return sources.ifEmpty { defaults.enabledSources }
    }

    private fun migrateLegacyCacheDefaultIfNeeded() {
        if (prefs.getBoolean(KEY_CACHE_DEFAULT_MIGRATED, false)) return
        prefs.edit {
            if (prefs.getLong("max_cache_bytes", -1L) == UserPreferences.LEGACY_MAX_CACHE_BYTES) {
                putLong("max_cache_bytes", UserPreferences.DEFAULT_MAX_CACHE_BYTES)
            }
            putBoolean(KEY_CACHE_DEFAULT_MIGRATED, true)
        }
    }

    /**
     * Stores [updated]. Unknown source ids are dropped. Whether the blurred-fill choice changed is decided here, in the
     * same step as the write, so two concurrent saves cannot both miss (or both report) the change.
     */
    fun save(updated: UserPreferences): SaveResult = synchronized(LOCK) { saveLocked(updated) }

    private fun saveLocked(updated: UserPreferences): SaveResult {
        val blurredFillChanged = prefs.getBoolean("allow_blurred_fill", false) != updated.allowBlurredFrameFill
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
            remove("rotation_interval_hours") // no longer used; drop what an older version stored
            putStringSet("enabled_categories", updated.enabledCategories.map { it.name }.toSet())
            putStringSet("enabled_sources", enabledAdmittedSources)
            putStringSet("known_admitted_sources", SourceRegistry.admittedSources().map { it.sourceId }.toSet())
            // A save is a deliberate choice of sources, so it settles every one-time switch-off.
            putStringSet(
                KEY_SWITCHED_OFF_APPLIED,
                (prefs.getStringSet(KEY_SWITCHED_OFF_APPLIED, null) ?: emptySet()) + SourceRegistry.switchedOffOnceSourceIds()
            )
            putString("display_policy", updated.displayPolicy.name)
            putString("lock_workspace_policy", updated.lockWorkspacePolicy.name)
            putBoolean("allow_blurred_fill", updated.allowBlurredFrameFill)
            putBoolean("wifi_only", updated.wifiOnlyDownloads)
            putBoolean("weekly_update_check", updated.weeklyUpdateCheck)
            putLong("last_update_check_ms", updated.lastUpdateCheckEpochMs)
            putBoolean("defer_battery_saver", updated.deferOnBatterySaver)
            putLong("max_cache_bytes", updated.maxCacheBytes)
            putString("baseline_restore_path", updated.baselineRestorePath)
            putInt("change_every_hours", UserPreferences.sanitizeChangeEveryHours(updated.changeEveryHours))
            remove("dry_run_preview_only")
            // A save is a deliberate choice, so it settles the one-time cache-default migration instead of leaving it
            // to override what was just saved.
            putBoolean(KEY_CACHE_DEFAULT_MIGRATED, true)
        }

        return SaveResult(enabledAdmittedSources, blurredFillChanged)
    }

    private companion object {
        /** One writer at a time across every repository in the process; each has its own store on the same file. */
        val LOCK = Any()
        private const val KEY_SWITCHED_OFF_APPLIED = "default_off_applied_sources"
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
