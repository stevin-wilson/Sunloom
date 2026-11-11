package com.sunloom.app.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sunloom.app.R
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.LockWorkspacePolicy
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.solar.CityGuess
import com.sunloom.app.update.updateCheckFeature
import java.time.Instant

/**
 * First-run setup (Article XII §1): four short steps with a visible "Step N of 4", plain words about what the app
 * does,
 * and a first step that can finish everything with the defaults. A city is guessed from the time zone so the person is
 * not asked for a location. Reopening setup from Schedule starts from the person's own choices instead.
 */
@Composable
fun FirstRunSetupScreen(
    uiState: SunloomUiState,
    onCompleteSetup: (UserPreferences) -> Unit
) {
    val zone = LocalDeviceZone.current
    val start = remember(uiState.preferences, zone) { withGuessedCity(uiState.preferences, zone) }
    val cityWasGuessed = start.selectedCityName != uiState.preferences.selectedCityName
    var draftPrefs by rememberSaveable(
        uiState.preferences,
        stateSaver = setupDraftSaver(start)
    ) { mutableStateOf(start) }
    var step by rememberSaveable { mutableStateOf(SetupStep.WELCOME) }
    BackHandler(enabled = !step.isFirst) { step = step.back() ?: step }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .desktopScrollbar(scrollState)
            .verticalScroll(scrollState)
            // Keep setup a readable column on ultra-wide windows (UI_Secondary_Elements).
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = 760.dp)
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.l)
    ) {
        SetupHeader(step)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                when (step) {
                    SetupStep.WELCOME -> WelcomeStep()
                    SetupStep.SCHEDULE -> ScheduleStep(draftPrefs, cityWasGuessed) { draftPrefs = it }
                    SetupStep.CONTENT -> ContentStep(draftPrefs) { draftPrefs = it }
                    SetupStep.FINISH -> FinishStep(uiState, draftPrefs) { draftPrefs = it }
                }
            }
        }
        SetupButtons(
            step = step,
            autoOn = draftPrefs.autoChangeEnabled,
            onStep = { step = it },
            onFinish = { onCompleteSetup(draftPrefs.copy(dryRunPreviewOnly = false)) }
        )
    }
}

/** On a first run the city comes from the time zone; a person's own choice, or a reopened setup, is left as it is. */
private fun withGuessedCity(prefs: UserPreferences, zone: java.time.ZoneId): UserPreferences {
    if (prefs.isSetupCompleted || prefs.selectedCityName != UserPreferences().selectedCityName) return prefs
    val city = CityGuess.forZone(zone, Instant.now()) ?: return prefs
    return prefs.copy(selectedCityName = city.name, latitude = city.latitude, longitude = city.longitude)
}

@Composable
private fun SetupHeader(step: SetupStep) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(
            text = stringResource(R.string.welcome_to_sunloom),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.sectionHeading()
        )
        Text(
            text = stringResource(R.string.daylight_and_evening_wallpapers_tailored),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.setup_step_progress, step.number, SetupStep.entries.size),
            style = MaterialTheme.typography.labelLarge
        )
        LinearProgressIndicator(
            progress = { step.progress },
            modifier = Modifier.fillMaxWidth().testTag("setup_progress")
        )
    }
}

