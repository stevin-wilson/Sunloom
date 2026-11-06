package com.sunloom.app.ui

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.sunloom.app.domain.model.LockWorkspacePolicy
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.UserPreferences

/**
 * Saves only the fields the first-run setup screen lets the user edit, so a half-finished setup
 * survives activity recreation (locale change, process death) without persisting anything to disk
 * before the user finishes (Article XII §1). All other fields come from [base].
 */
internal fun setupDraftSaver(base: UserPreferences): Saver<UserPreferences, Any> = listSaver(
    save = { p ->
        listOf(
            p.autoChangeEnabled,
            p.scheduleMode.name,
            p.selectedCityName,
            p.latitude,
            p.longitude,
            p.enabledCategories.joinToString(",") { it.name },
            p.lockWorkspacePolicy.name
        )
    },
    restore = { saved ->
        runCatching {
            val categories = (saved[5] as String).split(",")
                .filter { it.isNotBlank() }
                .mapNotNull { name -> SafeCategory.entries.firstOrNull { it.name == name } }
                .toSet()
            base.copy(
                autoChangeEnabled = saved[0] as Boolean,
                scheduleMode = ScheduleMode.valueOf(saved[1] as String),
                selectedCityName = saved[2] as String,
                latitude = saved[3] as Double,
                longitude = saved[4] as Double,
                // Never restore an empty category set; that would leave nothing to select from.
                enabledCategories = categories.ifEmpty { base.enabledCategories },
                lockWorkspacePolicy = LockWorkspacePolicy.valueOf(saved[6] as String)
            )
        }.getOrNull()
    }
)
