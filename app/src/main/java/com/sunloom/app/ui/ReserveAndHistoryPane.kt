package com.sunloom.app.ui

import com.sunloom.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import com.sunloom.app.domain.model.WallpaperRecord

private val SelectionSaver = listSaver<SelectionState, Any?>(
    save = { listOf(it.anchorId, *it.selected.toTypedArray()) },
    restore = { saved ->
        SelectionState(
            selected = saved.drop(1).filterIsInstance<String>().toSet(),
            anchorId = saved.firstOrNull() as? String
        )
    }
)

private val GridCardMinWidth = 280.dp
private val GridSpacing = 12.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReserveAndHistoryPane(
    uiState: SunloomUiState,
    onApplySpecific: (String) -> Unit,
    onDeleteFromReserve: (String) -> Unit,
    onToggleKeepSpecific: (String) -> Unit,
    onPickLocalPhoto: () -> Unit,
    onPickBaselineRestore: () -> Unit,
    onSyncOnline: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val actions = LocalWallpaperActions.current
    val restricted = uiState.isWallpaperChangeRestricted
    val thumbnailAspect = primaryDisplayAspect(uiState, 1.2f, 2.2f)
    var aboutRecordId by rememberSaveable { mutableStateOf<String?>(null) }
    var gridView by rememberSaveable { mutableStateOf(UiPrefs.reserveGridView(context)) }
    var selection by rememberSaveable(stateSaver = SelectionSaver) { mutableStateOf(SelectionState()) }
    val focusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    val hiddenIds = uiState.pendingDiscard?.allRecordIds?.toSet().orEmpty()
    val visibleItems = if (hiddenIds.isEmpty()) {
        uiState.libraryWallpapers
    } else {
        uiState.libraryWallpapers.filter { it.id !in hiddenIds }
    }
    val order = visibleItems.map { it.id }
    LaunchedEffect(order) { selection = pruneSelection(selection, order.toSet()) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onKeyEvent { event ->
                // Esc drops the selection; Ctrl+A selects everything (Keyboard_Exit, Input_Combinations).
                // Esc is consumed on key-down: an unconsumed Esc key-down makes Compose clear focus, so
                // the matching key-up would never reach this pane.
                when {
                    event.key == Key.Escape && selection.selected.isNotEmpty() -> {
                        if (event.type == KeyEventType.KeyDown) selection = SelectionState()
                        true
                    }
                    // With a selection, Delete/Backspace removes the selection (one Undo) instead of bubbling to the
                    // workspace accelerator, which would permanently reject the unrelated current wallpaper.
                    (event.key == Key.Delete || event.key == Key.Backspace) && selection.selected.isNotEmpty() &&
                        !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed -> {
                        // The workspace accelerator fires on key-up, so act (and consume) on key-up too; the
                        // key-down is consumed so it is not treated as an unhandled key.
                        if (event.type == KeyEventType.KeyUp) {
                            val ids = selection.selected.toList()
                            selection = SelectionState()
                            actions.removeMany(ids)
                        }
                        true
                    }
                    event.type == KeyEventType.KeyUp && event.key == Key.A && event.isCtrlPressed && order.isNotEmpty() -> {
                        selection = SelectionState(order.toSet(), order.first())
                        true
                    }
                    else -> false
                }
            }
    ) {
        val columns = if (gridView) {
            ((maxWidth + GridSpacing) / (GridCardMinWidth + GridSpacing)).toInt().coerceAtLeast(1)
        } else {
            1
        }

        fun focusItem(index: Int) {
            val id = order.getOrNull(index) ?: return
            scope.launch {
                val visible = if (gridView) {
                    gridState.layoutInfo.visibleItemsInfo.any { it.key == id }
                } else {
                    listState.layoutInfo.visibleItemsInfo.any { it.key == id }
                }
                if (!visible) {
                    // +1 for the header item that precedes the cards.
                    if (gridView) gridState.scrollToItem(index + 1) else listState.scrollToItem(index + 1)
                    withFrameNanos { }
                }
                focusRequesters[id]?.let { runCatching { it.requestFocus() } }
            }
        }

        @Composable
        fun ItemCard(item: WallpaperRecord, index: Int) {
            ReserveItemCard(
                item = item,
                index = index,
                grid = gridView,
                thumbnailAspect = thumbnailAspect,
                uiState = uiState,
                restricted = restricted,
                selected = item.id in selection.selected,
                focusRequester = focusRequesters.getOrPut(item.id) { FocusRequester() },
                columns = columns,
                onNavigate = { move ->
                    reserveNavTarget(move, index, order.size, columns)?.let { focusItem(it) }
                },
                onPress = { ctrl, shift, touch ->
                    selection = selectionAfterPress(selection, order, item.id, ctrl, shift, touch)
                    // Pointer selection should leave the keyboard where the user is working, so
                    // Space, arrows, Esc and Ctrl+A act on the list right after clicking.
                    if (ctrl || shift || selection.selected.isNotEmpty()) {
                        focusRequesters[item.id]?.let { runCatching { it.requestFocus() } }
                    }
                },
                onToggleSelected = { selection = toggleSelection(selection, item.id) },
                onApplySpecific = onApplySpecific,
                onDeleteFromReserve = onDeleteFromReserve,
                onToggleKeepSpecific = onToggleKeepSpecific,
                onShowAbout = { aboutRecordId = item.id }
            )
        }

        @Composable
        fun HeaderCard() {
            ReserveHeader(
                gridView = gridView,
                onSetGridView = {
                    gridView = it
                    UiPrefs.setReserveGridView(context, it)
                },
                selectedCount = selection.selected.size,
                selectedRecords = visibleItems.filter { it.id in selection.selected },
                onClearSelection = { selection = SelectionState() },
                onKeepSelected = { records ->
                    // If every selected item is already kept, un-keep them; otherwise keep the rest.
                    val allKept = records.all { it.isFavorite }
                    records.filter { it.isFavorite == allKept }.forEach { onToggleKeepSpecific(it.id) }
                },
                onRemoveSelected = { ids ->
                    selection = SelectionState()
                    actions.removeMany(ids)
                },
                onPickLocalPhoto = onPickLocalPhoto,
                onPickBaselineRestore = onPickBaselineRestore
            )
        }

        @Composable
        fun EmptyCard() {
            ReserveEmptyState(
                isSyncing = uiState.isSyncingOnline,
                onSyncOnline = onSyncOnline,
                onPickLocalPhoto = onPickLocalPhoto
            )
        }

        if (gridView) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                modifier = Modifier.fillMaxSize().desktopScrollbar(gridState).testTag("reserve_grid"),
                verticalArrangement = Arrangement.spacedBy(GridSpacing),
                horizontalArrangement = Arrangement.spacedBy(GridSpacing)
            ) {
                item(key = "reserve_header", span = { GridItemSpan(maxLineSpan) }) { HeaderCard() }
                if (visibleItems.isEmpty()) {
                    item(key = "reserve_empty", span = { GridItemSpan(maxLineSpan) }) { EmptyCard() }
                }
                itemsIndexed(visibleItems, key = { _, it -> it.id }) { index, item -> ItemCard(item, index) }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().desktopScrollbar(listState).testTag("reserve_list"),
                verticalArrangement = Arrangement.spacedBy(GridSpacing)
            ) {
                item(key = "reserve_header") { HeaderCard() }
                if (visibleItems.isEmpty()) {
                    item(key = "reserve_empty") { EmptyCard() }
                }
                itemsIndexed(visibleItems, key = { _, it -> it.id }) { index, item -> ItemCard(item, index) }
            }
        }
    }

    aboutRecordId?.let { id ->
        // Resolve by id so the open dialog survives recreation and disappears if the item is removed.
        uiState.libraryWallpapers.firstOrNull { it.id == id }?.let { record ->
            WallpaperAboutDialog(record = record, onDismiss = { aboutRecordId = null })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReserveHeader(
    gridView: Boolean,
    onSetGridView: (Boolean) -> Unit,
    selectedCount: Int,
    selectedRecords: List<WallpaperRecord>,
    onClearSelection: () -> Unit,
    onKeepSelected: (List<WallpaperRecord>) -> Unit,
    onRemoveSelected: (List<String>) -> Unit,
    onPickLocalPhoto: () -> Unit,
    onPickBaselineRestore: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(Spacing.l),
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                verticalArrangement = Arrangement.spacedBy(Spacing.s)
            ) {
                Column(modifier = Modifier.widthIn(min = 220.dp).weight(1f)) {
                    Text(
                        text = stringResource(R.string.saved_wallpapers),
                        modifier = Modifier.sectionHeading(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.wallpapers_ready_for_offline_rotation_fr),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = !gridView,
                        onClick = { onSetGridView(false) },
                        label = { Text(stringResource(R.string.view_list)) },
                        modifier = Modifier.desktopControl(MaterialTheme.shapes.small).testTag("reserve_view_list")
                    )
                    FilterChip(
                        selected = gridView,
                        onClick = { onSetGridView(true) },
                        label = { Text(stringResource(R.string.view_grid)) },
                        modifier = Modifier.desktopControl(MaterialTheme.shapes.small).testTag("reserve_view_grid")
                    )
                    OutlinedButton(onClick = onPickLocalPhoto, modifier = Modifier.desktopControl(CircleShape)) {
                        Icon(SunloomIcons.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.add_photo))
                    }
                    OutlinedButton(onClick = onPickBaselineRestore, modifier = Modifier.desktopControl(CircleShape)) {
                        Icon(SunloomIcons.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.restore_photo))
                    }
                }
            }
        }
        if (selectedCount > 0) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().testTag("reserve_selection_bar")
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    Text(
                        text = pluralStringResource(R.plurals.reserve_selected_count, selectedCount, selectedCount),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.align(Alignment.CenterVertically).semantics {
                            liveRegion = LiveRegionMode.Polite
                        }
                    )
                    OutlinedButton(
                        onClick = { onKeepSelected(selectedRecords) },
                        modifier = Modifier.desktopControl(CircleShape).testTag("reserve_selection_keep")
                    ) { Text(stringResource(R.string.keep_selected)) }
                    OutlinedButton(
                        onClick = { onRemoveSelected(selectedRecords.map { it.id }) },
                        modifier = Modifier.desktopControl(CircleShape).testTag("reserve_selection_remove")
                    ) { Text(stringResource(R.string.remove_selected)) }
                    OutlinedButton(
                        onClick = onClearSelection,
                        modifier = Modifier.desktopControl(CircleShape).testTag("reserve_selection_clear")
                    ) { Text(stringResource(R.string.clear_selection)) }
                }
            }
        }
    }
}