@Composable
private fun WelcomeStep() {
    Text(
        stringResource(R.string.setup_intro_how),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
    for (line in listOf(R.string.setup_intro_sources, R.string.setup_intro_network, R.string.setup_intro_privacy)) {
        Text(
            stringResource(line),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ScheduleStep(draft: UserPreferences, cityWasGuessed: Boolean, onChange: (UserPreferences) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.automatic_wallpaper_schedule),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.sectionHeading()
            )
            Text(
                stringResource(R.string.switch_between_bright_wallpapers_during),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = draft.autoChangeEnabled,
            onCheckedChange = { onChange(draft.copy(autoChangeEnabled = it)) },
            modifier = Modifier
                .desktopControl(CircleShape)
                .switchLabel(stringResource(R.string.automatic_wallpaper_schedule))
                .testTag("setup_auto_switch")
        )
    }
    if (!draft.autoChangeEnabled) return
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
    Text(
        stringResource(R.string.schedule),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        ScheduleMode.entries.forEach { mode ->
            FilterChip(
                selected = draft.scheduleMode == mode,
                onClick = { onChange(draft.copy(scheduleMode = mode)) },
                label = { Text(mode.label) },
                modifier = Modifier.desktopControl(MaterialTheme.shapes.small)
            )
        }
    }
    Text(
        draft.scheduleMode.plainDescription,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (draft.scheduleMode == ScheduleMode.SUNRISE_SUNSET) {
        CityDropdownSelector(
            selectedCityName = draft.selectedCityName,
            onSelectCity = { city ->
                onChange(draft.copy(selectedCityName = city.name, latitude = city.latitude, longitude = city.longitude))
            }
        )
        if (cityWasGuessed) {
            Text(
                stringResource(R.string.setup_city_guess_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ContentStep(draft: UserPreferences, onChange: (UserPreferences) -> Unit) {
    Text(
        stringResource(R.string.categories),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.sectionHeading()
    )
    Text(
        stringResource(R.string.choose_the_themes_you_enjoy),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        SafeCategory.entries.filter { it != SafeCategory.USER_LOCAL }.forEach { cat ->
            val selected = cat in draft.enabledCategories
            FilterChip(
                selected = selected,
                onClick = {
                    // There must always be something to choose from: the last category cannot be removed.
                    val canRemove = selected && draft.enabledCategories.size > 1
                    val next = if (canRemove) draft.enabledCategories - cat else draft.enabledCategories + cat
                    onChange(draft.copy(enabledCategories = next))
                },
                label = { Text(cat.displayName) },
                modifier = Modifier.desktopControl(MaterialTheme.shapes.small)
            )
        }
    }
    Text(
        stringResource(R.string.setup_nasa_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
    Text(
        stringResource(R.string.apply_wallpaper_to),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.sectionHeading()
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        LockWorkspacePolicy.entries.forEach { policy ->
            FilterChip(
                selected = draft.lockWorkspacePolicy == policy,
                onClick = { onChange(draft.copy(lockWorkspacePolicy = policy)) },
                label = { Text(policy.label) },
                modifier = Modifier.desktopControl(MaterialTheme.shapes.small)
            )
        }
    }
}

@Composable
private fun FinishStep(uiState: SunloomUiState, draft: UserPreferences, onChange: (UserPreferences) -> Unit) {
    Text(
        stringResource(R.string.initial_preview),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.sectionHeading()
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
        val library = uiState.libraryWallpapers
        val sampleLight = library.firstOrNull { it.evaluation.brightnessClass == BrightnessClass.LIGHT }
        val sampleDark = library.firstOrNull { it.evaluation.brightnessClass == BrightnessClass.DARK }
        if (sampleLight != null) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                WallpaperPreviewSurface(
                    record = sampleLight,
                    showLegibilityZones = false,
                    modifier = Modifier.height(130.dp),
                    maxDecodeWidth = 640,
                    maxDecodeHeight = 400
                )
                TruncatedTitleWithTooltip("Daytime: ${sampleLight.title}", style = MaterialTheme.typography.labelLarge)
            }
        }
        if (sampleDark != null) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                WallpaperPreviewSurface(
                    record = sampleDark,
                    showLegibilityZones = false,
                    modifier = Modifier.height(130.dp),
                    maxDecodeWidth = 640,
                    maxDecodeHeight = 400
                )
                TruncatedTitleWithTooltip("Evening: ${sampleDark.title}", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    // Only the GitHub build can look for updates; it is offered once, here, and off unless chosen.
    if (remember { updateCheckFeature() != null }) {
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(stringResource(R.string.setup_update_opt_in), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.setup_update_opt_in_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = draft.weeklyUpdateCheck,
                onCheckedChange = { onChange(draft.copy(weeklyUpdateCheck = it)) },
                modifier = Modifier
                    .desktopControl(CircleShape)
                    .switchLabel(stringResource(R.string.setup_update_opt_in))
                    .testTag("setup_update_opt_in")
            )
        }
    }
}

/** The first step finishes with the defaults or goes on; later steps go back or on; the last finishes. */
@Composable
private fun SetupButtons(step: SetupStep, autoOn: Boolean, onStep: (SetupStep) -> Unit, onFinish: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalAlignment = Alignment.CenterVertically
    ) {
        step.back()?.let { previous ->
            TextButton(
                onClick = { onStep(previous) },
                modifier = Modifier.desktopControl(CircleShape).testTag("setup_back_button")
            ) {
                Text(stringResource(R.string.setup_back))
            }
        }
        Spacer(Modifier.weight(1f))
        when {
            step.isFirst -> {
                OutlinedButton(
                    onClick = { onStep(step.next() ?: step) },
                    modifier = Modifier.desktopControl(CircleShape).testTag("setup_customize_button")
                ) { Text(stringResource(R.string.setup_customize)) }
                Button(
                    onClick = onFinish,
                    modifier = Modifier.desktopControl(CircleShape).testTag("start_with_defaults_button")
                ) { Text(stringResource(R.string.setup_start_with_defaults), fontWeight = FontWeight.SemiBold) }
            }
            step.isLast -> Button(
                onClick = onFinish,
                modifier = Modifier.desktopControl(CircleShape).testTag("enable_sunloom_button")
            ) {
                Icon(SunloomIcons.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (autoOn) stringResource(R.string.start_sunloom) else stringResource(R.string.open_sunloom),
                    fontWeight = FontWeight.SemiBold
                )
            }
            else -> Button(
                onClick = { onStep(step.next() ?: step) },
                modifier = Modifier.desktopControl(CircleShape).testTag("setup_next_button")
            ) { Text(stringResource(R.string.setup_next), fontWeight = FontWeight.SemiBold) }
        }
    }
}
