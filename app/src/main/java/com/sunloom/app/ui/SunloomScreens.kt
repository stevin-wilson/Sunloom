package com.sunloom.app.ui

import com.sunloom.app.R
import androidx.activity.compose.BackHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.isShiftPressed
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.sunloom.app.domain.attribution.AttributionFormatter
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SunloomApp(
    uiState: SunloomUiState,
    actions: SunloomActions,
    isFullscreen: Boolean = false,
    darkTheme: Boolean = isSystemInDarkTheme()
) {
    val onCompleteSetup = actions.onCompleteSetup
    val onSelectTab = actions.onSelectTab
    val onNextWallpaper = actions.onNextWallpaper
    val onPreviousWallpaper = actions.onPreviousWallpaper
    val onToggleKeep = actions.onToggleKeep
    val onRejectForever = actions.onRejectForever
    val onTogglePause = actions.onTogglePause
    val onRestoreOriginal = actions.onRestoreOriginal
    val onApplySpecific = actions.onApplySpecific
    val onToggleLegibilityOverlay = actions.onToggleLegibilityOverlay
    val onSyncOnline = actions.onSyncOnline
    val onToggleCategory = actions.onToggleCategory
    val onToggleSource = actions.onToggleSource
    val onSelectCity = actions.onSelectCity
    val onUpdateScheduleMode = actions.onUpdateScheduleMode
    val onUpdateLockPolicy = actions.onUpdateLockPolicy
    val onToggleBlurredFill = actions.onToggleBlurredFill
    val onToggleWifiOnly = actions.onToggleWifiOnly
    val onPickLocalPhoto = actions.onPickLocalPhoto
    val onDeleteFromReserve = actions.onDeleteFromReserve
    val onToggleKeepSpecific = actions.onToggleKeepSpecific
    val onUpdateFixedHours = actions.onUpdateFixedHours
    val onUpdateCustomCoordinates = actions.onUpdateCustomCoordinates
    val onUpdateMaxCacheBytes = actions.onUpdateMaxCacheBytes
    val onToggleBatterySaverDeferral = actions.onToggleBatterySaverDeferral
    val onUpdateDisplayPolicy = actions.onUpdateDisplayPolicy
    val onForgetRememberedDisplay = actions.onForgetRememberedDisplay
    val onRestoreSystemDefault = actions.onRestoreSystemDefault
    val onToggleFullscreen = actions.onToggleFullscreen
    val onDropImages = actions.onDropImages
    val onSaveAs = actions.onSaveAs
    val onDeleteManyFromReserve = actions.onDeleteManyFromReserve
    val onUndoDiscard = actions.onUndoDiscard
    val onCommitDiscard = actions.onCommitDiscard
    val onToggleAutoSwitch = actions.onToggleAutoSwitch
    val onReopenSetup = actions.onReopenSetup
    val onResetSunloom = actions.onResetSunloom
    val onCopyDiagnostics = actions.onCopyDiagnostics
    val onReportProblem = actions.onReportProblem
    val onCheckForUpdates = actions.onCheckForUpdates
    val snackbarHostState = remember { SnackbarHostState() }
    val colorScheme = if (darkTheme) SunloomDarkPalette else SunloomLightPalette
    val shortcutGate = remember { ShortcutGate() }
    val rootFocusRequester = remember { FocusRequester() }
    val workspaceReady = !uiState.isInitialLoading &&
        !uiState.isStorageUnavailable &&
        uiState.preferences.isSetupCompleted

    // System Back steps out of a section to Home before it leaves the app (Article XII §4).
    val backTarget = tabAfterBack(uiState.activeTab, workspaceReady)
    BackHandler(enabled = backTarget != null) { backTarget?.let(onSelectTab) }

    // Give the root focus once the workspace is usable, and again after the reject dialog
    // closes, so single-key accelerators work without needing a click first.
    LaunchedEffect(workspaceReady, uiState.activeTab) {
        if (workspaceReady) {
            runCatching { rootFocusRequester.requestFocus() }
        }
    }
    val restoreRootFocus: () -> Unit = remember(rootFocusRequester) {
        { runCatching { rootFocusRequester.requestFocus() }; Unit }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val attributionCopiedMessage = stringResource(R.string.attribution_copied)
    val showMessage: (String) -> Unit = remember(scope, snackbarHostState) {
        { message -> scope.launch { snackbarHostState.showSnackbar(message) }; Unit }
    }
    // Remembered: LocalWallpaperActions is a static local, so a new instance per recomposition would
    // recompose the whole workspace every time.
    val copyAttribution: (com.sunloom.app.domain.model.WallpaperRecord) -> Unit =
        remember(context, showMessage, attributionCopiedMessage) {
            { record ->
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText(record.title, AttributionFormatter.format(record))
                )
                showMessage(attributionCopiedMessage)
            }
        }
    val wallpaperActions = remember(onSaveAs, copyAttribution, onDeleteManyFromReserve, showMessage) {
        WallpaperActions(onSaveAs, copyAttribution, onDeleteManyFromReserve, showMessage)
    }
    val dropTarget = remember(onDropImages) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean = onDropImages(event.toAndroidDragEvent())
        }
    }

    CompositionLocalProvider(
        LocalShortcutGate provides shortcutGate,
        LocalRestoreRootFocus provides restoreRootFocus,
        LocalWallpaperActions provides wallpaperActions
    ) {
    MaterialTheme(colorScheme = colorScheme) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .busyCursor(uiState.isApplyingWallpaper || uiState.isSyncingOnline)
                .focusRequester(rootFocusRequester)
                .focusable()
                .onKeyEvent { event ->
                    // Ctrl+1..5 jumps between workspace tabs (works while typing, like desktop apps).
                    if (event.type == KeyEventType.KeyUp && event.isCtrlPressed &&
                        !event.isAltPressed && !event.isMetaPressed && workspaceReady
                    ) {
                        val tab = tabForCtrlShortcut(event.key)
                        if (tab != null) {
                            onSelectTab(tab)
                            return@onKeyEvent true
                        }
                        val current = uiState.currentWallpaper
                        when (
                            ctrlActionFor(
                                key = event.key,
                                shift = event.isShiftPressed,
                                textInputFocused = shortcutGate.isTextInputFocused,
                                hasPendingUndo = uiState.pendingDiscard != null,
                                hasCurrentWallpaper = current != null
                            )
                        ) {
                            CtrlAction.UNDO -> { onUndoDiscard(); return@onKeyEvent true }
                            CtrlAction.COPY_ATTRIBUTION -> { current?.let(copyAttribution); return@onKeyEvent true }
                            CtrlAction.SAVE_AS -> { current?.let(onSaveAs); return@onKeyEvent true }
                            CtrlAction.OPEN_SCHEDULE -> {
                                onSelectTab(DesktopNavTab.DISPLAYS_AND_SCHEDULE)
                                return@onKeyEvent true
                            }
                            null -> Unit
                        }
                    }
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
                        // Esc backs out of whatever has focus (a text field, chip or button) to the
                        // workspace, so single-key accelerators work again (Keyboard_Exit).
                        event.key == Key.Escape && workspaceReady -> {
                            restoreRootFocus()
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
                                // Acts immediately; the snackbar below offers Undo (no blocking dialog).
                                if (uiState.currentWallpaper != null) onRejectForever()
                                true
                            }
                            else -> false
                        }
                    }
                },
            color = MaterialTheme.colorScheme.background
        ) {
          // Edge-to-edge (enforced at targetSdk 36): keep content clear of the caption bar, system
          // bars, display cutouts and the keyboard, while the background still bleeds to the edges.
          BoxWithConstraints(
              modifier = Modifier
                  .fillMaxSize()
                  .windowInsetsPadding(WindowInsets.safeDrawing)
                  .dragAndDropTarget(
                      shouldStartDragAndDrop = { event ->
                          event.mimeTypes().any { it.startsWith("image/") }
                      },
                      target = dropTarget
                  )
          ) {
            UndoSnackbarEffect(
                pending = uiState.pendingDiscard,
                snackbarHostState = snackbarHostState,
                onUndo = onUndoDiscard,
                onCommit = onCommitDiscard
            )

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
                                    text = stringResource(R.string.local_library_database_unavailable),
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
                                    text = stringResource(R.string.to_protect_your_permanent_rejection_bloc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedButton(onClick = onRestoreSystemDefault, modifier = Modifier.desktopControl(RoundedCornerShape(50))) {
                                    Text(stringResource(R.string.restore_system_default_wallpaper))
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
                !uiState.preferences.isSetupCompleted || uiState.isSetupReopened -> {
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
                    onRequestRejectConfirm = { if (uiState.currentWallpaper != null) onRejectForever() },
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
                    onToggleAutoSwitch = onToggleAutoSwitch,
                    onResetSunloom = onResetSunloom,
                    onCopyDiagnostics = onCopyDiagnostics,
                    onReportProblem = onReportProblem,
                    onCheckForUpdates = onCheckForUpdates,
                    onReopenSetup = onReopenSetup
                    )
                }
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        bottom = if (WorkspaceWidthClass.from(maxWidth) == WorkspaceWidthClass.Compact) 88.dp else 16.dp
                    )
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
          }
        }
    }
    }
}

