package com.sunloom.app.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Window-width buckets for the desktop workspace, matching the Material window size classes:
 * Compact < 600dp (snapped/narrow window), Medium 600-839dp, Expanded >= 840dp.
 */
enum class WorkspaceWidthClass {
    Compact,
    Medium,
    Expanded;

    companion object {
        val CompactMaxWidth: Dp = 600.dp
        val MediumMaxWidth: Dp = 840.dp

        fun from(width: Dp): WorkspaceWidthClass = when {
            width < CompactMaxWidth -> Compact
            width < MediumMaxWidth -> Medium
            else -> Expanded
        }
    }
}

/** Below this pane width the Today canvas stacks its preview above the provenance card. */
val TodayCanvasStackBreakpoint: Dp = 720.dp
