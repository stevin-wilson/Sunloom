package com.sunloom.app.ui

import androidx.compose.ui.input.key.Key

/**
 * Pure selection and keyboard-navigation rules for the Reserve list/grid (Input_Combinations,
 * Keyboard_Navigation). Kept free of Compose state so they are deterministic and unit-testable.
 */
internal enum class NavMove { UP, DOWN, LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN }

internal fun navMoveFor(key: Key): NavMove? = when (key) {
    Key.DirectionUp -> NavMove.UP
    Key.DirectionDown -> NavMove.DOWN
    Key.DirectionLeft -> NavMove.LEFT
    Key.DirectionRight -> NavMove.RIGHT
    Key.MoveHome -> NavMove.HOME
    Key.MoveEnd -> NavMove.END
    Key.PageUp -> NavMove.PAGE_UP
    Key.PageDown -> NavMove.PAGE_DOWN
    else -> null
}

/**
 * Index to move focus to from [index], or null when the move does not apply (for example Left/Right in a
 * single-column list). Clamps at the ends instead of wrapping.
 */
internal fun reserveNavTarget(move: NavMove, index: Int, count: Int, columns: Int, pageRows: Int = 4): Int? {
    if (count <= 0 || index !in 0 until count) return null
    val cols = columns.coerceAtLeast(1)
    val target = when (move) {
        NavMove.UP -> index - cols
        NavMove.DOWN -> index + cols
        NavMove.LEFT -> if (cols == 1) return null else index - 1
        NavMove.RIGHT -> if (cols == 1) return null else index + 1
        NavMove.HOME -> 0
        NavMove.END -> count - 1
        NavMove.PAGE_UP -> index - cols * pageRows
        NavMove.PAGE_DOWN -> index + cols * pageRows
    }
    return target.coerceIn(0, count - 1)
}

internal data class SelectionState(val selected: Set<String> = emptySet(), val anchorId: String? = null)

internal fun toggleSelection(state: SelectionState, id: String): SelectionState {
    val next = if (id in state.selected) state.selected - id else state.selected + id
    return SelectionState(next, anchorId = id)
}

/** Contiguous range between [anchorId] and [targetId] in [order]; just the target when no anchor exists. */
internal fun rangeSelection(order: List<String>, anchorId: String?, targetId: String): Set<String> {
    val to = order.indexOf(targetId)
    if (to < 0) return emptySet()
    val from = anchorId?.let { order.indexOf(it) }?.takeIf { it >= 0 } ?: to
    return order.subList(minOf(from, to), maxOf(from, to) + 1).toSet()
}

/**
 * Result of a pointer press on item [id] with the given modifiers.
 *
 * - Ctrl toggles the item; Shift selects the range from the anchor (Ctrl+Shift adds the range).
 * - A plain mouse press clears an existing selection; a plain touch tap toggles while selecting.
 */
internal fun selectionAfterPress(
    state: SelectionState,
    order: List<String>,
    id: String,
    ctrl: Boolean,
    shift: Boolean,
    isTouch: Boolean
): SelectionState = when {
    shift -> {
        val range = rangeSelection(order, state.anchorId ?: id, id)
        SelectionState(if (ctrl) state.selected + range else range, state.anchorId ?: id)
    }
    ctrl -> toggleSelection(state, id)
    state.selected.isEmpty() -> state
    isTouch -> toggleSelection(state, id)
    else -> SelectionState()
}

/** Drops ids that no longer exist (removed, rejected, or pending undo) so the batch bar never lies. */
internal fun pruneSelection(state: SelectionState, existingIds: Set<String>): SelectionState {
    val kept = state.selected.filterTo(LinkedHashSet()) { it in existingIds }
    return if (kept.size == state.selected.size) state else SelectionState(kept, state.anchorId?.takeIf { it in existingIds })
}
