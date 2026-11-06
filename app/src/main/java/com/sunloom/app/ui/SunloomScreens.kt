package com.sunloom.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.DisplayPolicy
import com.sunloom.app.domain.model.LockWorkspacePolicy
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.solar.CityCoordinates
import com.sunloom.app.domain.solar.SolarCalculator

private val SunloomDarkPalette = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF082F49),
    secondary = Color(0xFFF59E0B),
    onSecondary = Color(0xFF451A03),
    tertiary = Color(0xFF818CF8),
    background = Color(0xFF0B1120),
    surface = Color(0xFF131C31),
    surfaceVariant = Color(0xFF1E293B),
    onBackground = Color(0xFFF1F5F9),
    onSurface = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF64748B),
    outlineVariant = Color(0xFF334155),
    error = Color(0xFFF87171)
)

private val SunloomLightPalette = lightColorScheme(
    primary = Color(0xFF0284C7),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFFD97706),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF4F46E5),
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE2E8F0),
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFFCBD5E1),
    error = Color(0xFFDC2626)
)

@Composable
fun SunloomApp(
    uiState: SunloomUiState,
    onCompleteSetup: (UserPreferences) -> Unit,
    onSelectTab: (DesktopNavTab) -> Unit,
    onNextWallpaper: () -> Unit,
    onPreviousWallpaper: () -> Unit,
    onToggleKeep: () -> Unit,
    onRejectForever: () -> Unit,
    onTogglePause: () -> Unit,
    onRestoreOriginal: () -> Unit,
    onApplySpecific: (String) -> Unit,
    onToggleLegibilityOverlay: () -> Unit,
    onSyncOnline: () -> Unit,
    onToggleCategory: (SafeCategory) -> Unit,
    onToggleSource: (String) -> Unit,
    onSelectCity: (CityCoordinates) -> Unit,
    onUpdateScheduleMode: (ScheduleMode) -> Unit,
    onUpdateLockPolicy: (LockWorkspacePolicy) -> Unit,
    onToggleBlurredFill: (Boolean) -> Unit,
    onToggleWifiOnly: (Boolean) -> Unit,
    onPickLocalPhoto: (asBaselineRestore: Boolean) -> Unit,
    onDeleteFromReserve: (String) -> Unit = {},
    onToggleKeepSpecific: (String) -> Unit = {},
    onUpdateFixedHours: (lightHour: Int, darkHour: Int) -> Unit = { _, _ -> },
    onUpdateCustomCoordinates: (lat: Double, lon: Double, label: String) -> Unit = { _, _, _ -> },
    onUpdateMaxCacheBytes: (Long) -> Unit = {},
    onToggleBatterySaverDeferral: (Boolean) -> Unit = {},
    onUpdateDisplayPolicy: (DisplayPolicy) -> Unit = {},
    onForgetRememberedDisplay: (String) -> Unit = {},
    onRestoreSystemDefault: () -> Unit = onRestoreOriginal,
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    darkTheme: Boolean = isSystemInDarkTheme()
) {
    var showKeyboardRejectConfirm by remember { mutableStateOf(false) }
    val colorScheme = if (darkTheme) SunloomDarkPalette else SunloomLightPalette
    val shortcutGate = remember { ShortcutGate() }
    val rootFocusRequester = remember { FocusRequester() }
    val workspaceReady = !uiState.isInitialLoading &&
        !uiState.isStorageUnavailable &&
        uiState.preferences.isSetupCompleted

    // Give the root focus once the workspace is usable, and again after the reject dialog
    // closes, so single-key accelerators work without needing a click first.
    LaunchedEffect(workspaceReady, showKeyboardRejectConfirm) {
        if (workspaceReady && !showKeyboardRejectConfirm) {
            runCatching { rootFocusRequester.requestFocus() }
        }
    }

    CompositionLocalProvider(LocalShortcutGate provides shortcutGate) {
    MaterialTheme(colorScheme = colorScheme) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(rootFocusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyUp || !isPlainKeyEvent(event)) {
                        return@onKeyEvent false
                    }
                    when {
                        event.key == Key.F11 -> {
                            onToggleFullscreen()
                            true
                        }
                        event.key == Key.Escape && isFullscreen -> {
                            onToggleFullscreen()
                            true
                        }
                        // Single-key accelerators never fire while typing or before setup is complete.
                        shortcutGate.isTextInputFocused || !workspaceReady -> false
                        else -> when (event.key) {
                            Key.N -> {
                                onNextWallpaper()
                                true
                            }
                            Key.P -> {
                                onPreviousWallpaper()
                                true
                            }
                            Key.K -> {
                                onToggleKeep()
                                true
                            }
                            Key.Backspace, Key.Delete -> {
                                showKeyboardRejectConfirm = true
                                true
                            }
                            else -> false
                        }
                    }
                },
            color = MaterialTheme.colorScheme.background
        ) {
            if (showKeyboardRejectConfirm && uiState.currentWallpaper != null) {
                ConfirmDestructiveDialog(
                    title = "Dislike Wallpaper?",
                    message = "Permanently exclude '${uiState.currentWallpaper.title}' from rotation and remove its cached file?",
                    confirmLabel = "Dislike",
                    confirmTestTag = "confirm_reject_dialog_button",
                    onConfirm = {
                        showKeyboardRejectConfirm = false
                        onRejectForever()
                    },
                    onDismiss = { showKeyboardRejectConfirm = false }
                )
            }

            when {
                uiState.isStorageUnavailable -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            modifier = Modifier.width(560.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "Local Library Database Unavailable",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = uiState.statusBannerMessage,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "To protect your permanent rejection blocklist, Sunloom fails closed rather than running without persistent storage. Check available device storage and restart Sunloom.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedButton(onClick = onRestoreSystemDefault) {
                                    Text("Restore System Default Wallpaper")
                                }
                            }
                        }
                    }
                }
                uiState.isInitialLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = Modifier.width(360.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                text = uiState.statusBannerMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                !uiState.preferences.isSetupCompleted -> {
                    FirstRunSetupScreen(
                        uiState = uiState,
                        onCompleteSetup = onCompleteSetup
                    )
                }
                else -> {
                    DesktopWorkspaceScreen(
                    uiState = uiState,
                    isFullscreen = isFullscreen,
                    onToggleFullscreen = onToggleFullscreen,
                    onSelectTab = onSelectTab,
                    onNextWallpaper = onNextWallpaper,
                    onPreviousWallpaper = onPreviousWallpaper,
                    onToggleKeep = onToggleKeep,
                    onRequestRejectConfirm = { showKeyboardRejectConfirm = true },
                    onTogglePause = onTogglePause,
                    onRestoreOriginal = onRestoreOriginal,
                    onRestoreSystemDefault = onRestoreSystemDefault,
                    onApplySpecific = onApplySpecific,
                    onDeleteFromReserve = onDeleteFromReserve,
                    onToggleKeepSpecific = onToggleKeepSpecific,
                    onToggleLegibilityOverlay = onToggleLegibilityOverlay,
                    onSyncOnline = onSyncOnline,
                    onToggleCategory = onToggleCategory,
                    onToggleSource = onToggleSource,
                    onSelectCity = onSelectCity,
                    onUpdateScheduleMode = onUpdateScheduleMode,
                    onUpdateLockPolicy = onUpdateLockPolicy,
                    onToggleBlurredFill = onToggleBlurredFill,
                    onToggleWifiOnly = onToggleWifiOnly,
                    onPickLocalPhoto = onPickLocalPhoto,
                    onUpdateFixedHours = onUpdateFixedHours,
                    onUpdateCustomCoordinates = onUpdateCustomCoordinates,
                    onUpdateMaxCacheBytes = onUpdateMaxCacheBytes,
                    onToggleBatterySaverDeferral = onToggleBatterySaverDeferral,
                    onUpdateDisplayPolicy = onUpdateDisplayPolicy,
                    onForgetRememberedDisplay = onForgetRememberedDisplay,
                    onReopenSetup = {
                        onCompleteSetup(uiState.preferences.copy(isSetupCompleted = false))
                    }
                    )
                }
            }
        }
    }
    }
}

