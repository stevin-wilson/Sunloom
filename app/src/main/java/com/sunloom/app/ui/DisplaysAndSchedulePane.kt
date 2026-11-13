package com.sunloom.app.ui

import com.sunloom.app.labels.summary
import com.sunloom.app.labels.title
import androidx.compose.ui.platform.LocalResources
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import com.sunloom.app.R
import androidx.compose.ui.res.pluralStringResource
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.sunloom.app.domain.matcher.ChosenSlot
import com.sunloom.app.domain.matcher.ChosenWallpaper
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.model.DisplayPolicy
import com.sunloom.app.domain.model.LockWorkspacePolicy
import com.sunloom.app.domain.model.RememberedDisplayRecord
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

/**
 * Why "Change every" cannot be used right now, or null when it can. Disabled with a reason, rather than hidden, so
 * the page does not jump around and the person learns what to switch on.
 */
internal fun changeEveryUnavailableNote(prefs: UserPreferences): Int? = when {
    !prefs.autoChangeEnabled -> R.string.change_every_needs_automatic
    prefs.scheduleMode == ScheduleMode.MANUAL_ONLY -> R.string.change_every_needs_schedule
    else -> null
}

/** The "Change every" choices: extra changes inside each daytime or evening period (Article III §1, v0.58). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChangeEverySection(prefs: UserPreferences, onUpdateChangeEvery: (Int) -> Unit) {
    val unavailable = changeEveryUnavailableNote(prefs)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(stringResource(R.string.change_every_title), style = MaterialTheme.typography.labelLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.s)
        ) {
            UserPreferences.CHANGE_EVERY_CHOICES_HOURS.forEach { hours ->
                FilterChip(
                    selected = prefs.changeEveryHours == hours,
                    enabled = unavailable == null,
                    onClick = { onUpdateChangeEvery(hours) },
                    label = {
                        Text(
                            if (hours == 0) {
                                stringResource(R.string.change_every_off)
                            } else {
                                pluralStringResource(R.plurals.change_every_hours, hours, hours)
                            }
                        )
                    },
                    modifier = Modifier.desktopControl(MaterialTheme.shapes.small).testTag("change_every_$hours")
                )
            }
        }
        if (unavailable != null) {
            Text(
                text = stringResource(unavailable),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("change_every_note")
            )
        } else {
            Text(
                text = if (prefs.changeEveryHours == 0) {
                    stringResource(R.string.change_every_hint_off)
                } else {
                    pluralStringResource(R.plurals.change_every_hint_on, prefs.changeEveryHours, prefs.changeEveryHours)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("change_every_hint")
            )
        }
    }
}

/** What a Daytime or Evening card shows: nothing chosen, a picture that can be shown, or one that cannot right now. */
internal sealed interface ChosenCardState {
    data object Automatic : ChosenCardState
    data class Chosen(val record: WallpaperRecord) : ChosenCardState
    data object Unavailable : ChosenCardState
}

internal fun chosenCardState(
    prefs: UserPreferences,
    slot: ChosenSlot,
    records: List<WallpaperRecord>,
    fileExists: (String) -> Boolean
): ChosenCardState {
    val id = slot.pickOf(prefs)?.takeIf { it.isNotBlank() } ?: return ChosenCardState.Automatic
    val record = records.firstOrNull { it.id == id }
    return if (record != null && ChosenWallpaper.isUsable(record, fileExists)) {
        ChosenCardState.Chosen(record)
    } else {
        ChosenCardState.Unavailable
    }
}

/** When a slot's picture is shown, in the words of the current schedule; null when the schedule is Manual. */
internal fun chosenWhenNote(prefs: UserPreferences, slot: ChosenSlot): Int? = when (prefs.scheduleMode) {
    ScheduleMode.SUNRISE_SUNSET ->
        if (slot == ChosenSlot.DAYTIME) R.string.chosen_when_daytime_sun else R.string.chosen_when_evening_sun
    ScheduleMode.FOLLOW_OS_THEME ->
        if (slot == ChosenSlot.DAYTIME) R.string.chosen_when_daytime_os else R.string.chosen_when_evening_os
    ScheduleMode.FIXED_CLOCK ->
        if (slot == ChosenSlot.DAYTIME) R.string.chosen_when_daytime_clock else R.string.chosen_when_evening_clock
    ScheduleMode.MANUAL_ONLY -> null
}

