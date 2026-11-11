package com.sunloom.app.labels

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import com.sunloom.app.R
import com.sunloom.app.data.sources.SourceRegistration
import com.sunloom.app.domain.model.SourceIds
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.DisplayPolicy
import com.sunloom.app.domain.model.DisplayQualityTier
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.LockWorkspacePolicy
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.SourceHealthState
import com.sunloom.app.domain.model.WallpaperStatus
import com.sunloom.app.ui.DesktopNavTab

/*
 * The words the person reads for each choice. The domain model keeps its English names as identifiers and for logs;
 * screens, messages and the platform adapter read these instead, so every label can be translated from strings.xml
 * (Constitution Article XII §1). `LabelsTest` keeps the English here equal to the model's.
 */

fun SafeCategory.title(r: Resources): String = r.getString(
    when (this) {
        SafeCategory.LANDSCAPES_NATURE -> R.string.category_name_landscapes_nature
        SafeCategory.SPACE_ASTRONOMY -> R.string.category_name_space_astronomy
        SafeCategory.ARCHITECTURE_CITYSCAPES -> R.string.category_name_architecture_cityscapes
        SafeCategory.ABSTRACT_PATTERNS -> R.string.category_name_abstract_patterns
        SafeCategory.SCENIC_FINE_ART -> R.string.category_name_scenic_fine_art
        SafeCategory.BOTANICAL_FLORA -> R.string.category_name_botanical_flora
        SafeCategory.AERIAL_EARTH -> R.string.category_name_aerial_earth
        SafeCategory.USER_LOCAL -> R.string.category_name_user_local
    }
)

@Composable
fun SafeCategory.title(): String = title(LocalResources.current)

fun SafeCategory.summary(r: Resources): String = r.getString(
    when (this) {
        SafeCategory.LANDSCAPES_NATURE -> R.string.category_desc_landscapes_nature
        SafeCategory.SPACE_ASTRONOMY -> R.string.category_desc_space_astronomy
        SafeCategory.ARCHITECTURE_CITYSCAPES -> R.string.category_desc_architecture_cityscapes
        SafeCategory.ABSTRACT_PATTERNS -> R.string.category_desc_abstract_patterns
        SafeCategory.SCENIC_FINE_ART -> R.string.category_desc_scenic_fine_art
        SafeCategory.BOTANICAL_FLORA -> R.string.category_desc_botanical_flora
        SafeCategory.AERIAL_EARTH -> R.string.category_desc_aerial_earth
        SafeCategory.USER_LOCAL -> R.string.category_desc_user_local
    }
)

@Composable
fun SafeCategory.summary(): String = summary(LocalResources.current)

fun LicenseType.title(r: Resources): String = r.getString(
    when (this) {
        LicenseType.PUBLIC_DOMAIN -> R.string.license_name_public_domain
        LicenseType.CC0 -> R.string.license_name_cc0
        LicenseType.CC_BY -> R.string.license_name_cc_by
        LicenseType.CC_BY_SA -> R.string.license_name_cc_by_sa
        LicenseType.USER_OWNED -> R.string.license_name_user_owned
        LicenseType.UNKNOWN_OR_RESTRICTED -> R.string.license_name_unknown_or_restricted
    }
)

@Composable
fun LicenseType.title(): String = title(LocalResources.current)

fun BrightnessClass.title(r: Resources): String = r.getString(
    when (this) {
        BrightnessClass.LIGHT -> R.string.brightness_name_light
        BrightnessClass.DARK -> R.string.brightness_name_dark
        BrightnessClass.NEUTRAL -> R.string.brightness_name_neutral
    }
)

@Composable
fun BrightnessClass.title(): String = title(LocalResources.current)

fun ScheduleMode.title(r: Resources): String = r.getString(
    when (this) {
        ScheduleMode.SUNRISE_SUNSET -> R.string.schedule_name_sunrise_sunset
        ScheduleMode.FOLLOW_OS_THEME -> R.string.schedule_name_follow_os_theme
        ScheduleMode.FIXED_CLOCK -> R.string.schedule_name_fixed_clock
        ScheduleMode.MANUAL_ONLY -> R.string.schedule_name_manual_only
    }
)

@Composable
fun ScheduleMode.title(): String = title(LocalResources.current)

fun ScheduleMode.summary(r: Resources): String = r.getString(
    when (this) {
        ScheduleMode.SUNRISE_SUNSET -> R.string.schedule_desc_sunrise_sunset
        ScheduleMode.FOLLOW_OS_THEME -> R.string.schedule_desc_follow_os_theme
        ScheduleMode.FIXED_CLOCK -> R.string.schedule_desc_fixed_clock
        ScheduleMode.MANUAL_ONLY -> R.string.schedule_desc_manual_only
    }
)

@Composable
fun ScheduleMode.summary(): String = summary(LocalResources.current)

fun DisplayPolicy.title(r: Resources): String = r.getString(
    when (this) {
        DisplayPolicy.SAME_ON_ALL_DISPLAYS -> R.string.policy_name_same_on_all_displays
        DisplayPolicy.OPTIMIZE_PRIMARY_SHARPEST -> R.string.policy_name_optimize_primary_sharpest
        DisplayPolicy.INDEPENDENT_PER_DISPLAY -> R.string.policy_name_independent_per_display
    }
)