@Composable
private fun ReserveEmptyState(isSyncing: Boolean, onSyncOnline: () -> Unit, onPickLocalPhoto: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().testTag("reserve_empty_state")
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.s)
        ) {
            Text(
                text = stringResource(R.string.no_saved_wallpapers_yet),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.fetch_a_fresh_batch_from_your_enabled_so),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                androidx.compose.material3.Button(
                    onClick = onSyncOnline,
                    enabled = !isSyncing,
                    modifier = Modifier.desktopControl().testTag("reserve_empty_fetch")
                ) { Text(if (isSyncing) stringResource(R.string.fetching) else stringResource(R.string.fetch_wallpapers)) }
                OutlinedButton(onClick = onPickLocalPhoto, modifier = Modifier.desktopControl()) {
                    Text(stringResource(R.string.add_photo))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReserveItemCard(
    item: WallpaperRecord,
    index: Int,
    grid: Boolean,
    thumbnailAspect: Float,
    uiState: SunloomUiState,
    restricted: Boolean,
    selected: Boolean,
    focusRequester: FocusRequester,
    columns: Int,
    onNavigate: (NavMove) -> Unit,
    onPress: (ctrl: Boolean, shift: Boolean, isTouch: Boolean) -> Unit,
    onToggleSelected: () -> Unit,
    onApplySpecific: (String) -> Unit,
    onDeleteFromReserve: (String) -> Unit,
    onToggleKeepSpecific: (String) -> Unit,
    onShowAbout: () -> Unit
) {
    val actions = LocalWallpaperActions.current
    val isActive = uiState.currentWallpaper?.id == item.id
    val shape = MaterialTheme.shapes.medium
    val pressGuard = remember { SelectionPressGuard() }
    CompositionLocalProvider(LocalSelectionPressGuard provides pressGuard) {
    ContextMenuHost(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .pointerPressWithModifiers(pressGuard, onPress)
            .semantics { this.selected = selected }
            .testTag("reserve_card_${item.id}"),
        focusShape = shape,
        onActivate = if (!isActive && !restricted) ({ onApplySpecific(item.id) }) else null,
        onExtraKeyEvent = { event ->
            if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) {
                false
            } else {
                val move = navMoveFor(event.key)
                when {
                    move != null -> {
                        if (event.type == KeyEventType.KeyUp) onNavigate(move)
                        true
                    }
                    event.key == Key.Spacebar -> {
                        if (event.type == KeyEventType.KeyUp) onToggleSelected()
                        true
                    }
                    else -> false
                }
            }
        },
        menuContent = { dismiss ->
            if (!isActive && !restricted) {
                ContextMenuItem(stringResource(R.string.apply_wallpaper_now), "ctx_apply_${item.id}") {
                    dismiss()
                    onApplySpecific(item.id)
                }
            }
            ContextMenuItem(
                if (item.isFavorite) stringResource(R.string.remove_from_favorites) else stringResource(R.string.add_to_favorites),
                "ctx_favorite_${item.id}"
            ) {
                dismiss()
                onToggleKeepSpecific(item.id)
            }
            ContextMenuItem("About this image\u2026", "ctx_about_${item.id}") {
                dismiss()
                onShowAbout()
            }
            ContextMenuItem(stringResource(R.string.copy_attribution), "ctx_copy_attribution_${item.id}") {
                dismiss()
                actions.copyAttribution(item)
            }
            ContextMenuItem(stringResource(R.string.save_wallpaper_as), "ctx_save_as_${item.id}") {
                dismiss()
                actions.saveAs(item)
            }
            HorizontalDivider()
            ContextMenuItem(stringResource(R.string.remove_from_reserve), "ctx_delete_${item.id}", destructive = true) {
                dismiss()
                onDeleteFromReserve(item.id)
            }
        }
    ) {
        val container = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f).compositeOver(MaterialTheme.colorScheme.surface)
        } else {
            MaterialTheme.colorScheme.surface
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = container),
            shape = shape,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    when {
                        selected -> Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                        isActive -> Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape)
                        else -> Modifier
                    }
                )
        ) {
            if (grid) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.m),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s)
                ) {
                    WallpaperPreviewSurface(
                        record = item,
                        showLegibilityZones = false,
                        modifier = Modifier.fillMaxWidth().aspectRatio(thumbnailAspect),
                        maxDecodeWidth = 640,
                        maxDecodeHeight = 640
                    )
                    ReserveItemText(item, isActive)
                    ReserveItemActions(item, isActive, restricted, onApplySpecific, onDeleteFromReserve, onToggleKeepSpecific)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.m),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WallpaperPreviewSurface(
                        record = item,
                        showLegibilityZones = false,
                        modifier = Modifier.width(120.dp).aspectRatio(thumbnailAspect),
                        maxDecodeWidth = 480,
                        maxDecodeHeight = 480
                    )
                    Column(modifier = Modifier.weight(1f)) { ReserveItemText(item, isActive) }
                    ReserveItemActions(item, isActive, restricted, onApplySpecific, onDeleteFromReserve, onToggleKeepSpecific)
                }
            }
        }
    }
    }
}