/**
 * Shows an "Undo" snackbar for a pending Dislike/Remove. Dismiss/timeout commits the action; the
 * Undo action cancels it. Keyed by token so a new pending action replaces the old snackbar.
 */
@Composable
private fun UndoSnackbarEffect(
    pending: PendingDiscard?,
    snackbarHostState: SnackbarHostState,
    onUndo: () -> Unit,
    onCommit: () -> Unit
) {
    val latestUndo = rememberUpdatedState(onUndo)
    val latestCommit = rememberUpdatedState(onCommit)
    LaunchedEffect(pending?.token) {
        if (pending == null) return@LaunchedEffect
        val message = when (pending.kind) {
            DiscardKind.DISLIKE -> "Disliked '${pending.title}'"
            DiscardKind.REMOVE -> if (pending.additionalRecordIds.isEmpty()) {
                "Removed '${pending.title}' from reserve"
            } else {
                "Removed ${pending.title} from reserve"
            }
        }
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = "Undo",
            withDismissAction = true,
            duration = SnackbarDuration.Long
        )
        if (result == SnackbarResult.ActionPerformed) latestUndo.value() else latestCommit.value()
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
    var expanded by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(query) { filterCities(query, SolarCalculator.BUNDLED_CITIES) }
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
            // Type to filter the 47 bundled cities (Hick's law); shows the current city when closed.
            value = if (expanded) query else displayLabel,
            onValueChange = {
                query = it
                expanded = true
            },
            singleLine = true,
            label = { Text(stringResource(R.string.city_time_zone)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    // First Esc clears the search text; the next one leaves the field (Keyboard_Exit).
                    if (event.key == Key.Escape && query.isNotEmpty()) {
                        if (event.type == KeyEventType.KeyUp) query = ""
                        true
                    } else {
                        false
                    }
                }
                .suppressShortcutsWhileFocused()
                .testTag("city_dropdown_field")
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                query = ""
            }
        ) {
            if (matches.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.no_matching_city_try_the_custom_coordina)) },
                    onClick = {},
                    enabled = false
                )
            }
            matches.forEach { city ->
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
                        query = ""
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
    var draftPrefs by rememberSaveable(
        uiState.preferences,
        stateSaver = setupDraftSaver(uiState.preferences)
    ) { mutableStateOf(uiState.preferences) }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .desktopScrollbar(scrollState)
            .verticalScroll(scrollState)
            // Keep setup a readable column on ultra-wide windows (UI_Secondary_Elements).
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = 760.dp)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.welcome_to_sunloom),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = stringResource(R.string.daylight_and_evening_wallpapers_tailored),
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
                        Text(stringResource(R.string.automatic_wallpaper_schedule), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.sectionHeading())
                        Text(
                            stringResource(R.string.switch_between_bright_wallpapers_during),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = draftPrefs.autoChangeEnabled,
                        onCheckedChange = { draftPrefs = draftPrefs.copy(autoChangeEnabled = it) }, modifier = Modifier.desktopControl(RoundedCornerShape(50)).switchLabel(stringResource(R.string.automatic_wallpaper_schedule))
                    )
                }

                if (draftPrefs.autoChangeEnabled) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                    Text(stringResource(R.string.schedule), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ScheduleMode.entries.forEach { mode ->
                            FilterChip(
                                selected = draftPrefs.scheduleMode == mode,
                                onClick = { draftPrefs = draftPrefs.copy(scheduleMode = mode) },
                                label = { Text(mode.label) }, modifier = Modifier.desktopControl(RoundedCornerShape(8.dp))
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
                Text(stringResource(R.string.categories), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.sectionHeading())
                Text(
                    stringResource(R.string.choose_the_themes_you_enjoy),
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
                            label = { Text(cat.displayName) }, modifier = Modifier.desktopControl(RoundedCornerShape(8.dp))
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
                Text(stringResource(R.string.apply_wallpaper_to), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.sectionHeading())
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LockWorkspacePolicy.entries.forEach { policy ->
                        FilterChip(
                            selected = draftPrefs.lockWorkspacePolicy == policy,
                            onClick = { draftPrefs = draftPrefs.copy(lockWorkspacePolicy = policy) },
                            label = { Text(policy.label) }, modifier = Modifier.desktopControl(RoundedCornerShape(8.dp))
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
                    text = stringResource(R.string.initial_preview),
                    modifier = Modifier.sectionHeading(),
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
                            WallpaperPreviewSurface(record = sampleLight, showLegibilityZones = false, modifier = Modifier.height(130.dp), maxDecodeWidth = 640, maxDecodeHeight = 400)
                            TruncatedTitleWithTooltip("Daytime: ${sampleLight.title}", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    if (sampleDark != null) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            WallpaperPreviewSurface(record = sampleDark, showLegibilityZones = false, modifier = Modifier.height(130.dp), maxDecodeWidth = 640, maxDecodeHeight = 400)
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
                        modifier = Modifier.desktopControl(RoundedCornerShape(50)).testTag("enable_sunloom_button")
                    ) {
                        Icon(SunloomIcons.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (draftPrefs.autoChangeEnabled) stringResource(R.string.start_sunloom) else stringResource(R.string.open_sunloom),
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
    onToggleAutoSwitch: (Boolean) -> Unit = {},
    onResetSunloom: () -> Unit = {},
    onCopyDiagnostics: () -> Unit = {},
    onReportProblem: () -> Unit = {},
    onCheckForUpdates: () -> Unit = {},
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
                TopStatusBanner(
                    uiState = uiState,
                    onTogglePause = onTogglePause,
                    onRetryApply = { uiState.retryApplyRecordId?.let(onApplySpecific) }
                )

                when (uiState.activeTab) {
                    DesktopNavTab.TODAY_CANVAS -> TodayCanvasPane(
                        uiState = uiState,
                        onNextWallpaper = onNextWallpaper,
                        onPreviousWallpaper = onPreviousWallpaper,
                        onToggleKeep = onToggleKeep,
                        onRequestRejectConfirm = onRequestRejectConfirm,
                        onRestoreOriginal = onRestoreOriginal,
                        onSyncOnline = onSyncOnline,
                        isFullscreen = isFullscreen,
                        onToggleFullscreen = onToggleFullscreen
                    )
                    else -> ReadableWidth {
                        when (uiState.activeTab) {
                        DesktopNavTab.RESERVE_AND_HISTORY -> ReserveAndHistoryPane(
                            uiState = uiState,
                            onApplySpecific = onApplySpecific,
                            onDeleteFromReserve = onDeleteFromReserve,
                            onToggleKeepSpecific = onToggleKeepSpecific,
                            onPickLocalPhoto = { onPickLocalPhoto(false) },
                            onPickBaselineRestore = { onPickLocalPhoto(true) },
                            onSyncOnline = onSyncOnline
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
                            onToggleAutoSwitch = onToggleAutoSwitch,
                            onRestoreSystemDefault = onRestoreSystemDefault,
                            onReopenSetup = onReopenSetup
                        )
                        DesktopNavTab.SAFETY_AND_DECISION_LOG -> DiagnosticsPane(
                            uiState = uiState,
                            onToggleLegibilityOverlay = onToggleLegibilityOverlay,
                            onUpdateMaxCacheBytes = onUpdateMaxCacheBytes,
                            onResetSunloom = onResetSunloom,
                            onCopyDiagnostics = onCopyDiagnostics,
                            onReportProblem = onReportProblem,
                            onCheckForUpdates = onCheckForUpdates
                        )
                
                            else -> Unit
                        }
                    }
                }
            }
        }

        if (widthClass == WorkspaceWidthClass.Compact) {
            Column(modifier = Modifier.fillMaxSize()) {
                workspace(Modifier.weight(1f).fillMaxWidth())
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    modifier = Modifier.testTag("workspace_nav_bar")
                ) {
                    DesktopNavTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = uiState.activeTab == tab,
                            onClick = { onSelectTab(tab) },
                            modifier = Modifier.desktopControl(RoundedCornerShape(16.dp)).testTag("nav_${tab.name}"),
                            icon = { Icon(navIcon(tab), contentDescription = null) }, // the visible label names it
                            label = { Text(tab.label, fontSize = 12.sp, maxLines = 1) }
                        )
                    }
                }
            }
        } else {
            val showLabels = widthClass == WorkspaceWidthClass.Expanded
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    modifier = Modifier.fillMaxHeight().testTag("workspace_nav_rail")
                ) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.sunloom),
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
                            icon = { Icon(navIcon(tab), contentDescription = if (showLabels) null else tab.label) },
                            label = if (showLabels) {
                                { Text(tab.label, fontSize = 12.sp) }
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

/** Caps line length on text-heavy panes so wide free-form windows stay readable. */
@Composable
private fun ColumnScope.ReadableWidth(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
        Box(modifier = Modifier.widthIn(max = ReadableContentMaxWidth).fillMaxSize()) { content() }
    }
}

private val ReadableContentMaxWidth = 1100.dp

@Composable
private fun TopStatusBanner(
    uiState: SunloomUiState,
    onTogglePause: () -> Unit,
    onRetryApply: () -> Unit = {}
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
                    Column(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
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
                if (uiState.retryApplyRecordId != null) {
                    OutlinedButton(
                        onClick = onRetryApply,
                        enabled = !uiState.isApplyingWallpaper,
                        modifier = Modifier.desktopControl().testTag("retry_apply_button")
                    ) { Text(stringResource(R.string.try_again)) }
                }
                if (showPauseResume) {
                    ShortcutTooltip(
                        if (uiState.preferences.isPaused) stringResource(R.string.resume_automatic_scheduling) else stringResource(R.string.pause_automatic_scheduling)
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
                            Text(if (uiState.preferences.isPaused) stringResource(R.string.resume) else stringResource(R.string.pause))
                        }
                    }
                }
            }
            if (uiState.isSyncingOnline || uiState.isApplyingWallpaper) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** Case-insensitive, all-words match of [query] against each city's dropdown label; blank returns all. */
internal fun filterCities(query: String, cities: List<CityCoordinates>): List<CityCoordinates> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return cities
    return cities.filter { city ->
        val label = city.dropdownLabel.lowercase()
        words.all { it in label }
    }
}
