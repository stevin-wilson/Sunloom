package com.sunloom.app.ui

import com.sunloom.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.sunloom.app.domain.model.WallpaperRecord

/**
 * Calls [onOpen] (with the pointer position inside this element) on a mouse/trackpad secondary
 * click or a touch long-press. Does not consume events from child controls. [onOpen] is captured
 * once, so it should only write to remembered state (as [ContextMenuHost] does).
 */
fun Modifier.secondaryClickMenu(onOpen: (Offset) -> Unit): Modifier = this
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    onOpen(event.changes.first().position)
                }
            }
        }
    }
    .pointerInput(Unit) {
        detectTapGestures(onLongPress = { onOpen(it) })
    }

/**
 * Wraps [content] so it can be right-clicked (or long-pressed, or opened from the keyboard with the
 * Menu key / Shift+F10 while it is focused) to show a desktop context menu at the pointer.
 */
@Composable
fun ContextMenuHost(
    modifier: Modifier = Modifier,
    focusShape: Shape = MaterialTheme.shapes.medium,
    onActivate: (() -> Unit)? = null,
    onExtraKeyEvent: ((androidx.compose.ui.input.key.KeyEvent) -> Boolean)? = null,
    menuContent: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(Offset.Zero) }
    var heightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val openFromKeyboard = rememberUpdatedState { anchor = Offset.Zero; expanded = true }
    val activate = rememberUpdatedState(onActivate)
    val extraKey = rememberUpdatedState(onExtraKeyEvent)

    Box(
        modifier = modifier
            .onSizeChanged { heightPx = it.height }
            .desktopFocusRing(focusShape, selfOnly = true)
            .desktopHover(focusShape)
            .onKeyEvent { event ->
                if (extraKey.value?.invoke(event) == true) return@onKeyEvent true
                val isMenuChord = event.key == Key.Menu || (event.isShiftPressed && event.key == Key.F10)
                val isActivate = (event.key == Key.Enter || event.key == Key.NumPadEnter) && activate.value != null
                if (isMenuChord) {
                    if (event.type == KeyEventType.KeyUp) openFromKeyboard.value()
                    true
                } else if (isActivate) {
                    if (event.type == KeyEventType.KeyUp) activate.value?.invoke()
                    true
                } else {
                    false
                }
            }
            .focusable()
            .pointerInput(Unit) {
                // Double-click (or double-tap) applies; single clicks stay free for the inner controls.
                detectTapGestures(onDoubleTap = { activate.value?.invoke() })
            }
            .secondaryClickMenu { position ->
                anchor = position
                expanded = true
            }
    ) {
        content()
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            // DropdownMenu anchors below the Box; shift it up so the menu opens at the pointer.
            offset = with(density) { DpOffset(anchor.x.toDp(), (anchor.y - heightPx).toDp()) },
            modifier = Modifier.dismissOnEscape { expanded = false }
        ) {
            menuContent { expanded = false }
        }
    }
}

@Composable
fun ContextMenuItem(
    text: String,
    testTag: String,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Text(
                text = text,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        },
        onClick = onClick,
        modifier = Modifier.handCursor().testTag(testTag)
    )
}

/**
 * Confirmation for destructive actions. Focus starts on Cancel so Enter/Space cannot confirm by
 * accident, and Escape cancels (Constitution Art. XII §3).
 */
@Composable
fun ConfirmDestructiveDialog(
    title: String,
    message: String,
    confirmLabel: String,
    confirmTestTag: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val cancelFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocusRequester.requestFocus() } }
    RestoreRootFocusOnDispose()
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.dismissOnEscape(onDismiss),
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.desktopControl().testTag(confirmTestTag)
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.focusRequester(cancelFocusRequester).desktopControl()
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/** Read-only provenance details for one wallpaper ("About this image"). */
@Composable
fun WallpaperAboutDialog(record: WallpaperRecord, onDismiss: () -> Unit) {
    val scrollState = rememberScrollState()
    RestoreRootFocusOnDispose()
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.dismissOnEscape(onDismiss),
        title = { Text(record.title, maxLines = 2) },
        text = {
            Column(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .heightIn(max = 360.dp)
                    .fillMaxWidth()
                    .desktopScrollbar(scrollState)
                    .verticalScroll(scrollState)
                    .testTag("wallpaper_about_dialog"),
                verticalArrangement = Arrangement.spacedBy(Spacing.s)
            ) {
                ProvenanceDetails(record)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.desktopControl().testTag("wallpaper_about_close")) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

/** Hands focus back to the workspace root when the calling dialog leaves composition. */
@Composable
internal fun RestoreRootFocusOnDispose() {
    val restore = LocalRestoreRootFocus.current
    val latest = rememberUpdatedState(restore)
    DisposableEffect(Unit) { onDispose { latest.value() } }
}
