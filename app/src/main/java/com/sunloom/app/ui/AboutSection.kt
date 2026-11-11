package com.sunloom.app.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sunloom.app.BuildConfig
import com.sunloom.app.R
import com.sunloom.app.update.UpdateCheckResult
import com.sunloom.app.update.updateCheckFeature

/**
 * The first things on Storage & About (Article XII §1): what this is, which version, what it is licensed under and built
 * from, where the privacy policy and the code are. Someone who installed it from GitHub has no store page to learn from.
 */
internal fun LazyListScope.aboutItems(updateCheck: UpdateCheckUiState, onCheckForUpdates: () -> Unit) {
    item(key = "diag_about") { AboutCard(updateCheck, onCheckForUpdates) }
    item(key = "diag_privacy") { PrivacyCard() }
}

@Composable
private fun AboutCard(updateCheck: UpdateCheckUiState, onCheckForUpdates: () -> Unit) {
    val uris = LocalUriHandler.current
    var showLicenses by remember { mutableStateOf(false) }
    // Only the GitHub build has an update check; the Play build is updated by the store.
    val canCheckForUpdates = remember { updateCheckFeature() != null }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp).testTag("about_card")
    ) {
        Column(modifier = Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(
                stringResource(R.string.about_sunloom),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.sectionHeading()
            )
            Text(
                stringResource(R.string.about_version_line, BuildConfig.VERSION_NAME, BuildConfig.FLAVOR),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("about_version")
            )
            Text(
                stringResource(R.string.about_licence_line),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                LinkButton(R.string.privacy_policy, "about_privacy_policy") {
                    runCatching { uris.openUri(ProjectLinks.PRIVACY_POLICY) }
                }
                LinkButton(R.string.source_code, "about_source_code") {
                    runCatching { uris.openUri(ProjectLinks.SOURCE) }
                }
                LinkButton(R.string.open_source_licenses, "about_licenses") { showLicenses = true }
            }
            if (canCheckForUpdates) UpdateCheckRow(updateCheck, onCheckForUpdates) { url -> runCatching { uris.openUri(url) } }
        }
    }
    if (showLicenses) LicensesDialog(onDismiss = { showLicenses = false })
}

@Composable
private fun LinkButton(label: Int, tag: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.desktopControl(CircleShape).testTag(tag)) {
        Text(stringResource(label))
    }
}

/** The GitHub build's "Check for updates", and what it found in plain words. Never offers to install anything. */
@Composable
private fun UpdateCheckRow(state: UpdateCheckUiState, onCheck: () -> Unit, openPage: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        when (state) {
            UpdateCheckUiState.Checking -> Text(stringResource(R.string.update_checking), style = MaterialTheme.typography.bodySmall)
            is UpdateCheckUiState.Done -> when (val result = state.result) {
                is UpdateCheckResult.Available -> {
                    Text(
                        stringResource(R.string.update_available, result.version),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    LinkButton(R.string.update_view_release, "about_view_release") { openPage(result.pageUrl) }
                }
                is UpdateCheckResult.UpToDate -> {
                    Text(stringResource(R.string.update_up_to_date, result.version), style = MaterialTheme.typography.bodySmall)
                    LinkButton(R.string.check_for_updates, "about_check_updates", onCheck)
                }
                UpdateCheckResult.Failed -> {
                    Text(
                        stringResource(R.string.update_check_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    LinkButton(R.string.check_for_updates, "about_check_updates", onCheck)
                }
            }
            UpdateCheckUiState.Idle -> LinkButton(R.string.check_for_updates, "about_check_updates", onCheck)
        }
    }
}

@Composable
private fun PrivacyCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp).testTag("privacy_card")
    ) {
        Column(modifier = Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(
                stringResource(R.string.privacy_open_source_attribution),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.sectionHeading()
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

/** What Sunloom is built from and licensed under, from the curated file the build checks (see OpenSourceLicensesTest). */
@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    val resources = LocalResources.current
    // A 13 KB packaged resource, read once when the dialog opens: no more work than showing a string, and none of the
    // disk, database or decoding work the main thread must avoid.
    val text = remember {
        runCatching { resources.openRawResource(R.raw.open_source_licenses).bufferedReader().use { it.readText() } }
            .getOrDefault("")
    }
    RestoreRootFocusOnDispose()
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.dismissOnEscape(onDismiss),
        title = { Text(stringResource(R.string.open_source_licenses)) },
        text = {
            val scroll = rememberScrollState()
            SelectionContainer {
                Text(
                    text = text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .heightIn(max = 380.dp)
                        .desktopScrollbar(scroll)
                        .verticalScroll(scroll)
                        .testTag("licenses_text")
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.desktopControl()) { Text(stringResource(R.string.close)) } }
    )
}