@Composable
fun DisplayPolicy.title(): String = title(LocalResources.current)

fun LockWorkspacePolicy.title(r: Resources): String = r.getString(
    when (this) {
        LockWorkspacePolicy.SAME_ON_BOTH -> R.string.lock_name_same_on_both
        LockWorkspacePolicy.WORKSPACE_ONLY -> R.string.lock_name_workspace_only
        LockWorkspacePolicy.LOCK_ONLY -> R.string.lock_name_lock_only
    }
)

@Composable
fun LockWorkspacePolicy.title(): String = title(LocalResources.current)

fun WallpaperStatus.title(r: Resources): String = r.getString(
    when (this) {
        WallpaperStatus.DOWNLOADED -> R.string.status_name_downloaded
        WallpaperStatus.SAFETY_APPROVED -> R.string.status_name_safety_approved
        WallpaperStatus.PREPARED -> R.string.status_name_prepared
        WallpaperStatus.APPLIED_SUCCESSFULLY -> R.string.status_name_applied_successfully
        WallpaperStatus.WAITING_FOR_PERMISSION -> R.string.status_name_waiting_for_permission
        WallpaperStatus.UNSUPPORTED_BY_SYSTEM -> R.string.status_name_unsupported_by_system
        WallpaperStatus.FAILED_TEMPORARILY -> R.string.status_name_failed_temporarily
        WallpaperStatus.REJECTED_SAFETY_OR_QUALITY -> R.string.status_name_rejected_safety_or_quality
        WallpaperStatus.DISABLED_BY_USER -> R.string.status_name_disabled_by_user
    }
)

@Composable
fun WallpaperStatus.title(): String = title(LocalResources.current)

fun SourceHealthState.title(r: Resources): String = r.getString(
    when (this) {
        SourceHealthState.HEALTHY -> R.string.health_name_healthy
        SourceHealthState.DEGRADED -> R.string.health_name_degraded
        SourceHealthState.AUTO_DISABLED -> R.string.health_name_auto_disabled
        SourceHealthState.REVOKED -> R.string.health_name_revoked
    }
)

@Composable
fun SourceHealthState.title(): String = title(LocalResources.current)

fun DisplayQualityTier.title(r: Resources): String = r.getString(
    when (this) {
        DisplayQualityTier.STANDARD_100 -> R.string.tier_standard_100
        DisplayQualityTier.MID_80 -> R.string.tier_mid_80
        DisplayQualityTier.HIGH_65 -> R.string.tier_high_65
    }
)

@Composable
fun DisplayQualityTier.title(): String = title(LocalResources.current)

fun DesktopNavTab.title(r: Resources): String = r.getString(
    when (this) {
        DesktopNavTab.TODAY_CANVAS -> R.string.tab_today_canvas
        DesktopNavTab.RESERVE_AND_HISTORY -> R.string.tab_reserve_and_history
        DesktopNavTab.SOURCES_AND_CATEGORIES -> R.string.tab_sources_and_categories
        DesktopNavTab.DISPLAYS_AND_SCHEDULE -> R.string.tab_displays_and_schedule
        DesktopNavTab.SAFETY_AND_DECISION_LOG -> R.string.tab_safety_and_decision_log
    }
)

@Composable
fun DesktopNavTab.title(): String = title(LocalResources.current)

/** The name and one-line description of a source; a source this build does not know keeps its registry text. */
fun SourceRegistration.title(r: Resources): String =
    when (sourceId) {
        SourceIds.SEED_RESERVE -> r.getString(R.string.source_name_seed_reserve)
        "wikimedia_featured" -> r.getString(R.string.source_name_wikimedia_featured)
        "nasa_library" -> r.getString(R.string.source_name_nasa_library)
        "artic_cc0" -> r.getString(R.string.source_name_artic_cc0)
        "met_museum_cc0" -> r.getString(R.string.source_name_met_museum_cc0)
        "cma_cc0" -> r.getString(R.string.source_name_cma_cc0)
        "smk_open" -> r.getString(R.string.source_name_smk_open)
        SourceIds.USER_LOCAL -> r.getString(R.string.source_name_user_local)
        "openverse" -> r.getString(R.string.source_name_openverse)
        else -> sourceName
    }

fun SourceRegistration.summary(r: Resources): String =
    when (sourceId) {
        SourceIds.SEED_RESERVE -> r.getString(R.string.source_desc_seed_reserve)
        "wikimedia_featured" -> r.getString(R.string.source_desc_wikimedia_featured)
        "nasa_library" -> r.getString(R.string.source_desc_nasa_library)
        "artic_cc0" -> r.getString(R.string.source_desc_artic_cc0)
        "met_museum_cc0" -> r.getString(R.string.source_desc_met_museum_cc0)
        "cma_cc0" -> r.getString(R.string.source_desc_cma_cc0)
        "smk_open" -> r.getString(R.string.source_desc_smk_open)
        SourceIds.USER_LOCAL -> r.getString(R.string.source_desc_user_local)
        "openverse" -> r.getString(R.string.source_desc_openverse)
        else -> plainDescription
    }
