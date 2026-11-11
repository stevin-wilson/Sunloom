package com.sunloom.app.ui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import com.sunloom.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sunloom.app.domain.model.DisplayPolicy
import com.sunloom.app.domain.model.LockWorkspacePolicy
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.solar.CityCoordinates
import kotlin.math.roundToInt

/**
 * Why a switched-on "Automatic switching" will still not change anything, or null when it really runs. Saying so
 * beats leaving the switch looking broken.
 */
internal fun automaticSwitchingNote(prefs: UserPreferences): Int? = when {
    !prefs.autoChangeEnabled -> null
    prefs.isPaused -> R.string.automatic_switching_paused_note
    prefs.scheduleMode == ScheduleMode.MANUAL_ONLY -> R.string.automatic_switching_manual_note
    else -> null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DisplaysAndSchedulePane(
    uiState: SunloomUiState,
    onSelectCity: (CityCoordinates) -> Unit,
    onUpdateScheduleMode: (ScheduleMode) -> Unit,
    onUpdateLockPolicy: (LockWorkspacePolicy) -> Unit,
    onToggleBlurredFill: (Boolean) -> Unit,
    onUpdateFixedHours: (lightHour: Int, darkHour: Int) -> Unit,
    onUpdateCustomCoordinates: (lat: Double, lon: Double, label: String) -> Unit,
    onToggleBatterySaverDeferral: (Boolean) -> Unit,
    onUpdateDisplayPolicy: (DisplayPolicy) -> Unit = {},
    onForgetRememberedDisplay: (String) -> Unit = {},
    onToggleAutoSwitch: (Boolean) -> Unit = {},
    onRestoreSystemDefault: () -> Unit,
    onReopenSetup: () -> Unit
) {
    val scrollState = rememberScrollState()
    var showCustomCoords by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var customLatText by remember(uiState.preferences.latitude) {
        mutableStateOf("%.4f".format(uiState.preferences.latitude))
    }
    var customLonText by remember(uiState.preferences.longitude) {
        mutableStateOf("%.4f".format(uiState.preferences.longitude))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .desktopScrollbar(scrollState)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(Spacing.m)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Text(stringResource(R.string.schedule), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.sectionHeading())
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.automatic_switching),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            stringResource(R.string.automatic_switching_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val note = automaticSwitchingNote(uiState.preferences)
                        if (note != null) {
                            Text(
                                stringResource(note),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    Switch(
                        checked = uiState.preferences.autoChangeEnabled,
                        onCheckedChange = onToggleAutoSwitch,
                        modifier = Modifier
                            .desktopControl(CircleShape)
                            .switchLabel(stringResource(R.string.automatic_switching))
                            .testTag("auto_switch")
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s)
                ) {
                    ScheduleMode.entries.forEach { mode ->
                        FilterChip(
                            selected = uiState.preferences.scheduleMode == mode,
                            onClick = { onUpdateScheduleMode(mode) },
                            label = { Text(mode.label) }, modifier = Modifier.desktopControl(MaterialTheme.shapes.small)
                        )
                    }
                }
                Text(
                    text = uiState.preferences.scheduleMode.plainDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (uiState.preferences.scheduleMode == ScheduleMode.SUNRISE_SUNSET) {
                    CityDropdownSelector(
                        selectedCityName = uiState.preferences.selectedCityName,
                        onSelectCity = onSelectCity
                    )
                    FilterChip(
                        selected = showCustomCoords,
                        onClick = { showCustomCoords = !showCustomCoords },
                        label = { Text(stringResource(R.string.custom_coordinates)) }, modifier = Modifier.desktopControl(MaterialTheme.shapes.small)
                    )
                    if (showCustomCoords) {
                        val latError = coordinateError(customLatText, -90.0, 90.0)
                        val lonError = coordinateError(customLonText, -180.0, 180.0)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                            verticalArrangement = Arrangement.spacedBy(Spacing.s),
                            itemVerticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = customLatText,
                                onValueChange = { customLatText = it },
                                label = { Text(stringResource(R.string.latitude_90_90)) },
                                singleLine = true,
                                isError = latError != null,
                                supportingText = { latError?.let { Text(it) } },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier
                                    .widthIn(min = 140.dp)
                                    .weight(1f)
                                    .suppressShortcutsWhileFocused()
                                    .testTag("custom_lat_input")
                            )
                            OutlinedTextField(
                                value = customLonText,
                                onValueChange = { customLonText = it },
                                label = { Text(stringResource(R.string.longitude_180_180)) },
                                singleLine = true,
                                isError = lonError != null,
                                supportingText = { lonError?.let { Text(it) } },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier
                                    .widthIn(min = 140.dp)
                                    .weight(1f)
                                    .suppressShortcutsWhileFocused()
                                    .testTag("custom_lon_input")
                            )
                            OutlinedButton(
                                onClick = {
                                    val lat = customLatText.toDoubleOrNull()
                                    val lon = customLonText.toDoubleOrNull()
                                    if (lat != null && lon != null) {
                                        onUpdateCustomCoordinates(lat, lon, "Custom (${"%.2f".format(lat)}, ${"%.2f".format(lon)})")
                                    }
                                },
                                enabled = latError == null && lonError == null &&
                                    customLatText.isNotBlank() && customLonText.isNotBlank(),
                                modifier = Modifier.desktopControl(CircleShape).testTag("apply_custom_coords_button")
                            ) {
                                Text(stringResource(R.string.apply))
                            }
                        }
                    }
                }

                if (uiState.preferences.scheduleMode == ScheduleMode.FIXED_CLOCK) {
                    Text(
                        text = "Transition hours (Daytime ${uiState.preferences.fixedLightHour}:00 • Evening ${uiState.preferences.fixedDarkHour}:00)",
                        style = MaterialTheme.typography.labelLarge
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        listOf(6 to 18, 7 to 19, 8 to 20, 9 to 21, 20 to 6).forEach { (lHour, dHour) ->
                            FilterChip(
                                selected = uiState.preferences.fixedLightHour == lHour && uiState.preferences.fixedDarkHour == dHour,
                                onClick = { onUpdateFixedHours(lHour, dHour) },
                                label = { Text("${lHour}:00 / ${dHour}:00") },
                                modifier = Modifier.desktopControl(MaterialTheme.shapes.small)
                            )
                        }
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Text(stringResource(R.string.display_preferences), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.sectionHeading())
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("connected_displays_summary"),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    uiState.capabilities.connectedDisplays.forEach { disp ->
                        val roleTag = if (disp.isPrimary) "Built-in" else "External"
                        val ppiLabel = disp.physicalPpi?.let { "${it.roundToInt()} pixels per inch" } ?: "${disp.densityDpi} dpi"
                        Text(
                            text = "$roleTag: ${disp.name} (${disp.resolutionLabel}, ${"%.2f".format(disp.aspectRatio)}:1 • $ppiLabel • ${disp.qualityTier.shortLabel})",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = uiState.capabilities.capabilityDisclosureNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (uiState.capabilities.excludedDisplaysNote.isNotBlank()) {
                        Text(
                            text = uiState.capabilities.excludedDisplaysNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                if (uiState.capabilities.connectedDisplays.size > 1) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Text(stringResource(R.string.multi_display_fitting_mode), style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                        verticalArrangement = Arrangement.spacedBy(Spacing.s)
                    ) {
                        listOf(
                            DisplayPolicy.SAME_ON_ALL_DISPLAYS to "One wallpaper for all displays",
                            DisplayPolicy.OPTIMIZE_PRIMARY_SHARPEST to "Best for the sharpest display"
                        ).forEach { (policy, shortText) ->
                            FilterChip(
                                selected = uiState.preferences.displayPolicy == policy,
                                onClick = { onUpdateDisplayPolicy(policy) },
                                label = { Text(shortText) }, modifier = Modifier.desktopControl(MaterialTheme.shapes.small)
                            )
                        }
                    }
                }

                val disconnectedHistory = uiState.capabilities.rememberedDisplays.filter { !it.isCurrentlyConnected }
                if (disconnectedHistory.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Text(
                        text = stringResource(R.string.remembered_external_displays_disconnecte),
                        style = MaterialTheme.typography.labelLarge
                    )
                    disconnectedHistory.forEach { rec ->
                        val recPpi = rec.physicalPpi?.let { "${it.roundToInt()} pixels per inch" } ?: "${rec.densityDpi} dpi"
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${rec.name} (${rec.resolutionLabel} • $recPpi)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { onForgetRememberedDisplay(rec.key) },
                                modifier = Modifier.desktopControl(CircleShape).testTag("forget_display_${rec.key}")
                            ) {
                                Text(stringResource(R.string.forget))
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Text(stringResource(R.string.apply_wallpaper_to), style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s)
                ) {
                    LockWorkspacePolicy.entries.forEach { policy ->
                        FilterChip(
                            selected = uiState.preferences.lockWorkspacePolicy == policy,
                            onClick = { onUpdateLockPolicy(policy) },
                            label = { Text(policy.label) }, modifier = Modifier.desktopControl(MaterialTheme.shapes.small)
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.blurred_frame_fill_for_smaller_images), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.frame_smaller_artworks_with_a_darkened_b),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.preferences.allowBlurredFrameFill,
                        onCheckedChange = onToggleBlurredFill, modifier = Modifier.desktopControl(CircleShape).switchLabel(stringResource(R.string.blurred_frame_fill_for_smaller_images))
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s)
                ) {
                    OutlinedButton(onClick = onReopenSetup, modifier = Modifier.desktopControl(CircleShape)) {
                        Text(stringResource(R.string.setup_guide))
                    }
                    OutlinedButton(
                        onClick = { showResetConfirm = true },
                        modifier = Modifier.desktopControl(CircleShape).testTag("restore_system_default_button")
                    ) {
                        Icon(SunloomIcons.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.reset_to_system_default))
                    }
                }
            }
        }
    }

    if (showResetConfirm) {
        ConfirmDestructiveDialog(
            title = stringResource(R.string.reset_to_system_default_2),
            message = stringResource(R.string.sunloom_will_put_your_device_s_original),
            confirmLabel = stringResource(R.string.reset),
            confirmTestTag = "confirm_reset_default_button",
            onConfirm = {
                showResetConfirm = false
                onRestoreSystemDefault()
            },
            onDismiss = { showResetConfirm = false }
        )
    }
}

/**
 * Validation message for a coordinate text field, or null when [text] is empty (not yet typed) or a
 * number within [min]..[max]. Shown inline so bad input is explained instead of silently ignored.
 */
internal fun coordinateError(text: String, min: Double, max: Double): String? {
    if (text.isBlank()) return null
    val value = text.trim().toDoubleOrNull()
        ?: return "Enter a number, e.g. 37.77"
    if (!value.isFinite() || value < min || value > max) {
        return "Must be between ${min.toInt()} and ${max.toInt()}"
    }
    return null
}
