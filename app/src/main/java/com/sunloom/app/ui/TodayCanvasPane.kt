package com.sunloom.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.WallpaperRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Bounded LRU cache for downsampled UI preview bitmaps so `WallpaperPreviewSurface`
 * never decodes full-resolution bitmaps on the main UI thread.
 */
internal object PreviewBitmapCache {
    private val cache = object : LruCache<String, Bitmap>(16) {}

    fun get(path: String): Bitmap? = synchronized(cache) {
        cache.get(path)?.takeIf { !it.isRecycled }
    }

    fun put(path: String, bitmap: Bitmap) = synchronized(cache) {
        cache.put(path, bitmap)
    }

    fun decodeDownsampled(path: String, maxW: Int = 960, maxH: Int = 600): Bitmap? {
        if (path.isBlank()) return null
        get(path)?.let { return it }
        val file = File(path)
        if (!file.exists() || file.length() == 0L) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val opts = BitmapFactory.Options()
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                var sample = 1
                while ((bounds.outWidth / sample) > maxW || (bounds.outHeight / sample) > maxH) {
                    sample *= 2
                }
                opts.inSampleSize = sample
            }
            BitmapFactory.decodeFile(path, opts)?.also { put(path, it) }
        }.getOrNull()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TodayCanvasPane(
    uiState: SunloomUiState,
    onNextWallpaper: () -> Unit,
    onPreviousWallpaper: () -> Unit,
    onToggleKeep: () -> Unit,
    onRequestRejectConfirm: () -> Unit,
    onRestoreOriginal: () -> Unit
) {
    val current = uiState.currentWallpaper
    val scrollState = rememberScrollState()

    Row(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(
            modifier = Modifier.weight(1.35f),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        TruncatedTitleWithTooltip(
                            title = current?.title ?: "No Wallpaper Selected",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (current != null) {
                            Text(
                                text = "${current.category.displayName} • ${current.provenance.sourceName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (current != null) {
                        val activeDisplay = uiState.capabilities.connectedDisplays
                            .maxByOrNull { it.widthPx.toLong() * it.heightPx.toLong() }
                        val previewAspect = (activeDisplay?.aspectRatio?.toFloat() ?: (16f / 10f))
                            .coerceIn(0.75f, 2.6f)
                        WallpaperPreviewSurface(
                            record = current,
                            showLegibilityZones = uiState.showLegibilityZonesOverlay,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(previewAspect)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onNextWallpaper,
                                enabled = !uiState.isSyncingOnline,
                                modifier = Modifier.testTag("next_wallpaper_button")
                            ) {
                                Icon(
                                    imageVector = if (uiState.isSyncingOnline) SunloomIcons.Refresh else SunloomIcons.SkipNext,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(if (uiState.isSyncingOnline) "Updating..." else "Next")
                            }

                            OutlinedButton(
                                onClick = onPreviousWallpaper,
                                enabled = uiState.previousWallpaperId != null,
                                modifier = Modifier.testTag("previous_wallpaper_button")
                            ) {
                                Icon(SunloomIcons.SkipPrevious, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Previous")
                            }

                            OutlinedIconButton(
                                onClick = onToggleKeep,
                                modifier = Modifier.testTag("keep_wallpaper_button")
                            ) {
                                Icon(
                                    imageVector = if (current?.isFavorite == true) SunloomIcons.Favorite else SunloomIcons.FavoriteBorder,
                                    contentDescription = if (current?.isFavorite == true) "Remove from favorites" else "Add to favorites",
                                    tint = if (current?.isFavorite == true) Color(0xFFF43F5E) else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onRestoreOriginal,
                                modifier = Modifier.testTag("restore_original_button")
                            ) {
                                Icon(SunloomIcons.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Restore")
                            }

                            OutlinedIconButton(
                                onClick = onRequestRejectConfirm,
                                modifier = Modifier.testTag("reject_wallpaper_button")
                            ) {
                                Icon(
                                    imageVector = SunloomIcons.ThumbDown,
                                    contentDescription = "Dislike wallpaper",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.weight(1f)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(SunloomIcons.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "About this wallpaper",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (current != null) {
                    ProvenanceRow("Creator", current.provenance.creatorName)
                    ProvenanceRow("Source", current.provenance.sourceName)
                    val licenseLabel = if (current.provenance.licenseVersion.isNotBlank()) {
                        "${current.provenance.licenseType.label} (v${current.provenance.licenseVersion})"
                    } else {
                        current.provenance.licenseType.label
                    }
                    if (current.provenance.licenseUrl.isNotBlank()) {
                        ProvenanceLinkRow(
                            label = "License",
                            displayText = licenseLabel,
                            url = current.provenance.licenseUrl
                        )
                    } else {
                        ProvenanceRow("License", licenseLabel)
                    }
                    if (current.provenance.originalPageUrl.isNotBlank()) {
                        ProvenanceLinkRow(
                            label = "Source page",
                            displayText = current.provenance.originalPageUrl,
                            url = current.provenance.originalPageUrl
                        )
                    } else {
                        ProvenanceRow("Origin", "On-device")
                    }
                    ProvenanceRow(
                        "Fit & Resolution",
                        "Cropped without stretching (${current.provenance.originalWidth} × ${current.provenance.originalHeight} → ${current.provenance.renditionWidth} × ${current.provenance.renditionHeight})"
                    )
                }
            }
        }
    }
}

@Composable
internal fun ProvenanceRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION")
@Composable
internal fun ProvenanceLinkRow(label: String, displayText: String, url: String) {
    val uriHandler = LocalUriHandler.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("Open in browser: $url") } },
            state = rememberTooltipState()
        ) {
            Text(
                text = displayText.ifBlank { "Local / Offline Asset" },
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = if (url.startsWith("https://")) TextDecoration.Underline else TextDecoration.None,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .testTag("source_page_link")
                    .clickable(enabled = url.startsWith("https://")) {
                        if (url.startsWith("https://")) {
                            runCatching { uriHandler.openUri(url) }
                        }
                    }
            )
        }
    }
}

@Composable
fun WallpaperPreviewSurface(
    record: WallpaperRecord,
    showLegibilityZones: Boolean,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(record.localFilePath) {
        mutableStateOf(PreviewBitmapCache.get(record.localFilePath))
    }

    LaunchedEffect(record.localFilePath) {
        if (bitmap == null && record.localFilePath.isNotBlank()) {
            val decoded = withContext(Dispatchers.IO) {
                PreviewBitmapCache.decodeDownsampled(record.localFilePath)
            }
            bitmap = decoded
        }
    }

    val isDark = record.evaluation.brightnessClass == BrightnessClass.DARK
    val fallbackBrush = if (isDark) {
        Brush.verticalGradient(listOf(Color(0xFF0B1120), Color(0xFF1E1B4B), Color(0xFF0F172A)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFBAE6FD), Color(0xFFE0F2FE), Color(0xFFFDE68A)))
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(fallbackBrush)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
    ) {
        val safeBmp = bitmap
        if (safeBmp != null && !safeBmp.isRecycled) {
            Image(
                bitmap = safeBmp.asImageBitmap(),
                contentDescription = record.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (showLegibilityZones) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                drawRect(
                    color = Color(0x5538BDF8),
                    topLeft = Offset(0f, 0f),
                    size = Size(w, h * 0.10f)
                )
                drawRect(
                    color = Color(0x55F59E0B),
                    topLeft = Offset(w * 0.25f, h * 0.10f),
                    size = Size(w * 0.50f, h * 0.28f)
                )
                drawRect(
                    color = Color(0x5538BDF8),
                    topLeft = Offset(0f, h * 0.88f),
                    size = Size(w, h * 0.12f)
                )
                drawRect(
                    color = Color(0x44818CF8),
                    topLeft = Offset(0f, h * 0.10f),
                    size = Size(w * 0.15f, h * 0.78f)
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp)
                    .background(Color(0xAA0F172A), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Legibility Zones (Clock Y=${"%.2f".format(record.evaluation.lockClockZoneLuminance)} • Shelf Y=${"%.2f".format(record.evaluation.desktopZoneLuminance)})",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }
        }
    }
}
