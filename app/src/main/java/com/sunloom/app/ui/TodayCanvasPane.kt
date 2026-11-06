package com.sunloom.app.ui

import com.sunloom.app.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.runtime.saveable.rememberSaveable
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
 * Byte-bounded LRU cache for downsampled UI preview bitmaps so `WallpaperPreviewSurface`
 * never decodes full-resolution bitmaps on the main UI thread. Entries are keyed by path AND
 * target size, so a 120dp Reserve thumbnail never pays for (or evicts) a 960x600 hero preview.
 */
internal object PreviewBitmapCache {
    /** ~24 MB: a handful of hero previews or a couple hundred thumbnails. */
    const val MAX_BYTES = 24 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount.coerceAtLeast(1)
    }

    internal fun key(path: String, maxW: Int, maxH: Int) = "$path@${maxW}x$maxH"

    fun get(path: String, maxW: Int = DEFAULT_MAX_W, maxH: Int = DEFAULT_MAX_H): Bitmap? = synchronized(cache) {
        cache.get(key(path, maxW, maxH))?.takeIf { !it.isRecycled }
    }

    fun put(path: String, maxW: Int, maxH: Int, bitmap: Bitmap) = synchronized(cache) {
        cache.put(key(path, maxW, maxH), bitmap)
    }

    internal fun clear() = synchronized(cache) { cache.evictAll() }

    internal fun sizeBytes(): Int = synchronized(cache) { cache.size() }

    fun decodeDownsampled(path: String, maxW: Int = DEFAULT_MAX_W, maxH: Int = DEFAULT_MAX_H): Bitmap? {
        if (path.isBlank()) return null
        get(path, maxW, maxH)?.let { return it }
        val file = File(path)
        if (!file.exists() || file.length() == 0L) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val opts = BitmapFactory.Options()
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                opts.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxW, maxH)
            }
            BitmapFactory.decodeFile(path, opts)?.also { put(path, maxW, maxH, it) }
        }.getOrNull()
    }

    /** Smallest power-of-two sample size that fits the source inside [maxW] x [maxH]. */
    internal fun sampleSizeFor(srcW: Int, srcH: Int, maxW: Int, maxH: Int): Int {
        var sample = 1
        while ((srcW / sample) > maxW || (srcH / sample) > maxH) sample *= 2
        return sample
    }

    const val DEFAULT_MAX_W = 960
    const val DEFAULT_MAX_H = 600
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TodayCanvasPane(
    uiState: SunloomUiState,
    onNextWallpaper: () -> Unit,
    onPreviousWallpaper: () -> Unit,
    onToggleKeep: () -> Unit,
    onRequestRejectConfirm: () -> Unit,
    onRestoreOriginal: () -> Unit,
    onSyncOnline: () -> Unit = {},
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {}
) {
    val current = uiState.currentWallpaper
    val scrollState = rememberScrollState()
    var showAbout by rememberSaveable { mutableStateOf(false) }
    val restricted = uiState.isWallpaperChangeRestricted

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val stacked = maxWidth < TodayCanvasStackBreakpoint
        val previewColumn: @Composable (Modifier) -> Unit = { columnModifier ->
            Column(
                modifier = columnModifier,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (restricted) {
                    HostRestrictionNotice()
                }
                val actions = LocalWallpaperActions.current
                ContextMenuHost(
                    modifier = Modifier.fillMaxWidth(),
                    focusShape = RoundedCornerShape(16.dp),
                    menuContent = { dismiss ->
                        if (current != null) {
                            if (!restricted) {
                                ContextMenuItem(stringResource(R.string.next_wallpaper), "ctx_next") { dismiss(); onNextWallpaper() }
                            }
                            if (uiState.previousWallpaperId != null && !restricted) {
                                ContextMenuItem(stringResource(R.string.previous_wallpaper), "ctx_previous") { dismiss(); onPreviousWallpaper() }
                            }
                            ContextMenuItem(
                                if (current.isFavorite) stringResource(R.string.remove_from_favorites) else stringResource(R.string.add_to_favorites),
                                "ctx_keep"
                            ) { dismiss(); onToggleKeep() }
                            ContextMenuItem("About this image\u2026", "ctx_about") { dismiss(); showAbout = true }
                            ContextMenuItem(stringResource(R.string.copy_attribution), "ctx_copy_attribution") {
                                dismiss(); actions.copyAttribution(current)
                            }
                            ContextMenuItem(stringResource(R.string.save_wallpaper_as), "ctx_save_as") {
                                dismiss(); actions.saveAs(current)
                            }
                            if (!restricted) {
                                ContextMenuItem(stringResource(R.string.restore_original_wallpaper), "ctx_restore") { dismiss(); onRestoreOriginal() }
                            }
                            HorizontalDivider()
                            ContextMenuItem(stringResource(R.string.dislike_and_exclude), "ctx_dislike", destructive = true) {
                                dismiss()
                                onRequestRejectConfirm()
                            }
                        }
                    }
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
                                if (current == null) {
                                    Text(
                                        text = stringResource(R.string.nothing_is_showing_yet_fetch_a_batch_of),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Button(
                                        onClick = onSyncOnline,
                                        enabled = !uiState.isSyncingOnline,
                                        modifier = Modifier.desktopControl().testTag("home_empty_fetch")
                                    ) { Text(if (uiState.isSyncingOnline) stringResource(R.string.fetching) else stringResource(R.string.fetch_wallpapers)) }
                                }
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
                                val previewAspect = primaryDisplayAspect(uiState, 0.75f, 2.6f)
                                val zoomState = remember(current.id) { mutableStateOf(ZoomState()) }
                                val previewDescription = stringResource(R.string.today_preview_description)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(previewAspect)
                                        .clip(RoundedCornerShape(12.dp))
                                        .zoomablePreview(zoomState)
                                        .semantics { contentDescription = previewDescription }
                                        .testTag("today_preview")
                                ) {
                                    WallpaperPreviewSurface(
                                        record = current,
                                        showLegibilityZones = uiState.showLegibilityZonesOverlay,
                                        modifier = Modifier.fillMaxSize().applyZoom(zoomState.value)
                                    )
                                }
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
                                    ShortcutTooltip(if (restricted) HostRestrictedTooltip else "Next wallpaper", if (restricted) null else "N") {
                                        Button(
                                            onClick = onNextWallpaper,
                                            enabled = !restricted,
                                            modifier = Modifier.desktopControl().testTag("next_wallpaper_button")
                                        ) {
                                            Icon(
                                                imageVector = if (uiState.isApplyingWallpaper) SunloomIcons.Refresh else SunloomIcons.SkipNext,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(if (uiState.isApplyingWallpaper) stringResource(R.string.applying) else stringResource(R.string.next))
                                        }
                                    }

                                    ShortcutTooltip(if (restricted) HostRestrictedTooltip else "Previous wallpaper", if (restricted) null else "P") {
                                        OutlinedButton(
                                            onClick = onPreviousWallpaper,
                                            enabled = uiState.previousWallpaperId != null && !restricted,
                                            modifier = Modifier.desktopControl().testTag("previous_wallpaper_button")
                                        ) {
                                            Icon(SunloomIcons.SkipPrevious, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(stringResource(R.string.previous))
                                        }
                                    }

                                    ShortcutTooltip(
                                        if (current?.isFavorite == true) stringResource(R.string.remove_from_favorites) else stringResource(R.string.keep_in_favorites),
                                        "K"
                                    ) {
                                        OutlinedIconButton(
                                            onClick = onToggleKeep,
                                            modifier = Modifier.desktopControl(CircleShape).testTag("keep_wallpaper_button")
                                        ) {
                                            Icon(
                                                imageVector = if (current?.isFavorite == true) SunloomIcons.Favorite else SunloomIcons.FavoriteBorder,
                                                contentDescription = if (current?.isFavorite == true) stringResource(R.string.remove_from_favorites) else stringResource(R.string.add_to_favorites),
                                                tint = if (current?.isFavorite == true) Color(0xFFF43F5E) else MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }

                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    ShortcutTooltip(if (restricted) HostRestrictedTooltip else "Restore original wallpaper") {
                                        OutlinedButton(
                                            onClick = onRestoreOriginal,
                                            enabled = !restricted,
                                            modifier = Modifier.desktopControl().testTag("restore_original_button")
                                        ) {
                                            Icon(SunloomIcons.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(stringResource(R.string.restore))
                                        }
                                    }

                                    ShortcutTooltip(if (isFullscreen) stringResource(R.string.exit_fullscreen) else stringResource(R.string.view_fullscreen), "F11") {
                                    OutlinedIconButton(
                                        onClick = onToggleFullscreen,
                                        modifier = Modifier.desktopControl(CircleShape).testTag("fullscreen_button")
                                    ) {
                                        Icon(
                                            imageVector = if (isFullscreen) SunloomIcons.FullscreenExit else SunloomIcons.Fullscreen,
                                            contentDescription = if (isFullscreen) stringResource(R.string.exit_fullscreen) else stringResource(R.string.view_fullscreen),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                ShortcutTooltip(stringResource(R.string.dislike_and_exclude), "Del") {
                                        OutlinedIconButton(
                                            onClick = onRequestRejectConfirm,
                                            modifier = Modifier.desktopControl(CircleShape).testTag("reject_wallpaper_button")
                                        ) {
                                            Icon(
                                                imageVector = SunloomIcons.ThumbDown,
                                                contentDescription = stringResource(R.string.dislike_wallpaper),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        val provenanceCard: @Composable (Modifier) -> Unit = { cardModifier ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = cardModifier
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(SunloomIcons.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            text = stringResource(R.string.about_this_wallpaper),
                            modifier = Modifier.sectionHeading(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (current != null) {
                        ProvenanceDetails(current)
                    }
                }
            }
        }
        val scrollingModifier = Modifier
            .fillMaxSize()
            .desktopScrollbar(scrollState)
            .verticalScroll(scrollState)
        // The scroll container (and its scrollbar) spans the window; only the content is capped and
        // centred so an ultra-wide display does not stretch the preview and cards across the screen.
        val contentModifier = Modifier
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = TodayCanvasMaxWidth)
            .fillMaxWidth()
        Box(modifier = scrollingModifier) {
            if (stacked) {
                Column(
                    modifier = contentModifier,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    previewColumn(Modifier.fillMaxWidth())
                    provenanceCard(Modifier.fillMaxWidth())
                }
            } else {
                Row(
                    modifier = contentModifier.testTag("today_side_by_side"),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    previewColumn(Modifier.weight(1.35f))
                    provenanceCard(Modifier.weight(1f))
                }
            }
        }
    }

    if (showAbout && current != null) {
        WallpaperAboutDialog(record = current, onDismiss = { showAbout = false })
    }
}

/** Creator, source, license, origin and fit details for one wallpaper. */
@Composable
internal fun ProvenanceDetails(current: WallpaperRecord) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
    val actions = LocalWallpaperActions.current
    val noBrowserMessage = stringResource(R.string.no_browser_for_link)
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
                    .then(if (url.startsWith("https://")) Modifier.handCursor() else Modifier)
                    // The 48dp target IS the visual: the focus ring and hover tint are drawn by modifiers
                    // OUTSIDE minimumInteractiveComponentSize, so they wrap the whole hit area and the
                    // cursor target matches what the user sees (Cursor_Target_Size).
                    .desktopFocusRing(RoundedCornerShape(8.dp))
                    .then(if (url.startsWith("https://")) Modifier.desktopHover(RoundedCornerShape(8.dp)) else Modifier)
                    .minimumInteractiveComponentSize()
                    .clickable(
                        enabled = url.startsWith("https://"),
                        onClickLabel = "Open source page in browser",
                        role = androidx.compose.ui.semantics.Role.Button
                    ) {
                        if (url.startsWith("https://")) {
                            // No browser installed (or the host blocks it): say so instead of failing silently.
                            runCatching { uriHandler.openUri(url) }
                                .onFailure { actions.showMessage(noBrowserMessage) }
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
    modifier: Modifier = Modifier,
    maxDecodeWidth: Int = PreviewBitmapCache.DEFAULT_MAX_W,
    maxDecodeHeight: Int = PreviewBitmapCache.DEFAULT_MAX_H
) {
    var bitmap by remember(record.localFilePath, maxDecodeWidth, maxDecodeHeight) {
        mutableStateOf(PreviewBitmapCache.get(record.localFilePath, maxDecodeWidth, maxDecodeHeight))
    }

    LaunchedEffect(record.localFilePath, maxDecodeWidth, maxDecodeHeight) {
        if (bitmap == null && record.localFilePath.isNotBlank()) {
            val decoded = withContext(Dispatchers.IO) {
                PreviewBitmapCache.decodeDownsampled(record.localFilePath, maxDecodeWidth, maxDecodeHeight)
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

/** True once capabilities are probed and the host forbids changing the system wallpaper. */
internal val SunloomUiState.isWallpaperChangeRestricted: Boolean
    get() = !isInitialLoading && (!capabilities.isWallpaperSupported || !capabilities.isSetWallpaperAllowed)

internal const val HostRestrictedTooltip = "Unavailable: system wallpaper changes are restricted on this device"

internal const val HostRestrictionMessage =
    "System wallpaper changes are restricted by this device's host environment. " +
        "You can still browse and preview wallpapers in your reserve."

/** Aspect ratio (w/h) of the largest connected display, defaulting to 16:10, clamped to [min, max]. */
internal fun primaryDisplayAspect(uiState: SunloomUiState, min: Float, max: Float): Float {
    val display = uiState.capabilities.connectedDisplays
        .maxByOrNull { it.widthPx.toLong() * it.heightPx.toLong() }
    return (display?.aspectRatio?.toFloat() ?: (16f / 10f)).coerceIn(min, max)
}

@Composable
internal fun HostRestrictionNotice() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("host_restriction_notice")
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                SunloomIcons.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = HostRestrictionMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}
