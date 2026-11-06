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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed as isKeyCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
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

/** Keyboard zoom: one step multiplies the scale by this much. */
internal const val KEY_ZOOM_STEP = 1.25f

internal enum class ZoomKeyAction { IN, OUT, RESET }

/** Ctrl+= / Ctrl++ zoom in, Ctrl+- zoom out, Ctrl+0 resets. Shift is allowed because "+" needs it on most layouts. */
internal fun zoomKeyActionFor(key: Key, ctrl: Boolean): ZoomKeyAction? {
    if (!ctrl) return null
    return when (key) {
        Key.Equals, Key.Plus, Key.NumPadAdd -> ZoomKeyAction.IN
        Key.Minus, Key.NumPadSubtract -> ZoomKeyAction.OUT
        Key.Zero, Key.NumPad0 -> ZoomKeyAction.RESET
        else -> null
    }
}

internal fun applyZoomKey(state: ZoomState, action: ZoomKeyAction, size: IntSize): ZoomState {
    val center = Offset(size.width / 2f, size.height / 2f)
    return when (action) {
        ZoomKeyAction.IN -> zoomAround(state, KEY_ZOOM_STEP, center, size)
        ZoomKeyAction.OUT -> zoomAround(state, 1f / KEY_ZOOM_STEP, center, size)
        ZoomKeyAction.RESET -> ZoomState()
    }
}

/** Arrow keys pan a zoomed preview by a tenth of its size; null when the key does not pan. */
internal fun panDeltaForKey(key: Key, size: IntSize): Offset? {
    val dx = size.width * 0.1f
    val dy = size.height * 0.1f
    return when (key) {
        Key.DirectionLeft -> Offset(dx, 0f)
        Key.DirectionRight -> Offset(-dx, 0f)
        Key.DirectionUp -> Offset(0f, dy)
        Key.DirectionDown -> Offset(0f, -dy)
        else -> null
    }
}

private fun KeyEvent.hasAltOrMeta() = isAltPressed || isMetaPressed

/**
 * Content_Zoom: Ctrl + mouse wheel and trackpad pinch (delivered as Ctrl+scroll on desktop) zoom the
 * content under the pointer, two-finger touch pinch zooms and pans, and a double click/tap resets.
 * The focused preview also zooms with Ctrl+= / Ctrl+- / Ctrl+0 and pans with the arrows once zoomed.
 * Plain wheel scrolling is untouched so the page still scrolls.
 */
internal fun Modifier.zoomablePreview(state: MutableState<ZoomState>): Modifier = composed {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val focus = remember { FocusRequester() }
    this
        .onSizeChanged { size = it }
        .focusRequester(focus)
        .desktopFocusRing(RoundedCornerShape(12.dp))
        .focusable()
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || event.hasAltOrMeta()) return@onKeyEvent false
            val action = zoomKeyActionFor(event.key, event.isKeyCtrlPressed)
            if (action != null) {
                state.value = applyZoomKey(state.value, action, size)
                return@onKeyEvent true
            }
            // Arrows only belong to the preview while it is zoomed; at 1x they stay free for focus movement.
            val pan = if (!event.isKeyCtrlPressed && state.value.scale > MIN_PREVIEW_ZOOM) panDeltaForKey(event.key, size) else null
            if (pan != null) {
                state.value = panBy(state.value, pan, size)
                true
            } else {
                false
            }
        }
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
            detectTapGestures(
                onTap = { focus.requestFocus() },
                onDoubleTap = { state.value = ZoomState() }
            )
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
