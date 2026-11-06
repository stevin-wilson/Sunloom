package com.sunloom.app.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sunloom.app.domain.model.DisplayPolicy
import com.sunloom.app.domain.model.LockWorkspacePolicy
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.solar.CityCoordinates
import kotlin.math.roundToInt

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
    onRestoreSystemDefault: () -> Unit,
    onReopenSetup: () -> Unit
) {
    val scrollState = rememberScrollState()
    var showCustomCoords by remember { mutableStateOf(false) }
    var customLatText by remember(uiState.preferences.latitude) {
        mutableStateOf("%.4f".format(uiState.preferences.latitude))
    }
    var customLonText by remember(uiState.preferences.longitude) {
        mutableStateOf("%.4f".format(uiState.preferences.longitude))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Schedule", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ScheduleMode.entries.forEach { mode ->
                        FilterChip(
                            selected = uiState.preferences.scheduleMode == mode,
                            onClick = { onUpdateScheduleMode(mode) },
                            label = { Text(mode.label) }
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
                        label = { Text("Custom coordinates") }
                    )
                    if (showCustomCoords) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = customLatText,
                                onValueChange = { customLatText = it },
                                label = { Text("Latitude (-90..90)") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .onKeyEvent {
                                        it.type == KeyEventType.KeyUp &&
                                            it.key in setOf(Key.N, Key.P, Key.K, Key.Backspace, Key.Delete)
                                    }
                                    .testTag("custom_lat_input")
                            )
                            OutlinedTextField(
                                value = customLonText,
                                onValueChange = { customLonText = it },
                                label = { Text("Longitude (-180..180)") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .onKeyEvent {
                                        it.type == KeyEventType.KeyUp &&
                                            it.key in setOf(Key.N, Key.P, Key.K, Key.Backspace, Key.Delete)
                                    }
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
                                modifier = Modifier.testTag("apply_custom_coords_button")
                            ) {
                                Text("Apply")
                            }
                        }
                    }
                }

                if (uiState.preferences.scheduleMode == ScheduleMode.FIXED_CLOCK) {
                    Text(
                        text = "Transition hours (Daytime ${uiState.preferences.fixedLightHour}:00 • Evening ${uiState.preferences.fixedDarkHour}:00)",
                        style = MaterialTheme.typography.labelLarge
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(6 to 18, 7 to 19, 8 to 20, 9 to 21, 20 to 6).forEach { (lHour, dHour) ->
                            FilterChip(
                                selected = uiState.preferences.fixedLightHour == lHour && uiState.preferences.fixedDarkHour == dHour,
                                onClick = { onUpdateFixedHours(lHour, dHour) },
                                label = { Text("${lHour}:00 / ${dHour}:00") }
                            )
                        }
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Display & Preferences", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("connected_displays_summary"),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    uiState.capabilities.connectedDisplays.forEach { disp ->
                        val roleTag = if (disp.isPrimary) "Built-in" else "External"
                        val ppiLabel = disp.physicalPpi?.let { "${it.roundToInt()} PPI" } ?: "${disp.densityDpi} dpi"
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
                    Text("Multi-display fitting mode", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            DisplayPolicy.SAME_ON_ALL_DISPLAYS to "Shared envelope across displays",
                            DisplayPolicy.OPTIMIZE_PRIMARY_SHARPEST to "Optimize sharpest display only"
                        ).forEach { (policy, shortText) ->
                            FilterChip(
                                selected = uiState.preferences.displayPolicy == policy,
                                onClick = { onUpdateDisplayPolicy(policy) },
                                label = { Text(shortText) }
                            )
                        }
                    }
                }

                val disconnectedHistory = uiState.capabilities.rememberedDisplays.filter { !it.isCurrentlyConnected }
                if (disconnectedHistory.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Text(
                        text = "Remembered External Displays (disconnected — never constrain active crops)",
                        style = MaterialTheme.typography.labelLarge
                    )
                    disconnectedHistory.forEach { rec ->
                        val recPpi = rec.physicalPpi?.let { "${it.roundToInt()} PPI" } ?: "${rec.densityDpi} dpi"
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
                                modifier = Modifier.testTag("forget_display_${rec.key}")
                            ) {
                                Text("Forget")
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Text("Apply wallpaper to", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LockWorkspacePolicy.entries.forEach { policy ->
                        FilterChip(
                            selected = uiState.preferences.lockWorkspacePolicy == policy,
                            onClick = { onUpdateLockPolicy(policy) },
                            label = { Text(policy.label) }
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
                        Text("Blurred frame fill for smaller images", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Frame smaller artworks with a darkened blurred backdrop instead of rejecting them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.preferences.allowBlurredFrameFill,
                        onCheckedChange = onToggleBlurredFill
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Pause downloads on Battery Saver", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Defer background catalog refreshes when Battery Saver is active.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.preferences.deferOnBatterySaver,
                        onCheckedChange = onToggleBatterySaverDeferral,
                        modifier = Modifier.testTag("defer_battery_saver_switch")
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onReopenSetup) {
                        Text("Setup Guide")
                    }
                    OutlinedButton(
                        onClick = onRestoreSystemDefault,
                        modifier = Modifier.testTag("restore_system_default_button")
                    ) {
                        Icon(SunloomIcons.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Reset to System Default")
                    }
                }
            }
        }
    }
}
