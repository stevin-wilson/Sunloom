package com.sunloom.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Draws a visible, non-interactive scrollbar thumb on the right edge of a [verticalScroll][androidx.compose.foundation.verticalScroll]
 * container. Place this modifier BEFORE `verticalScroll` so it measures the viewport, not the content.
 */
@Composable
fun Modifier.desktopScrollbar(state: ScrollState): Modifier =
    scrollbarCore(isScrolling = { state.isScrollInProgress }) { viewport, minThumb ->
        // maxValue is Int.MAX_VALUE until the first measure pass.
        if (state.maxValue == Int.MAX_VALUE) {
            null
        } else {
            scrollbarThumb(viewport, viewport + state.maxValue, state.value.toFloat(), minThumb)
        }
    }

/**
 * Scrollbar for a LazyColumn. Total content is estimated from the average size of the currently
 * visible items, so the thumb size can shift slightly as differently sized items scroll into view.
 */
@Composable
fun Modifier.desktopScrollbar(state: LazyListState): Modifier =
    scrollbarCore(isScrolling = { state.isScrollInProgress }) { viewport, minThumb ->
        val info = state.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty() || info.totalItemsCount == 0) {
            null
        } else {
            val average = visible.sumOf { it.size }.toFloat() / visible.size
            val content = average * info.totalItemsCount
            val offset = state.firstVisibleItemIndex * average + state.firstVisibleItemScrollOffset
            scrollbarThumb(viewport, content, offset, minThumb)
        }
    }

/**
 * Scrollbar for a LazyVerticalGrid. Rows are estimated from the visible cells: cells sharing a top
 * offset form one row, so the thumb stays proportional regardless of the column count.
 */
@Composable
fun Modifier.desktopScrollbar(state: LazyGridState): Modifier =
    scrollbarCore(isScrolling = { state.isScrollInProgress }) { viewport, minThumb ->
        val info = state.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty() || info.totalItemsCount == 0) {
            null
        } else {
            val rowsVisible = visible.map { it.offset.y }.distinct().size.coerceAtLeast(1)
            val perRow = visible.size.toFloat() / rowsVisible
            val rowHeight = visible.sumOf { it.size.height }.toFloat() / visible.size
            val content = rowHeight * (info.totalItemsCount / perRow)
            val offset = (state.firstVisibleItemIndex / perRow) * rowHeight + state.firstVisibleItemScrollOffset
            scrollbarThumb(viewport, content, offset, minThumb)
        }
    }

private val ScrollbarThickness: Dp = 8.dp
private val ScrollbarMinThumb: Dp = 36.dp
private const val SCROLLBAR_IDLE_ALPHA = 0.4f
private const val SCROLLBAR_ACTIVE_ALPHA = 0.9f

@Composable
private fun Modifier.scrollbarCore(
    isScrolling: () -> Boolean,
    thumbProvider: (viewportPx: Float, minThumbPx: Float) -> ScrollbarThumb?
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    // Keep hover/scroll/alpha as State objects that are only read in the draw phase, so pointer
    // enter/exit and scroll start/stop repaint the thumb without recomposing the whole pane.
    val hovered = interactionSource.collectIsHoveredAsState()
    val alpha = remember { Animatable(SCROLLBAR_IDLE_ALPHA) }
    val currentIsScrolling by rememberUpdatedState(isScrolling)
    LaunchedEffect(Unit) {
        snapshotFlow { currentIsScrolling() || hovered.value }
            .distinctUntilChanged()
            .collect { active ->
                alpha.animateTo(if (active) SCROLLBAR_ACTIVE_ALPHA else SCROLLBAR_IDLE_ALPHA)
            }
    }
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    return this
        .hoverable(interactionSource)
        .drawWithContent {
            drawContent()
            val thickness = ScrollbarThickness.toPx()
            val thumb = thumbProvider(size.height, ScrollbarMinThumb.toPx()) ?: return@drawWithContent
            drawRoundRect(
                color = color.copy(alpha = alpha.value),
                topLeft = Offset(size.width - thickness - 2.dp.toPx(), thumb.top),
                size = Size(thickness, thumb.height),
                cornerRadius = CornerRadius(thickness / 2f)
            )
        }
}
