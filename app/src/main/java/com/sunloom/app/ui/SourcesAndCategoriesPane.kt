package com.sunloom.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SourceHealthState

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourcesAndCategoriesPane(
    uiState: SunloomUiState,
    onToggleCategory: (SafeCategory) -> Unit,
    onToggleSource: (String) -> Unit,
    onToggleWifiOnly: (Boolean) -> Unit,
    onToggleBatterySaverDeferral: (Boolean) -> Unit = {},
    onSyncOnline: () -> Unit
) {
    val scrollState = rememberScrollState()
    val diagMap = remember(uiState.sourceDiagnostics) {
        uiState.sourceDiagnostics.associateBy { it.sourceId }
    }

    val admittedRegistrations = remember {
        com.sunloom.app.data.sources.SourceRegistry.admittedSources()
    }
    val sourceCatalog = remember(admittedRegistrations) {
        admittedRegistrations.map { reg ->
            Triple(reg.sourceId, reg.sourceName, reg.plainDescription)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Wallpaper Sources", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Online and user sources always take priority over the offline on-device reserve.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                sourceCatalog.forEach { (id, title, desc) ->
                    val diag = diagMap[id]
                    val isUnhealthy = diag != null && diag.healthState != SourceHealthState.HEALTHY
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                if (isUnhealthy) {
                                    Text(
                                        text = "• ${diag.healthState.label}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (diag != null && diag.lastError.isNotBlank()) {
                                Text(
                                    text = diag.lastError,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                        Switch(
                            checked = id in uiState.preferences.enabledSources,
                            onCheckedChange = { onToggleSource(id) }
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
                        Text("Unmetered Wi-Fi or Ethernet only", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("Pause background downloads when connected to a metered mobile hotspot.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = uiState.preferences.wifiOnlyDownloads,
                        onCheckedChange = onToggleWifiOnly
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Pause downloads on Battery Saver", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Defer background catalog refreshes when Battery Saver is active.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.preferences.deferOnBatterySaver,
                        onCheckedChange = onToggleBatterySaverDeferral
                    )
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Categories", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SafeCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = cat in uiState.preferences.enabledCategories,
                            onClick = { onToggleCategory(cat) },
                            label = { Text(cat.displayName) }
                        )
                    }
                }
                Button(onClick = onSyncOnline, enabled = !uiState.isSyncingOnline, modifier = Modifier.testTag("sync_online_button")) {
                    Icon(SunloomIcons.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (uiState.isSyncingOnline) "Syncing..." else "Sync Catalog Now")
                }
            }
        }
    }
}