/**
 * The Daytime and Evening wallpaper cards (Article III §1, v0.59). A picture is chosen from its own menu; the card
 * shows the choice, when it is used, and a Clear button. It never hides: when it cannot work it says why.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChosenWallpaperSection(uiState: SunloomUiState, onSetChosen: (ChosenSlot, String?) -> Unit) {
    val prefs = uiState.preferences
    val unavailable = changeEveryUnavailableNote(prefs)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        ChosenSlot.entries.forEach { slot ->
            val key = if (slot == ChosenSlot.DAYTIME) "daytime" else "evening"
            val title = stringResource(if (slot == ChosenSlot.DAYTIME) R.string.chosen_daytime_title else R.string.chosen_evening_title)
            // Remembered: it stats a file, which must not repeat on every recomposition of the pane.
            val state = remember(slot.pickOf(prefs), uiState.libraryWallpapers) {
                chosenCardState(prefs, slot, uiState.libraryWallpapers) { java.io.File(it).exists() }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
                itemVerticalAlignment = Alignment.CenterVertically
            ) {
                if (state is ChosenCardState.Chosen) {
                    WallpaperPreviewSurface(
                        record = state.record,
                        showLegibilityZones = false,
                        modifier = Modifier.width(96.dp).aspectRatio(16f / 10f),
                        maxDecodeWidth = 320,
                        maxDecodeHeight = 320
                    )
                }
                Column(modifier = Modifier.widthIn(min = 160.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.sectionHeading().testTag("chosen_title_$key")
                    )
                    Text(
                        text = when (state) {
                            ChosenCardState.Automatic -> stringResource(R.string.chosen_automatic)
                            is ChosenCardState.Chosen -> state.record.title
                            ChosenCardState.Unavailable -> stringResource(R.string.chosen_unavailable)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("chosen_state_$key")
                    )
                    chosenWhenNote(prefs, slot)?.let {
                        Text(
                            stringResource(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("chosen_when_$key")
                        )
                    }
                }
                if (state != ChosenCardState.Automatic) {
                    val clearLabel = stringResource(R.string.chosen_clear_description, title)
                    TextButton(
                        onClick = { onSetChosen(slot, null) },
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .desktopControl(MaterialTheme.shapes.small)
                            .semantics { contentDescription = clearLabel }
                            .testTag("chosen_clear_$key")
                    ) { Text(stringResource(R.string.chosen_clear)) }
                }
            }
        }
        if (unavailable != null) {
            Text(
                text = stringResource(unavailable),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("chosen_note")
            )
        }
        Text(
            text = stringResource(R.string.chosen_how_to_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.chosen_small_picture_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("chosen_small_picture_hint")
        )
    }
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
    onUpdateChangeEvery: (hours: Int) -> Unit = {},
    onSetChosenWallpaper: (ChosenSlot, String?) -> Unit = { _, _ -> },
    onUpdateCustomCoordinates: (lat: Double, lon: Double, label: String) -> Unit,
    onUpdateDisplayPolicy: (DisplayPolicy) -> Unit = {},
    onForgetRememberedDisplay: (String) -> Unit = {},
    onToggleAutoSwitch: (Boolean) -> Unit = {},
    onRestoreSystemDefault: () -> Unit,
    onReopenSetup: () -> Unit
) {
    val scrollState = rememberScrollState()
    var showResetConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .desktopScrollbar(scrollState)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(Spacing.m)
    ) {
        ScheduleCard(
            uiState = uiState,
            onToggleAutoSwitch = onToggleAutoSwitch,
            onUpdateScheduleMode = onUpdateScheduleMode,
            onSelectCity = onSelectCity,
            onUpdateFixedHours = onUpdateFixedHours,
            onUpdateChangeEvery = onUpdateChangeEvery,
            onSetChosenWallpaper = onSetChosenWallpaper,
            onUpdateCustomCoordinates = onUpdateCustomCoordinates
        )
        DisplaysCard(
            uiState = uiState,
            onUpdateLockPolicy = onUpdateLockPolicy,
            onToggleBlurredFill = onToggleBlurredFill,
            onUpdateDisplayPolicy = onUpdateDisplayPolicy,
            onForgetRememberedDisplay = onForgetRememberedDisplay,
            onReopenSetup = onReopenSetup,
            onRequestSystemReset = { showResetConfirm = true }
        )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleCard(
    uiState: SunloomUiState,
    onToggleAutoSwitch: (Boolean) -> Unit,
    onUpdateScheduleMode: (ScheduleMode) -> Unit,
    onSelectCity: (CityCoordinates) -> Unit,
    onUpdateFixedHours: (lightHour: Int, darkHour: Int) -> Unit,
    onUpdateChangeEvery: (hours: Int) -> Unit,
    onSetChosenWallpaper: (ChosenSlot, String?) -> Unit,
    onUpdateCustomCoordinates: (lat: Double, lon: Double, label: String) -> Unit
) {
    var showCustomCoords by remember { mutableStateOf(false) }
    var customLatText by remember(uiState.preferences.latitude) {
        mutableStateOf("%.4f".format(uiState.preferences.latitude))
    }
    var customLonText by remember(uiState.preferences.longitude) {
        mutableStateOf("%.4f".format(uiState.preferences.longitude))
    }
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
                        label = { Text(mode.title()) }, modifier = Modifier.desktopControl(MaterialTheme.shapes.small)
                    )
                }
            }
            Text(
                text = uiState.preferences.scheduleMode.summary(),
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
                    CustomCoordinatesFields(
                        latText = customLatText,
                        lonText = customLonText,
                        onLatChange = { customLatText = it },
                        onLonChange = { customLonText = it },
                        onApply = onUpdateCustomCoordinates
                    )
                }
            }

            if (uiState.preferences.scheduleMode == ScheduleMode.FIXED_CLOCK) {
                Text(
                    text = stringResource(
                        R.string.transition_hours,
                        uiState.preferences.fixedLightHour,
                        uiState.preferences.fixedDarkHour
                    ),
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

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            ChangeEverySection(
                prefs = uiState.preferences,
                onUpdateChangeEvery = onUpdateChangeEvery
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            ChosenWallpaperSection(uiState = uiState, onSetChosen = onSetChosenWallpaper)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DisplaysCard(
    uiState: SunloomUiState,
    onUpdateLockPolicy: (LockWorkspacePolicy) -> Unit,
    onToggleBlurredFill: (Boolean) -> Unit,
    onUpdateDisplayPolicy: (DisplayPolicy) -> Unit,
    onForgetRememberedDisplay: (String) -> Unit,
    onReopenSetup: () -> Unit,
    onRequestSystemReset: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Text(stringResource(R.string.display_preferences), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.sectionHeading())
            ConnectedDisplaysSummary(uiState)

            if (uiState.capabilities.connectedDisplays.size > 1) {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Text(stringResource(R.string.multi_display_fitting_mode), style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s)
                ) {
                    listOf(
                        DisplayPolicy.SAME_ON_ALL_DISPLAYS to stringResource(R.string.policy_short_same_on_all),
                        DisplayPolicy.OPTIMIZE_PRIMARY_SHARPEST to stringResource(R.string.policy_short_sharpest)
                    ).forEach { (policy, shortText) ->
                        FilterChip(
                            selected = uiState.preferences.displayPolicy == policy,
                            onClick = { onUpdateDisplayPolicy(policy) },
                            label = { Text(shortText) }, modifier = Modifier.desktopControl(MaterialTheme.shapes.small)
                        )
                    }
                }
            }

            RememberedDisplaysSection(uiState.capabilities.rememberedDisplays, onForgetRememberedDisplay)
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
                        label = { Text(policy.title()) }, modifier = Modifier.desktopControl(MaterialTheme.shapes.small)
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
                    onClick = onRequestSystemReset,
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

@Composable
private fun ConnectedDisplaysSummary(uiState: SunloomUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("connected_displays_summary"),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        uiState.capabilities.connectedDisplays.forEach { disp ->
            val roleTag = stringResource(if (disp.isPrimary) R.string.display_role_built_in else R.string.display_role_external)
            val ppiLabel = disp.physicalPpi?.let { stringResource(R.string.pixels_per_inch, it.roundToInt().toString()) }
                ?: stringResource(R.string.dpi_density, disp.densityDpi.toString())
            Text(
                text = stringResource(
                    R.string.display_line, roleTag, disp.name, disp.resolutionLabel,
                    "%.2f".format(disp.aspectRatio), ppiLabel, disp.qualityTier.title()
                ),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = uiState.capabilities.capabilityDisclosureNote.ifBlank { stringResource(R.string.probing_displays) },
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
}

@Composable
private fun RememberedDisplaysSection(
    rememberedDisplays: List<RememberedDisplayRecord>,
    onForgetRememberedDisplay: (String) -> Unit
) {
    val disconnectedHistory = rememberedDisplays.filter { !it.isCurrentlyConnected }
    if (disconnectedHistory.isNotEmpty()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Text(
            text = stringResource(R.string.remembered_external_displays_disconnecte),
            style = MaterialTheme.typography.labelLarge
        )
        disconnectedHistory.forEach { rec ->
            val recPpi = rec.physicalPpi?.let { stringResource(R.string.pixels_per_inch, it.roundToInt().toString()) }
                ?: stringResource(R.string.dpi_density, rec.densityDpi.toString())
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomCoordinatesFields(
    latText: String,
    lonText: String,
    onLatChange: (String) -> Unit,
    onLonChange: (String) -> Unit,
    onApply: (lat: Double, lon: Double, label: String) -> Unit
) {
    val resources = LocalResources.current
    val latError = coordinateError(latText, -90.0, 90.0)
    val lonError = coordinateError(lonText, -180.0, 180.0)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
        itemVerticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = latText,
            onValueChange = onLatChange,
            label = { Text(stringResource(R.string.latitude_90_90)) },
            singleLine = true,
            isError = latError != null,
            supportingText = { latError?.let { Text(it.message(resources)) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier
                .widthIn(min = 140.dp)
                .weight(1f)
                .suppressShortcutsWhileFocused()
                .testTag("custom_lat_input")
        )
        OutlinedTextField(
            value = lonText,
            onValueChange = onLonChange,
            label = { Text(stringResource(R.string.longitude_180_180)) },
            singleLine = true,
            isError = lonError != null,
            supportingText = { lonError?.let { Text(it.message(resources)) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier
                .widthIn(min = 140.dp)
                .weight(1f)
                .suppressShortcutsWhileFocused()
                .testTag("custom_lon_input")
        )
        OutlinedButton(
            onClick = {
                val lat = latText.toDoubleOrNull()
                val lon = lonText.toDoubleOrNull()
                if (lat != null && lon != null) {
                    // Stored as the city name, not shown as text.
                    val name = "Custom (${"%.2f".format(lat)}, ${"%.2f".format(lon)})"
                    onApply(lat, lon, name)
                }
            },
            enabled = latError == null && lonError == null &&
                latText.isNotBlank() && lonText.isNotBlank(),
            modifier = Modifier.desktopControl(CircleShape).testTag("apply_custom_coords_button")
        ) {
            Text(stringResource(R.string.apply))
        }
    }
}

/**
 * Validation message for a coordinate text field, or null when [text] is empty (not yet typed) or a
 * number within [min]..[max]. Shown inline so bad input is explained instead of silently ignored.
 */
internal fun coordinateError(text: String, min: Double, max: Double): CoordinateError? {
    if (text.isBlank()) return null
    val value = text.trim().toDoubleOrNull()
        ?: return CoordinateError.NotANumber
    if (!value.isFinite() || value < min || value > max) {
        return CoordinateError.OutOfRange(min.toInt(), max.toInt())
    }
    return null
}

/** Why a typed coordinate is not accepted; the words come from strings.xml. */
internal sealed interface CoordinateError {
    data object NotANumber : CoordinateError
    data class OutOfRange(val min: Int, val max: Int) : CoordinateError

    fun message(r: android.content.res.Resources): String = when (this) {
        NotANumber -> r.getString(R.string.coordinate_not_a_number)
        is OutOfRange -> r.getString(R.string.coordinate_out_of_range, min.toString(), max.toString())
    }
}
