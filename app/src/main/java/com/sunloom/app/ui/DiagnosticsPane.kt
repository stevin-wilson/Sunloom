package com.sunloom.app.ui

import com.sunloom.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.testTag
import com.sunloom.app.domain.matcher.SourceRejectionGroup
import com.sunloom.app.domain.matcher.SourceStockRow
import com.sunloom.app.domain.model.SourceDiagnostics
import com.sunloom.app.domain.model.SourceHealthState
import com.sunloom.app.domain.safety.SafetyGate

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DiagnosticsPane(
    uiState: SunloomUiState,
    onToggleLegibilityOverlay: () -> Unit = {},
    onUpdateMaxCacheBytes: (Long) -> Unit = {},
    onResetSunloom: () -> Unit = {}
) {
    var showResetConfirm by remember { mutableStateOf(false) }
    if (showResetConfirm) {
        ConfirmDestructiveDialog(
            title = stringResource(R.string.reset_sunloom),
            message = stringResource(R.string.reset_sunloom_message),
            confirmLabel = stringResource(R.string.reset_sunloom_confirm),
            confirmTestTag = "confirm_reset_sunloom_button",
            onConfirm = {
                showResetConfirm = false
                onResetSunloom()
            },
            onDismiss = { showResetConfirm = false }
        )
    }
    val usedMb = uiState.totalCacheBytesUsed.toDouble() / (1024.0 * 1024.0)
    val maxMb = uiState.preferences.maxCacheBytes.toDouble() / (1024.0 * 1024.0)

    val stockRows = remember(uiState.libraryWallpapers, uiState.preferences.enabledCategories, uiState.sourceDiagnostics) {
        uiState.reserveBySource
    }
    val rejectionGroups = remember(uiState.rejectionLog) { uiState.rejectionSummary }

    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().desktopScrollbar(listState),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item(key = "diag_header") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.internal_storage_display_diagnostics), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.sectionHeading())
                            Text(
                                text = "Reserve: ${uiState.lightReserveCount} Light + ${uiState.darkReserveCount} Dark (${uiState.disabledReserveCount} disabled) • Cache Used: ${"%.1f".format(usedMb)} MB / ${"%.0f".format(maxMb)} MB cap",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilterChip(
                            selected = uiState.showLegibilityZonesOverlay,
                            onClick = onToggleLegibilityOverlay,
                            leadingIcon = { Icon(SunloomIcons.Visibility, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text(stringResource(R.string.legibility_overlay)) },
                            modifier = Modifier.desktopControl(RoundedCornerShape(8.dp))
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (usedMb / maxMb.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(100L, 250L, 500L, 1024L).forEach { mb ->
                            val bytes = mb * 1024L * 1024L
                            FilterChip(
                                selected = uiState.preferences.maxCacheBytes == bytes,
                                onClick = { onUpdateMaxCacheBytes(bytes) },
                                label = { Text(if (mb >= 1024L) "${mb / 1024L} GB cap" else "$mb MB cap") }, modifier = Modifier.desktopControl(RoundedCornerShape(8.dp))
                            )
                        }
                    }
                    uiState.capabilities.connectedDisplays.forEach { disp ->
                        Text(
                            text = "Display: ${disp.name} (${disp.resolutionLabel}, ${"%.2f".format(disp.aspectRatio)}:1, ${disp.densityDpi} dpi)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item(key = "diag_reset") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            stringResource(R.string.reset_sunloom),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.sectionHeading()
                        )
                        Text(
                            stringResource(R.string.reset_sunloom_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = { showResetConfirm = true },
                        modifier = Modifier.desktopControl(RoundedCornerShape(50)).testTag("reset_sunloom_button")
                    ) {
                        Text(stringResource(R.string.reset_sunloom), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        if (stockRows.isNotEmpty()) {
            item(key = "diag_sources_header") {
                DiagnosticsListHeader(
                    text = "Reserve by source",
                    color = MaterialTheme.colorScheme.primary
                )
            }
            items(stockRows.size, key = { "diag_src_${stockRows[it].sourceId}" }) { index ->
                val row = stockRows[index]
                val diag = uiState.sourceDiagnostics.firstOrNull { it.sourceId == row.sourceId }
                DiagnosticsListRow(text = sourceStockLine(row), monospace = false)
                sourceSyncLine(diag)?.let { DiagnosticsListRow(text = "    $it", monospace = false, secondary = true) }
            }
            item(key = "diag_sources_gap") { Spacer(Modifier.height(14.dp)) }
        }

        if (rejectionGroups.isNotEmpty()) {
            item(key = "diag_rejection_groups_header") {
                DiagnosticsListHeader(
                    text = "Skipped by source and reason (latest ${uiState.rejectionLog.size})",
                    color = MaterialTheme.colorScheme.error
                )
            }
            items(rejectionGroups.size, key = { "diag_rejgrp_$it" }) { index ->
                DiagnosticsListRow(text = rejectionGroupLine(rejectionGroups[index]), monospace = true)
            }
            item(key = "diag_rejection_groups_gap") { Spacer(Modifier.height(14.dp)) }
        }

        if (uiState.rejectionLog.isNotEmpty()) {
            item(key = "diag_rejections_header") {
                DiagnosticsListHeader(
                    text = "Skipped Candidates (${uiState.rejectionLog.size})",
                    color = MaterialTheme.colorScheme.error
                )
            }
            val shownRejections = uiState.rejectionLog.take(50)
            items(shownRejections.size, key = { "diag_rej_$it" }) { index ->
                val rej = shownRejections[index]
                val scopeTag = when {
                    rej.isPermanent -> "PERMANENT"
                    rej.displaySignature.isBlank() -> "TRANSIENT"
                    else -> "DISPLAY:${rej.displaySignature}"
                }
                DiagnosticsListRow(
                    text = "• [${rej.reasonCode} / $scopeTag] Asset ${rej.assetId} (${rej.sourceId}): ${rej.explanation}",
                    monospace = true
                )
            }
            item(key = "diag_rejections_gap") { Spacer(Modifier.height(14.dp)) }
        }

        item(key = "diag_prepared_header") {
            DiagnosticsListHeader(
                text = "Prepared Reserve (${uiState.libraryWallpapers.size}) • Gate ${SafetyGate.COMPOSITE_GATE_VERSION}",
                color = MaterialTheme.colorScheme.primary
            )
        }
        items(uiState.libraryWallpapers, key = { "diag_rec_${it.id}" }) { rec ->
            DiagnosticsListRow(
                text = "✓ ${rec.title} [${rec.id}] (${rec.provenance.gateLayerOutcomes}): ${rec.decisionExplanation}",
                monospace = false
            )
        }
        item(key = "diag_prepared_gap") { Spacer(Modifier.height(14.dp)) }

        item(key = "diag_about_privacy_card") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.privacy_open_source_attribution),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.privacy_sunloom_operates_locally_on_your),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.licensing_every_remote_wallpaper_is_veri),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsListHeader(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 6.dp)
        )
    }
}

@Composable
private fun DiagnosticsListRow(text: String, monospace: Boolean, secondary: Boolean = false) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            color = if (secondary) MaterialTheme.colorScheme.onSurfaceVariant else androidx.compose.ui.graphics.Color.Unspecified,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 3.dp)
        )
    }
}

