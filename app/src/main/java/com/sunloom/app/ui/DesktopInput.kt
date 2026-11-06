package com.sunloom.app.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp

/**
 * Tracks whether any text input currently holds focus so single-key accelerators
 * (N / P / K / Delete) never fire while the user is typing. Lives in the UI layer only;
 * it is intentionally not part of [SunloomUiState].
 */
@Stable
class ShortcutGate {
    private var focusedFields by mutableIntStateOf(0)

    val isTextInputFocused: Boolean get() = focusedFields > 0

    internal fun acquire() {
        focusedFields++
    }

    internal fun release() {
        if (focusedFields > 0) focusedFields--
    }
}

val LocalShortcutGate = staticCompositionLocalOf { ShortcutGate() }

/**
 * Marks this element as a text input. While it (or a descendant) has focus, root
 * accelerators are suppressed. Safe if the element leaves composition while focused.
 */
@Composable
fun Modifier.suppressShortcutsWhileFocused(): Modifier {
    val gate = LocalShortcutGate.current
    var held by remember(gate) { mutableStateOf(false) }
    DisposableEffect(gate) {
        onDispose {
            if (held) {
                held = false
                gate.release()
            }
        }
    }
    return this.onFocusChanged { state ->
        if (state.hasFocus != held) {
            held = state.hasFocus
            if (held) gate.acquire() else gate.release()
        }
    }
}

/** True when no Ctrl/Alt/Meta modifier is down, so chords like Ctrl+K or Alt+N are never hijacked. */
internal fun isPlainKeyEvent(event: KeyEvent): Boolean =
    !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed

/** Calls [onDismiss] when Escape is released, so desktop users can close dialogs and overlays from the keyboard. */
fun Modifier.dismissOnEscape(onDismiss: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.key == Key.Escape) {
        if (event.type == KeyEventType.KeyUp) onDismiss()
        true
    } else {
        false
    }
}

/** Hand cursor over clickable, non-text controls (cards, buttons, links). */
fun Modifier.handCursor(): Modifier = pointerHoverIcon(PointerIcon.Hand)

/**
 * Draws a 2dp focus ring in the theme primary colour around this element whenever it or
 * a descendant holds keyboard focus (or only the element itself when [selfOnly]). Place on a wrapper
 * outside the focusable control.
 */
@Composable
fun Modifier.desktopFocusRing(shape: Shape = RoundedCornerShape(8.dp), selfOnly: Boolean = false): Modifier {
    var hasFocus by remember { mutableStateOf(false) }
    val ringColor = MaterialTheme.colorScheme.primary
    return this
        .onFocusChanged { hasFocus = if (selfOnly) it.isFocused else it.hasFocus }
        .drawWithContent {
            drawContent()
            if (hasFocus) {
                drawOutline(
                    outline = shape.createOutline(size, layoutDirection, this),
                    color = ringColor,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
}

/** Standard desktop affordances for an interactive control: hand cursor plus a visible keyboard focus ring. */
@Composable
fun Modifier.desktopControl(shape: Shape = RoundedCornerShape(50)): Modifier =
    this.handCursor().desktopFocusRing(shape)

/** WCAG contrast ratio between two opaque colours (1.0 .. 21.0). */
internal fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    val lighter = maxOf(la, lb)
    val darker = minOf(la, lb)
    return (lighter + 0.05f) / (darker + 0.05f)
}

/** Tooltip showing an action name and, when present, its keyboard shortcut, e.g. "Next wallpaper (N)". */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION")
@Composable
fun ShortcutTooltip(
    label: String,
    shortcut: String? = null,
    content: @Composable () -> Unit
) {
    val text = shortcutTooltipText(label, shortcut)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState()
    ) {
        content()
    }
}

internal fun shortcutTooltipText(label: String, shortcut: String?): String =
    if (shortcut.isNullOrBlank()) label else "$label ($shortcut)"