@Composable
private fun ReserveItemText(item: WallpaperRecord, isActive: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            TruncatedTitleWithTooltip(
                title = item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (isActive) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = stringResource(R.string.active),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
        Text(
            "${item.evaluation.brightnessClass.displayName} • ${item.category.displayName} • ${item.provenance.sourceName}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        ProvenanceLinkRow(
            label = "${item.provenance.creatorName} (${item.provenance.licenseType.label})",
            displayText = item.provenance.originalPageUrl.ifBlank { "On-device" },
            url = item.provenance.originalPageUrl
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReserveItemActions(
    item: WallpaperRecord,
    isActive: Boolean,
    restricted: Boolean,
    onApplySpecific: (String) -> Unit,
    onDeleteFromReserve: (String) -> Unit,
    onToggleKeepSpecific: (String) -> Unit
) {
    FlowRow(
        modifier = Modifier.ignoredBySelection(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalArrangement = Arrangement.Center
    ) {
        ShortcutTooltip(if (item.isFavorite) stringResource(R.string.remove_from_favorites) else stringResource(R.string.add_to_favorites)) {
            IconButton(
                onClick = { onToggleKeepSpecific(item.id) },
                modifier = Modifier.desktopControl(CircleShape).testTag("favorite_reserve_item_${item.id}")
            ) {
                Icon(
                    imageVector = if (item.isFavorite) SunloomIcons.Favorite else SunloomIcons.FavoriteBorder,
                    contentDescription = if (item.isFavorite) stringResource(R.string.remove_from_favorites) else stringResource(R.string.add_to_favorites),
                    tint = if (item.isFavorite) SunloomColors.Favorite else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (!isActive) {
            ShortcutTooltip(if (restricted) HostRestrictedTooltip else "Apply as wallpaper now") {
                OutlinedButton(
                    onClick = { onApplySpecific(item.id) },
                    enabled = !restricted,
                    modifier = Modifier.desktopControl()
                ) {
                    Text(stringResource(R.string.apply))
                }
            }
        }
        ShortcutTooltip(stringResource(R.string.delete_from_reserve)) {
            IconButton(
                onClick = { onDeleteFromReserve(item.id) },
                modifier = Modifier.desktopControl(CircleShape).testTag("delete_reserve_item_${item.id}")
            ) {
                Icon(
                    imageVector = SunloomIcons.Delete,
                    contentDescription = stringResource(R.string.delete_from_reserve),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