/** "Wikimedia: 14 images (9 Light, 3 Dark, 2 Balanced), 10 unseen" - what one source holds. */
internal fun sourceStockLine(row: SourceStockRow): String {
    if (row.total == 0) return "${row.sourceName}: nothing in the reserve"
    val noun = if (row.total == 1) "image" else "images"
    val base = "${row.sourceName}: ${row.total} $noun (${row.light} Light, ${row.dark} Dark, ${row.balanced} Balanced), ${row.unseen} unseen"
    return if (row.isFallback) "$base, fallback, not counted towards the reserve target" else base
}

/** Health, last sync and last error for a source on one line, or null when there is nothing to report. */
internal fun sourceSyncLine(diag: SourceDiagnostics?): String? {
    if (diag == null) return null
    val unhealthy = diag.healthState != SourceHealthState.HEALTHY
    if (!unhealthy && diag.lastSyncSummary.isBlank() && diag.lastError.isBlank()) return null
    return listOfNotNull(
        diag.healthState.label,
        diag.lastSyncSummary.takeIf { it.isNotBlank() }?.let { "Last sync: $it" },
        diag.lastError.takeIf { it.isNotBlank() }
    ).joinToString(" • ")
}

internal fun rejectionGroupLine(group: SourceRejectionGroup): String =
    "${group.sourceId}: ${group.reasonCode} × ${group.count}"
