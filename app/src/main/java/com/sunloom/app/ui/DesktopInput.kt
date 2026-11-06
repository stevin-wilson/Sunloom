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
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isCtrlPressed as isPointerCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed as isPointerShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.composed
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

/**
 * Lets transient UI (dialogs, menus) hand keyboard focus back to the workspace root when they close,
 * so single-key accelerators keep working without a click. Provided by [SunloomApp].
 */
val LocalRestoreRootFocus = staticCompositionLocalOf<() -> Unit> { {} }

/** Maps Ctrl+1..5 to the workspace tab at that position, or null for any other key. */
internal fun tabForCtrlShortcut(key: Key): DesktopNavTab? {
    val index = when (key) {
        Key.One -> 0
        Key.Two -> 1
        Key.Three -> 2
        Key.Four -> 3
        Key.Five -> 4
        else -> return null
    }
    return DesktopNavTab.entries.getOrNull(index)
}

/**
 * One-shot platform actions that menus deep in the tree can trigger without threading callbacks
 * through every pane. Provided by [SunloomApp].
 */
class WallpaperActions(
    val saveAs: (com.sunloom.app.domain.model.WallpaperRecord) -> Unit = {},
    val copyAttribution: (com.sunloom.app.domain.model.WallpaperRecord) -> Unit = {},
    val removeMany: (Collection<String>) -> Unit = {},
    val showMessage: (String) -> Unit = {}
)

val LocalWallpaperActions = staticCompositionLocalOf { WallpaperActions() }

/** Workspace-level Ctrl chords that are not tab switches. */
internal enum class CtrlAction { UNDO, COPY_ATTRIBUTION, OPEN_SCHEDULE, SAVE_AS }

/**
 * Resolves a Ctrl+key chord to a workspace action, or null when it should pass through (for example
 * Ctrl+Z / Ctrl+C belong to the text field while one is focused).
 */
internal fun ctrlActionFor(
    key: Key,
    shift: Boolean,
    textInputFocused: Boolean,
    hasPendingUndo: Boolean,
    hasCurrentWallpaper: Boolean
): CtrlAction? = when {
    key == Key.Comma && !shift -> CtrlAction.OPEN_SCHEDULE
    textInputFocused -> null
    key == Key.Z && !shift && hasPendingUndo -> CtrlAction.UNDO
    key == Key.C && !shift && hasCurrentWallpaper -> CtrlAction.COPY_ATTRIBUTION
    key == Key.S && !shift && hasCurrentWallpaper -> CtrlAction.SAVE_AS
    else -> null
}

/**
 * Subtle hover feedback for pointer users (Hover_Parity): tints the element while the pointer is over
 * it. The hover state is read only in the draw phase, so hovering never recomposes the content.
 */
@Composable
fun Modifier.desktopHover(shape: Shape = RoundedCornerShape(14.dp)): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered = interactionSource.collectIsHoveredAsState()
    val tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    return this
        .hoverable(interactionSource)
        .drawWithContent {
            drawContent()
            if (hovered.value) {
                drawOutline(outline = shape.createOutline(size, layoutDirection, this), color = tint)
            }
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


/**
 * Reports primary-button (or touch) presses together with the Ctrl/Shift state, without consuming the
 * event, so list items can implement Ctrl+click / Shift+click selection (Input_Combinations).
 */
fun Modifier.pointerPressWithModifiers(
    onPress: (ctrl: Boolean, shift: Boolean, isTouch: Boolean) -> Unit
): Modifier = composed {
    val latest = androidx.compose.runtime.rememberUpdatedState(onPress)
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                if (event.type != androidx.compose.ui.input.pointer.PointerEventType.Press) continue
                val change = event.changes.firstOrNull() ?: continue
                val isTouch = change.type == androidx.compose.ui.input.pointer.PointerType.Touch
                if (isTouch || event.buttons.isPrimaryPressed) {
                    latest.value(
                        event.keyboardModifiers.isPointerCtrlPressed,
                        event.keyboardModifiers.isPointerShiftPressed,
                        isTouch
                    )
                }
            }
        }
    }
}


/**
 * Shows the system "busy" pointer over non-interactive areas while a long operation (applying a
 * wallpaper, syncing catalogs) runs, so pointer users get progress feedback (Custom_Cursors).
 * Interactive controls keep their own hand cursor.
 */
@Composable
fun Modifier.busyCursor(busy: Boolean): Modifier {
    if (!busy) return this
    val context = androidx.compose.ui.platform.LocalContext.current
    val icon = remember(context) {
        runCatching {
            android.view.PointerIcon.getSystemIcon(context, android.view.PointerIcon.TYPE_WAIT)
                ?.let { androidx.compose.ui.input.pointer.PointerIcon(it) }
        }.getOrNull()
    }
    return if (icon == null) this else this.pointerHoverIcon(icon)
}
