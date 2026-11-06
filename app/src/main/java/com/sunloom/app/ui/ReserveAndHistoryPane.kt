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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sunloom.app.domain.model.WallpaperRecord

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
    val listState = rememberLazyListState()
    val restricted = uiState.isWallpaperChangeRestricted
    val thumbnailAspect = primaryDisplayAspect(uiState, 1.2f, 2.2f)
    var aboutRecordId by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().desktopScrollbar(listState),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "reserve_header") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.saved_wallpapers),
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onPickLocalPhoto) {
                            Icon(SunloomIcons.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.add_photo))
                        }
                        OutlinedButton(onClick = onPickBaselineRestore) {
                            Icon(SunloomIcons.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.restore_photo))
                        }
                    }
                }
            }
        }

        val hiddenId = uiState.pendingDiscard?.recordId
        val visibleItems = if (hiddenId == null) uiState.libraryWallpapers else uiState.libraryWallpapers.filter { it.id != hiddenId }
        if (visibleItems.isEmpty()) {
            item(key = "reserve_empty") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().testTag("reserve_empty_state")
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            androidx.compose.material3.Button(
                                onClick = onSyncOnline,
                                enabled = !uiState.isSyncingOnline,
                                modifier = Modifier.desktopControl().testTag("reserve_empty_fetch")
                            ) { Text(if (uiState.isSyncingOnline) stringResource(R.string.fetching) else stringResource(R.string.fetch_wallpapers)) }
                            OutlinedButton(onClick = onPickLocalPhoto, modifier = Modifier.desktopControl()) {
                                Text(stringResource(R.string.add_photo))
                            }
                        }
                    }
                }
            }
        }

        items(items = visibleItems, key = { it.id }) { item ->
            val isActive = uiState.currentWallpaper?.id == item.id
            ContextMenuHost(
                modifier = Modifier.fillMaxWidth().testTag("reserve_card_${item.id}"),
                focusShape = RoundedCornerShape(14.dp),
                onActivate = if (!isActive && !restricted) ({ onApplySpecific(item.id) }) else null,
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
                        aboutRecordId = item.id
                    }
                    HorizontalDivider()
                    ContextMenuItem(stringResource(R.string.remove_from_reserve), "ctx_delete_${item.id}", destructive = true) {
                        dismiss()
                        onDeleteFromReserve(item.id)
                    }
                }
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isActive) {
                                Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                            } else {
                                Modifier
                            }
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WallpaperPreviewSurface(
                            record = item,
                            showLegibilityZones = false,
                            modifier = Modifier
                                .width(120.dp)
                                .aspectRatio(thumbnailAspect),
                            maxDecodeWidth = 480,
                            maxDecodeHeight = 480
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TruncatedTitleWithTooltip(
                                    title = item.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (uiState.currentWallpaper?.id == item.id) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(8.dp)
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
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
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
                                        tint = if (item.isFavorite) Color(0xFFF43F5E) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (uiState.currentWallpaper?.id != item.id) {
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
                }
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