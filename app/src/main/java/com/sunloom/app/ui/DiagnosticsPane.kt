package com.sunloom.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sunloom.app.domain.safety.SafetyGate

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DiagnosticsPane(
    uiState: SunloomUiState,
    onToggleLegibilityOverlay: () -> Unit = {},
    onUpdateMaxCacheBytes: (Long) -> Unit = {}
) {
    val usedMb = uiState.totalCacheBytesUsed.toDouble() / (1024.0 * 1024.0)
    val maxMb = uiState.preferences.maxCacheBytes.toDouble() / (1024.0 * 1024.0)

    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().desktopScrollbar(listState),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "diag_header") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Internal Storage & Display Diagnostics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                            label = { Text("Legibility Overlay") }
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
                                label = { Text("$mb MB cap") }
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

        if (uiState.rejectionLog.isNotEmpty()) {
            item(key = "diag_rejections_card") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Skipped Candidates (${uiState.rejectionLog.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        uiState.rejectionLog.take(50).forEach { rej ->
                            val scopeTag = if (rej.isPermanent) "PERMANENT" else "DISPLAY:${rej.displaySignature}"
                            Text(
                                text = "• [${rej.reasonCode} / $scopeTag] Asset ${rej.assetId} (${rej.sourceId}): ${rej.explanation}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        item(key = "diag_prepared_card") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Prepared Reserve (${uiState.libraryWallpapers.size}) • Gate ${SafetyGate.COMPOSITE_GATE_VERSION}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    uiState.libraryWallpapers.forEach { rec ->
                        Text(
                            text = "✓ ${rec.title} [${rec.id}] (${rec.provenance.gateLayerOutcomes}): ${rec.decisionExplanation}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        item(key = "diag_about_privacy_card") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Privacy & Open-Source Attribution",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "• Privacy: Sunloom operates locally on your device without user accounts, analytics SDKs, advertising IDs, or telemetry. Network connections are made exclusively over HTTPS to the public-domain and Creative Commons sources you enable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "• Licensing: Every remote wallpaper is verified for CC0, Public Domain, CC BY (2.0–4.0), or CC BY-SA (2.0–4.0) terms prior to admission.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
