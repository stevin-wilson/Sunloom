package com.sunloom.app.ui

import com.sunloom.app.labels.title
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.CircleShape
import com.sunloom.app.R
import androidx.activity.compose.BackHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.stateDescription
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
import androidx.compose.ui.input.key.KeyEvent
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

/**
 * The actions the panes reach through [LocalWallpaperActions]. Remembered: that local is static, so a new instance
 * per recomposition would recompose the whole workspace every time.
 */
@Composable
private fun rememberWallpaperActions(actions: SunloomActions, snackbarHostState: SnackbarHostState): WallpaperActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val attributionCopiedMessage = stringResource(R.string.attribution_copied)
    val showMessage: (String) -> Unit = remember(scope, snackbarHostState) {
        { message -> scope.launch { snackbarHostState.showSnackbar(message) }; Unit }
    }
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
    return remember(actions.onSaveAs, copyAttribution, actions.onDeleteManyFromReserve, showMessage, actions.onSetChosenWallpaper) {
        WallpaperActions(actions.onSaveAs, copyAttribution, actions.onDeleteManyFromReserve, showMessage, actions.onSetChosenWallpaper)
    }
}

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
    val onSetChosenWallpaper = actions.onSetChosenWallpaper
    val onRestoreSystemDefault = actions.onRestoreSystemDefault
    val onToggleFullscreen = actions.onToggleFullscreen
    val onDropImages = actions.onDropImages
    val onSaveAs = actions.onSaveAs
    val onDeleteManyFromReserve = actions.onDeleteManyFromReserve
    val onUndoDiscard = actions.onUndoDiscard
    val onCommitDiscard = actions.onCommitDiscard
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
    val singleKeyShortcuts = remember(context) {
        SingleKeyShortcuts(UiPrefs.singleKeyShortcuts(context)) { UiPrefs.setSingleKeyShortcuts(context, it) }
    }
    val wallpaperActions = rememberWallpaperActions(actions, snackbarHostState)
    val dropTarget = remember(onDropImages) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean = onDropImages(event.toAndroidDragEvent())
        }
    }

    CompositionLocalProvider(
        LocalShortcutGate provides shortcutGate,
        LocalSingleKeyShortcuts provides singleKeyShortcuts,
        LocalRestoreRootFocus provides restoreRootFocus,
        LocalWallpaperActions provides wallpaperActions
    ) {
    MaterialTheme(colorScheme = colorScheme, shapes = SunloomShapes) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("workspace_root")
                .busyCursor(uiState.isApplyingWallpaper || uiState.isSyncingOnline)
                .focusRequester(rootFocusRequester)
                .focusable()
                .onKeyEvent { event ->
                    handleWorkspaceKey(
                        event, uiState, actions, wallpaperActions, isFullscreen,
                        workspaceReady, shortcutGate, singleKeyShortcuts, restoreRootFocus
                    )
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
                uiState.isStorageUnavailable -> StorageUnavailableScreen(uiState, actions.onRestoreSystemDefault)
                uiState.isInitialLoading -> InitialLoadingScreen(uiState.statusBannerMessage)
                !uiState.preferences.isSetupCompleted || uiState.isSetupReopened -> {
                    FirstRunSetupScreen(
                        uiState = uiState,
                        onCompleteSetup = onCompleteSetup
                    )
                }
                else -> {
                    DesktopWorkspaceScreen(uiState = uiState, actions = actions, isFullscreen = isFullscreen)
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

/** Workspace-wide keyboard shortcuts. Returns whether the event was consumed. */
private fun handleWorkspaceKey(
    event: KeyEvent,
    uiState: SunloomUiState,
    actions: SunloomActions,
    wallpaperActions: WallpaperActions,
    isFullscreen: Boolean,
    workspaceReady: Boolean,
    shortcutGate: ShortcutGate,
    singleKeyShortcuts: SingleKeyShortcuts,
    restoreRootFocus: () -> Unit
): Boolean {
    // Ctrl+1..5 jumps between workspace tabs (works while typing, like desktop apps).
    if (workspaceReady && isCtrlChord(event) && handleCtrlKey(event, uiState, actions, wallpaperActions, shortcutGate)) {
        return true
    }
    if (event.type != KeyEventType.KeyUp || !isPlainKeyEvent(event)) return false
    return when {
        event.key == Key.F11 -> {
            actions.onToggleFullscreen()
            true
        }
        event.key == Key.Escape && isFullscreen -> {
            actions.onToggleFullscreen()
            true
        }
        // Esc backs out of whatever has focus (a text field, chip or button) to the
        // workspace, so single-key accelerators work again (Keyboard_Exit).
        event.key == Key.Escape && workspaceReady -> {
            restoreRootFocus()
            true
        }
        // Single-key accelerators never fire while typing or before setup is complete.
        shortcutGate.isTextInputFocused || !workspaceReady || !singleKeyShortcuts.enabled -> false
        else -> handleSingleKey(event.key, uiState, actions)
    }
}

private fun isCtrlChord(event: KeyEvent): Boolean =
    event.type == KeyEventType.KeyUp && event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed

private fun handleCtrlKey(
    event: KeyEvent,
    uiState: SunloomUiState,
    actions: SunloomActions,
    wallpaperActions: WallpaperActions,
    shortcutGate: ShortcutGate
): Boolean {
    val tab = tabForCtrlShortcut(event.key)
    if (tab != null) {
        actions.onSelectTab(tab)
        return true
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
        CtrlAction.UNDO -> actions.onUndoDiscard()
        CtrlAction.COPY_ATTRIBUTION -> current?.let(wallpaperActions.copyAttribution)
        CtrlAction.SAVE_AS -> current?.let(actions.onSaveAs)
        CtrlAction.OPEN_SCHEDULE -> actions.onSelectTab(DesktopNavTab.DISPLAYS_AND_SCHEDULE)
        null -> return false
    }
    return true
}

private fun handleSingleKey(key: Key, uiState: SunloomUiState, actions: SunloomActions): Boolean = when (key) {
    Key.N -> {
        actions.onNextWallpaper()
        true
    }
    Key.P -> {
        actions.onPreviousWallpaper()
        true
    }
    Key.K -> {
        actions.onToggleKeep()
        true
    }
    Key.Backspace, Key.Delete -> {
        // Acts immediately; the snackbar below offers Undo (no blocking dialog).
        if (uiState.currentWallpaper != null) actions.onRejectForever()
        true
    }
    else -> false
}

@Composable
private fun StorageUnavailableScreen(uiState: SunloomUiState, onRestoreSystemDefault: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xxl),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.width(560.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.m)
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
                OutlinedButton(onClick = onRestoreSystemDefault, modifier = Modifier.desktopControl(CircleShape)) {
                    Text(stringResource(R.string.restore_system_default_wallpaper))
                }
            }
        }
    }
}

