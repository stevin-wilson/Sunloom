package com.sunloom.app.ui

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
    onPickBaselineRestore: () -> Unit
) {
    val listState = rememberLazyListState()
    val restricted = uiState.isWallpaperChangeRestricted
    val thumbnailAspect = primaryDisplayAspect(uiState, 1.2f, 2.2f)
    var pendingDelete by remember { mutableStateOf<WallpaperRecord?>(null) }
    var aboutRecord by remember { mutableStateOf<WallpaperRecord?>(null) }
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
                            text = "Saved Wallpapers",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Wallpapers ready for offline rotation from your enabled sources.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onPickLocalPhoto) {
                            Icon(SunloomIcons.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add Photo")
                        }
                        OutlinedButton(onClick = onPickBaselineRestore) {
                            Icon(SunloomIcons.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Backup")
                        }
                    }
                }
            }
        }

        items(items = uiState.libraryWallpapers, key = { it.id }) { item ->
            val isActive = uiState.currentWallpaper?.id == item.id
            ContextMenuHost(
                modifier = Modifier.fillMaxWidth().testTag("reserve_card_${item.id}"),
                focusShape = RoundedCornerShape(14.dp),
                menuContent = { dismiss ->
                    if (!isActive && !restricted) {
                        ContextMenuItem("Apply wallpaper now", "ctx_apply_${item.id}") {
                            dismiss()
                            onApplySpecific(item.id)
                        }
                    }
                    ContextMenuItem(
                        if (item.isFavorite) "Remove from favorites" else "Add to favorites",
                        "ctx_favorite_${item.id}"
                    ) {
                        dismiss()
                        onToggleKeepSpecific(item.id)
                    }
                    ContextMenuItem("About this image\u2026", "ctx_about_${item.id}") {
                        dismiss()
                        aboutRecord = item
                    }
                    HorizontalDivider()
                    ContextMenuItem("Remove from reserve\u2026", "ctx_delete_${item.id}", destructive = true) {
                        dismiss()
                        pendingDelete = item
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
                                            text = "Active",
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
                            ShortcutTooltip(if (item.isFavorite) "Remove from favorites" else "Add to favorites") {
                                IconButton(
                                    onClick = { onToggleKeepSpecific(item.id) },
                                    modifier = Modifier.desktopControl(CircleShape).testTag("favorite_reserve_item_${item.id}")
                                ) {
                                    Icon(
                                        imageVector = if (item.isFavorite) SunloomIcons.Favorite else SunloomIcons.FavoriteBorder,
                                        contentDescription = if (item.isFavorite) "Remove from favorites" else "Add to favorites",
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
                                        Text("Apply")
                                    }
                                }
                            }
                            ShortcutTooltip("Delete from reserve") {
                                IconButton(
                                    onClick = { pendingDelete = item },
                                    modifier = Modifier.desktopControl(CircleShape).testTag("delete_reserve_item_${item.id}")
                                ) {
                                    Icon(
                                        imageVector = SunloomIcons.Delete,
                                        contentDescription = "Delete from reserve",
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

    pendingDelete?.let { record ->
        ConfirmDestructiveDialog(
            title = "Remove from reserve?",
            message = "Remove '${record.title}' from your saved wallpapers and delete its cached file? This can't be undone.",
            confirmLabel = "Remove",
            confirmTestTag = "confirm_delete_reserve_button",
            onConfirm = {
                pendingDelete = null
                onDeleteFromReserve(record.id)
            },
            onDismiss = { pendingDelete = null }
        )
    }
    aboutRecord?.let { record ->
        WallpaperAboutDialog(record = record, onDismiss = { aboutRecord = null })
    }
}