package com.sunloom.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.exp

/** Scale and translation of a zoomed preview. Translation is in px and applied after scaling. */
internal data class ZoomState(val scale: Float = 1f, val translation: Offset = Offset.Zero)

internal const val MIN_PREVIEW_ZOOM = 1f
internal const val MAX_PREVIEW_ZOOM = 5f

/** Multiplier for one mouse-wheel notch: wheel up (negative delta) zooms in. */
internal fun scrollZoomFactor(scrollDeltaY: Float): Float = exp(-scrollDeltaY * 0.12f)

/** Keeps the zoomed content covering the viewport (no empty edges). */
internal fun clampZoom(state: ZoomState, size: IntSize): ZoomState {
    val scale = state.scale.coerceIn(MIN_PREVIEW_ZOOM, MAX_PREVIEW_ZOOM)
    if (scale <= MIN_PREVIEW_ZOOM) return ZoomState()
    val minX = size.width * (1f - scale)
    val minY = size.height * (1f - scale)
    return ZoomState(
        scale = scale,
        translation = Offset(
            state.translation.x.coerceIn(minX, 0f),
            state.translation.y.coerceIn(minY, 0f)
        )
    )
}

/** Zooms by [factor] keeping the content point under [focal] fixed, then clamps. */
internal fun zoomAround(state: ZoomState, factor: Float, focal: Offset, size: IntSize): ZoomState {
    val target = (state.scale * factor).coerceIn(MIN_PREVIEW_ZOOM, MAX_PREVIEW_ZOOM)
    val ratio = target / state.scale
    val translation = focal - (focal - state.translation) * ratio
    return clampZoom(ZoomState(target, translation), size)
}

internal fun panBy(state: ZoomState, delta: Offset, size: IntSize): ZoomState =
    if (state.scale <= MIN_PREVIEW_ZOOM) state else clampZoom(state.copy(translation = state.translation + delta), size)

/**
 * Content_Zoom: Ctrl + mouse wheel and trackpad pinch (delivered as Ctrl+scroll on desktop) zoom the
 * content under the pointer, two-finger touch pinch zooms and pans, and a double click/tap resets.
 * Plain wheel scrolling is untouched so the page still scrolls.
 */
internal fun Modifier.zoomablePreview(state: MutableState<ZoomState>): Modifier = composed {
    var size by remember { mutableStateOf(IntSize.Zero) }
    this
        .onSizeChanged { size = it }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Scroll && event.keyboardModifiers.isCtrlPressed) {
                        val change = event.changes.first()
                        state.value = zoomAround(state.value, scrollZoomFactor(change.scrollDelta.y), change.position, size)
                        change.consume()
                    }
                }
            }
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.count { it.pressed } >= 2) {
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (zoom != 1f || pan != Offset.Zero) {
                            val zoomed = zoomAround(state.value, zoom, event.calculateCentroid(), size)
                            state.value = panBy(zoomed, pan, size)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    }
                } while (event.changes.any { it.pressed && !it.changedToUp() })
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(onDoubleTap = { state.value = ZoomState() })
        }
}

/** Applies [state] as a top-left-origin scale and translation. */
internal fun Modifier.applyZoom(state: ZoomState): Modifier = graphicsLayer {
    transformOrigin = TransformOrigin(0f, 0f)
    scaleX = state.scale
    scaleY = state.scale
    translationX = state.translation.x
    translationY = state.translation.y
}