@Composable
private fun InitialLoadingScreen(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xxl),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.width(360.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.m)
        ) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
    val resources = LocalResources.current
    LaunchedEffect(pending?.token) {
        if (pending == null) return@LaunchedEffect
        val message = when (pending.kind) {
            DiscardKind.DISLIKE -> resources.getString(R.string.undo_disliked, pending.title)
            DiscardKind.REMOVE -> if (pending.additionalRecordIds.isEmpty()) {
                resources.getString(R.string.undo_removed_one, pending.title)
            } else {
                resources.getString(R.string.undo_removed_many, pending.title)
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
    val displayLabel = selectedCity?.dropdownLabel ?: stringResource(R.string.custom_city_label, selectedCityName)

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
fun DesktopWorkspaceScreen(
    uiState: SunloomUiState,
    actions: SunloomActions,
    isFullscreen: Boolean = false
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthClass = WorkspaceWidthClass.from(maxWidth)
        val contentPadding = if (widthClass == WorkspaceWidthClass.Compact) 12.dp else 20.dp
        val workspace: @Composable (Modifier) -> Unit = { paneModifier ->
            Column(
                modifier = paneModifier.padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(Spacing.m)
            ) {
                TopStatusBanner(
                    uiState = uiState,
                    onTogglePause = actions.onTogglePause,
                    onRetryApply = { uiState.retryApplyRecordId?.let(actions.onApplySpecific) },
                    onRetrySync = actions.onSyncOnline,
                    onApplyNow = { uiState.applyOfferRecordId?.let(actions.onApplySpecific) }
                )

                ActiveWorkspacePane(uiState, actions, isFullscreen)
            }
        }

        if (widthClass == WorkspaceWidthClass.Compact) {
            Column(modifier = Modifier.fillMaxSize()) {
                workspace(Modifier.weight(1f).fillMaxWidth())
                WorkspaceNavigationBar(uiState, actions)
            }
        } else {
            val showLabels = widthClass == WorkspaceWidthClass.Expanded
            Row(modifier = Modifier.fillMaxSize()) {
                WorkspaceNavigationRail(uiState, actions, showLabels)
                workspace(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.ActiveWorkspacePane(uiState: SunloomUiState, actions: SunloomActions, isFullscreen: Boolean) {
    val onRequestRejectConfirm = { if (uiState.currentWallpaper != null) actions.onRejectForever() }
    when (uiState.activeTab) {
        DesktopNavTab.TODAY_CANVAS -> TodayCanvasPane(
            uiState = uiState,
            onNextWallpaper = actions.onNextWallpaper,
            onPreviousWallpaper = actions.onPreviousWallpaper,
            onToggleKeep = actions.onToggleKeep,
            onRequestRejectConfirm = onRequestRejectConfirm,
            onRestoreOriginal = actions.onRestoreOriginal,
            onSyncOnline = actions.onSyncOnline,
            isFullscreen = isFullscreen,
            onToggleFullscreen = actions.onToggleFullscreen
        )
        else -> ReadableWidth {
            when (uiState.activeTab) {
            DesktopNavTab.RESERVE_AND_HISTORY -> ReserveAndHistoryPane(
                uiState = uiState,
                onApplySpecific = actions.onApplySpecific,
                onDeleteFromReserve = actions.onDeleteFromReserve,
                onToggleKeepSpecific = actions.onToggleKeepSpecific,
                onPickLocalPhoto = { actions.onPickLocalPhoto(false) },
                onPickBaselineRestore = { actions.onPickLocalPhoto(true) },
                onSyncOnline = actions.onSyncOnline
            )
            DesktopNavTab.SOURCES_AND_CATEGORIES -> SourcesAndCategoriesPane(
                uiState = uiState,
                onToggleCategory = actions.onToggleCategory,
                onToggleSource = actions.onToggleSource,
                onToggleWifiOnly = actions.onToggleWifiOnly,
                onToggleBatterySaverDeferral = actions.onToggleBatterySaverDeferral,
                onSyncOnline = actions.onSyncOnline
            )
            DesktopNavTab.DISPLAYS_AND_SCHEDULE -> DisplaysAndSchedulePane(
                uiState = uiState,
                onSelectCity = actions.onSelectCity,
                onUpdateScheduleMode = actions.onUpdateScheduleMode,
                onUpdateLockPolicy = actions.onUpdateLockPolicy,
                onToggleBlurredFill = actions.onToggleBlurredFill,
                onUpdateFixedHours = actions.onUpdateFixedHours,
                onUpdateChangeEvery = actions.onUpdateChangeEvery,
                onSetChosenWallpaper = actions.onSetChosenWallpaper,
                onUpdateCustomCoordinates = actions.onUpdateCustomCoordinates,
                onToggleBatterySaverDeferral = actions.onToggleBatterySaverDeferral,
                onUpdateDisplayPolicy = actions.onUpdateDisplayPolicy,
                onForgetRememberedDisplay = actions.onForgetRememberedDisplay,
                onToggleAutoSwitch = actions.onToggleAutoSwitch,
                onRestoreSystemDefault = actions.onRestoreSystemDefault,
                onReopenSetup = actions.onReopenSetup
            )
            DesktopNavTab.SAFETY_AND_DECISION_LOG -> DiagnosticsPane(
                uiState = uiState,
                onToggleLegibilityOverlay = actions.onToggleLegibilityOverlay,
                onUpdateMaxCacheBytes = actions.onUpdateMaxCacheBytes,
                onResetSunloom = actions.onResetSunloom,
                onCopyDiagnostics = actions.onCopyDiagnostics,
                onReportProblem = actions.onReportProblem,
                onCheckForUpdates = actions.onCheckForUpdates
            )
                else -> Unit
            }
        }
    }

}

@Composable
private fun WorkspaceNavigationBar(uiState: SunloomUiState, actions: SunloomActions) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        windowInsets = WindowInsets(0, 0, 0, 0),
        modifier = Modifier.testTag("workspace_nav_bar")
    ) {
        val labelsVisible = navLabelsAlwaysVisible(LocalDensity.current.fontScale)
        DesktopNavTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = uiState.activeTab == tab,
                onClick = { actions.onSelectTab(tab) },
                modifier = Modifier.desktopControl(MaterialTheme.shapes.large).testTag("nav_${tab.name}"),
                // The visible label names it; when labels give way to large text the icon must.
                icon = { Icon(navIcon(tab), contentDescription = if (labelsVisible) null else tab.title()) },
                label = { Text(tab.title(), fontSize = 12.sp, maxLines = 1) },
                alwaysShowLabel = labelsVisible
            )
        }
    }
}

@Composable
private fun WorkspaceNavigationRail(uiState: SunloomUiState, actions: SunloomActions, showLabels: Boolean) {
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
            // Without text beside the icon, hover or long-press names the section.
            val railItem: @Composable () -> Unit = {
                NavigationRailItem(
                    selected = uiState.activeTab == tab,
                    onClick = { actions.onSelectTab(tab) },
                    modifier = Modifier.desktopControl(MaterialTheme.shapes.large).testTag("nav_${tab.name}"),
                    icon = { Icon(navIcon(tab), contentDescription = if (showLabels) null else tab.title()) },
                    label = if (showLabels) {
                        { Text(tab.title(), fontSize = 12.sp) }
                    } else {
                        null
                    },
                    alwaysShowLabel = showLabels
                )
            }
            if (showLabels) railItem() else ShortcutTooltip(tab.title()) { railItem() }
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
    onRetryApply: () -> Unit = {},
    onRetrySync: () -> Unit = {},
    onApplyNow: () -> Unit = {}
) {
    val prefs = uiState.preferences
    val brightnessName = uiState.targetBrightnessNow.title()
    val scheduleNote = when (prefs.scheduleMode) {
        ScheduleMode.SUNRISE_SUNSET -> {
            val solarNote = when {
                uiState.daylightWindow.isPolarDay -> stringResource(R.string.solar_24h_daylight)
                uiState.daylightWindow.isPolarNight -> stringResource(R.string.solar_24h_night)
                else -> stringResource(
                    R.string.solar_sunrise_sunset, uiState.daylightWindow.sunriseTime, uiState.daylightWindow.sunsetTime
                )
            }
            stringResource(R.string.banner_note_solar, brightnessName, solarNote)
        }
        ScheduleMode.FOLLOW_OS_THEME -> stringResource(R.string.banner_note_follow_os, brightnessName)
        ScheduleMode.FIXED_CLOCK ->
            stringResource(R.string.banner_note_fixed, brightnessName, prefs.fixedLightHour, prefs.fixedDarkHour)
        ScheduleMode.MANUAL_ONLY -> stringResource(R.string.banner_note_manual, brightnessName)
    }
    val isDaytime = uiState.targetBrightnessNow == BrightnessClass.LIGHT
    val showPauseResume = uiState.preferences.autoChangeEnabled &&
        uiState.preferences.scheduleMode != ScheduleMode.MANUAL_ONLY
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
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
                    horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (isDaytime) SunloomIcons.LightMode else SunloomIcons.DarkMode,
                        contentDescription = null,
                        tint = if (isDaytime) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
                    )
                    BannerMessage(uiState.statusBannerMessage, scheduleNote)
                }
                if (uiState.retryApplyRecordId != null) {
                    OutlinedButton(
                        onClick = onRetryApply,
                        enabled = !uiState.isApplyingWallpaper,
                        modifier = Modifier.desktopControl().testTag("retry_apply_button")
                    ) { Text(stringResource(R.string.try_again)) }
                }
                if (uiState.applyOfferRecordId != null) {
                    OutlinedButton(
                        onClick = onApplyNow,
                        enabled = !uiState.isApplyingWallpaper,
                        modifier = Modifier.desktopControl().testTag("apply_now_button")
                    ) { Text(stringResource(R.string.apply_now)) }
                }
                if (uiState.canRetrySync) {
                    OutlinedButton(
                        onClick = onRetrySync,
                        enabled = !uiState.isSyncingOnline,
                        modifier = Modifier.desktopControl().testTag("retry_sync_button")
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
            BannerProgress(uiState)
        }
    }
}

@Composable
private fun BannerProgress(uiState: SunloomUiState) {
    val importing = uiState.importProgress
    if (importing != null && importing.total > 1) {
        LinearProgressIndicator(
            progress = { importing.done.toFloat() / importing.total },
            modifier = Modifier.fillMaxWidth().testTag("import_progress")
        )
    } else if (importing != null || uiState.isSyncingOnline || uiState.isApplyingWallpaper) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("import_progress"))
    }
}

@Composable
private fun BannerMessage(message: String, scheduleNote: String) {
    var expanded by remember(message) { mutableStateOf(false) }
    var cutOff by remember(message) { mutableStateOf(false) }
    val expandedLabel = stringResource(R.string.show_less)
    val collapsedLabel = stringResource(R.string.show_more)
    Column(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (expanded) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) cutOff = it.hasVisualOverflow },
            modifier = Modifier
                .testTag("status_banner_message")
                .semantics {
                    stateDescription = if (expanded) expandedLabel else collapsedLabel
                }
                .clickable { expanded = !expanded }
        )
        if (cutOff || expanded) {
            Text(
                text = stringResource(if (expanded) R.string.show_less else R.string.show_more),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("status_banner_expand_hint")
            )
        }
        Text(
            text = scheduleNote,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("status_banner_note")
        )
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