/**
 * Single-line truncated text that reveals the full untruncated string in a floating
 * tooltip on pointer hover or long-press.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION")
@Composable
fun TruncatedTitleWithTooltip(
    title: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Text(text = title)
            }
        },
        state = rememberTooltipState()
    ) {
        Text(
            text = title,
            style = style,
            color = color,
            fontWeight = fontWeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
        )
    }
}

/**
 * Dropdown menu for selecting a city, sorted by live DST-aware UTC offset ascending.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CityDropdownSelector(
    selectedCityName: String,
    onSelectCity: (CityCoordinates) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedCity = remember(selectedCityName) {
        SolarCalculator.BUNDLED_CITIES.firstOrNull {
            it.name.equals(selectedCityName, ignoreCase = true) ||
                selectedCityName.startsWith(it.cityName, ignoreCase = true)
        }
    }
    val displayLabel = selectedCity?.dropdownLabel ?: "Custom: $selectedCityName"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = displayLabel,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("City & time zone") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
                .suppressShortcutsWhileFocused()
                .testTag("city_dropdown_field")
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            SolarCalculator.BUNDLED_CITIES.forEach { city ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = city.dropdownLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (city.name == selectedCity?.name) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        onSelectCity(city)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FirstRunSetupScreen(
    uiState: SunloomUiState,
    onCompleteSetup: (UserPreferences) -> Unit
) {
    var draftPrefs by remember(uiState.preferences) { mutableStateOf(uiState.preferences) }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .desktopScrollbar(scrollState)
            .verticalScroll(scrollState)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Welcome to Sunloom",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Daylight and evening wallpapers tailored to your screen.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Automatic wallpaper schedule", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Switch between bright wallpapers during the day and calm dark wallpapers at night.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = draftPrefs.autoChangeEnabled,
                        onCheckedChange = { draftPrefs = draftPrefs.copy(autoChangeEnabled = it) }
                    )
                }

                if (draftPrefs.autoChangeEnabled) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                    Text("Schedule", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ScheduleMode.entries.forEach { mode ->
                            FilterChip(
                                selected = draftPrefs.scheduleMode == mode,
                                onClick = { draftPrefs = draftPrefs.copy(scheduleMode = mode) },
                                label = { Text(mode.label) }
                            )
                        }
                    }
                    Text(
                        text = draftPrefs.scheduleMode.plainDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (draftPrefs.scheduleMode == ScheduleMode.SUNRISE_SUNSET) {
                        CityDropdownSelector(
                            selectedCityName = draftPrefs.selectedCityName,
                            onSelectCity = { city ->
                                draftPrefs = draftPrefs.copy(
                                    selectedCityName = city.name,
                                    latitude = city.latitude,
                                    longitude = city.longitude
                                )
                            }
                        )
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Categories", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Choose the themes you enjoy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SafeCategory.entries.filter { it != SafeCategory.USER_LOCAL }.forEach { cat ->
                        val selected = cat in draftPrefs.enabledCategories
                        FilterChip(
                            selected = selected,
                            onClick = {
                                val next = if (selected && draftPrefs.enabledCategories.size > 1) {
                                    draftPrefs.enabledCategories - cat
                                } else {
                                    draftPrefs.enabledCategories + cat
                                }
                                draftPrefs = draftPrefs.copy(enabledCategories = next)
                            },
                            label = { Text(cat.displayName) }
                        )
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Apply wallpaper to", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LockWorkspacePolicy.entries.forEach { policy ->
                        FilterChip(
                            selected = draftPrefs.lockWorkspacePolicy == policy,
                            onClick = { draftPrefs = draftPrefs.copy(lockWorkspacePolicy = policy) },
                            label = { Text(policy.label) }
                        )
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Initial preview",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val sampleLight = uiState.libraryWallpapers.firstOrNull { it.evaluation.brightnessClass == BrightnessClass.LIGHT }
                    val sampleDark = uiState.libraryWallpapers.firstOrNull { it.evaluation.brightnessClass == BrightnessClass.DARK }

                    if (sampleLight != null) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            WallpaperPreviewSurface(record = sampleLight, showLegibilityZones = false, modifier = Modifier.height(130.dp))
                            TruncatedTitleWithTooltip("Daytime: ${sampleLight.title}", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    if (sampleDark != null) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            WallpaperPreviewSurface(record = sampleDark, showLegibilityZones = false, modifier = Modifier.height(130.dp))
                            TruncatedTitleWithTooltip("Evening: ${sampleDark.title}", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = { onCompleteSetup(draftPrefs.copy(dryRunPreviewOnly = false)) },
                        modifier = Modifier.testTag("enable_sunloom_button")
                    ) {
                        Icon(SunloomIcons.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (draftPrefs.autoChangeEnabled) "Start Sunloom" else "Open Sunloom",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DesktopWorkspaceScreen(
    uiState: SunloomUiState,
    onSelectTab: (DesktopNavTab) -> Unit,
    onNextWallpaper: () -> Unit,
    onPreviousWallpaper: () -> Unit,
    onToggleKeep: () -> Unit,
    onRequestRejectConfirm: () -> Unit,
    onTogglePause: () -> Unit,
    onRestoreOriginal: () -> Unit,
    onRestoreSystemDefault: () -> Unit = onRestoreOriginal,
    onApplySpecific: (String) -> Unit,
    onDeleteFromReserve: (String) -> Unit = {},
    onToggleKeepSpecific: (String) -> Unit = {},
    onToggleLegibilityOverlay: () -> Unit,
    onSyncOnline: () -> Unit,
    onToggleCategory: (SafeCategory) -> Unit,
    onToggleSource: (String) -> Unit,
    onSelectCity: (CityCoordinates) -> Unit,
    onUpdateScheduleMode: (ScheduleMode) -> Unit,
    onUpdateLockPolicy: (LockWorkspacePolicy) -> Unit,
    onToggleBlurredFill: (Boolean) -> Unit,
    onToggleWifiOnly: (Boolean) -> Unit,
    onPickLocalPhoto: (asBaselineRestore: Boolean) -> Unit,
    onUpdateFixedHours: (lightHour: Int, darkHour: Int) -> Unit = { _, _ -> },
    onUpdateCustomCoordinates: (lat: Double, lon: Double, label: String) -> Unit = { _, _, _ -> },
    onUpdateMaxCacheBytes: (Long) -> Unit = {},
    onToggleBatterySaverDeferral: (Boolean) -> Unit = {},
    onUpdateDisplayPolicy: (DisplayPolicy) -> Unit = {},
    onForgetRememberedDisplay: (String) -> Unit = {},
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    onReopenSetup: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthClass = WorkspaceWidthClass.from(maxWidth)
        val contentPadding = if (widthClass == WorkspaceWidthClass.Compact) 12.dp else 20.dp
        val workspace: @Composable (Modifier) -> Unit = { paneModifier ->
            Column(
                modifier = paneModifier.padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                TopStatusBanner(uiState = uiState, onTogglePause = onTogglePause)

                when (uiState.activeTab) {
                    DesktopNavTab.TODAY_CANVAS -> TodayCanvasPane(
                        uiState = uiState,
                        onNextWallpaper = onNextWallpaper,
                        onPreviousWallpaper = onPreviousWallpaper,
                        onToggleKeep = onToggleKeep,
                        onRequestRejectConfirm = onRequestRejectConfirm,
                        onRestoreOriginal = onRestoreOriginal,
                        isFullscreen = isFullscreen,
                        onToggleFullscreen = onToggleFullscreen
                    )
                    DesktopNavTab.RESERVE_AND_HISTORY -> ReserveAndHistoryPane(
                        uiState = uiState,
                        onApplySpecific = onApplySpecific,
                        onDeleteFromReserve = onDeleteFromReserve,
                        onToggleKeepSpecific = onToggleKeepSpecific,
                        onPickLocalPhoto = { onPickLocalPhoto(false) },
                        onPickBaselineRestore = { onPickLocalPhoto(true) }
                    )
                    DesktopNavTab.SOURCES_AND_CATEGORIES -> SourcesAndCategoriesPane(
                        uiState = uiState,
                        onToggleCategory = onToggleCategory,
                        onToggleSource = onToggleSource,
                        onToggleWifiOnly = onToggleWifiOnly,
                        onToggleBatterySaverDeferral = onToggleBatterySaverDeferral,
                        onSyncOnline = onSyncOnline
                    )
                    DesktopNavTab.DISPLAYS_AND_SCHEDULE -> DisplaysAndSchedulePane(
                        uiState = uiState,
                        onSelectCity = onSelectCity,
                        onUpdateScheduleMode = onUpdateScheduleMode,
                        onUpdateLockPolicy = onUpdateLockPolicy,
                        onToggleBlurredFill = onToggleBlurredFill,
                        onUpdateFixedHours = onUpdateFixedHours,
                        onUpdateCustomCoordinates = onUpdateCustomCoordinates,
                        onToggleBatterySaverDeferral = onToggleBatterySaverDeferral,
                        onUpdateDisplayPolicy = onUpdateDisplayPolicy,
                        onForgetRememberedDisplay = onForgetRememberedDisplay,
                        onRestoreSystemDefault = onRestoreSystemDefault,
                        onReopenSetup = onReopenSetup
                    )
                    DesktopNavTab.SAFETY_AND_DECISION_LOG -> DiagnosticsPane(
                        uiState = uiState,
                        onToggleLegibilityOverlay = onToggleLegibilityOverlay,
                        onUpdateMaxCacheBytes = onUpdateMaxCacheBytes
                    )
                }
            }
        }

        if (widthClass == WorkspaceWidthClass.Compact) {
            Column(modifier = Modifier.fillMaxSize()) {
                workspace(Modifier.weight(1f).fillMaxWidth())
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.testTag("workspace_nav_bar")
                ) {
                    DesktopNavTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = uiState.activeTab == tab,
                            onClick = { onSelectTab(tab) },
                            modifier = Modifier.desktopControl(RoundedCornerShape(16.dp)).testTag("nav_${tab.name}"),
                            icon = { Icon(navIcon(tab), contentDescription = tab.label) },
                            label = { Text(tab.label, fontSize = 11.sp, maxLines = 1) }
                        )
                    }
                }
            }
        } else {
            val showLabels = widthClass == WorkspaceWidthClass.Expanded
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxHeight().testTag("workspace_nav_rail")
                ) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Sunloom",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    DesktopNavTab.entries.forEach { tab ->
                        NavigationRailItem(
                            selected = uiState.activeTab == tab,
                            onClick = { onSelectTab(tab) },
                            modifier = Modifier.desktopControl(RoundedCornerShape(16.dp)).testTag("nav_${tab.name}"),
                            icon = { Icon(navIcon(tab), contentDescription = tab.label) },
                            label = if (showLabels) {
                                { Text(tab.label, fontSize = 11.sp) }
                            } else {
                                null
                            },
                            alwaysShowLabel = showLabels
                        )
                    }
                }
                workspace(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

private fun navIcon(tab: DesktopNavTab) = when (tab) {
    DesktopNavTab.TODAY_CANVAS -> SunloomIcons.AutoAwesome
    DesktopNavTab.RESERVE_AND_HISTORY -> SunloomIcons.Collections
    DesktopNavTab.SOURCES_AND_CATEGORIES -> SunloomIcons.CloudDownload
    DesktopNavTab.DISPLAYS_AND_SCHEDULE -> SunloomIcons.Schedule
    DesktopNavTab.SAFETY_AND_DECISION_LOG -> SunloomIcons.Info
}

@Composable
private fun TopStatusBanner(
    uiState: SunloomUiState,
    onTogglePause: () -> Unit
) {
    val solarNote = when {
        uiState.daylightWindow.isPolarDay -> "24h Daylight"
        uiState.daylightWindow.isPolarNight -> "24h Night"
        else -> "Sunrise ${uiState.daylightWindow.sunriseTime} • Sunset ${uiState.daylightWindow.sunsetTime}"
    }
    val isDaytime = uiState.targetBrightnessNow == BrightnessClass.LIGHT
    val showPauseResume = uiState.preferences.autoChangeEnabled &&
        uiState.preferences.scheduleMode != ScheduleMode.MANUAL_ONLY
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (isDaytime) SunloomIcons.LightMode else SunloomIcons.DarkMode,
                        contentDescription = null,
                        tint = if (isDaytime) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
                    )
                    Column {
                        Text(
                            text = uiState.statusBannerMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${uiState.targetBrightnessNow.displayName} • $solarNote",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (showPauseResume) {
                    ShortcutTooltip(
                        if (uiState.preferences.isPaused) "Resume automatic scheduling" else "Pause automatic scheduling"
                    ) {
                        OutlinedButton(
                            onClick = onTogglePause,
                            modifier = Modifier.desktopControl().testTag("pause_resume_button")
                        ) {
                            Icon(
                                imageVector = if (uiState.preferences.isPaused) SunloomIcons.PlayArrow else SunloomIcons.Pause,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (uiState.preferences.isPaused) "Resume" else "Pause")
                        }
                    }
                }
            }
            if (uiState.isSyncingOnline) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
