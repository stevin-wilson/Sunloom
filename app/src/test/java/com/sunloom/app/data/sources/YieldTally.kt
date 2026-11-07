package com.sunloom.app.data.sources

import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.renderer.CropRenderer

/** Sorts the sizes a catalog advertises into what the pre-download checks would do with them for one display. */
internal object YieldTally {

    data class Yield(val accepted: Int, val wrongShape: Int, val tooSmall: Int, val unknown: Int) {
        val total: Int get() = accepted + wrongShape + tooSmall + unknown

        /** Accepted, plus unknown ones, which are only judged after downloading. */
        val usable: Int get() = accepted + unknown
    }

    fun of(sizes: List<Pair<Int, Int>>, display: DisplayProfile, allowBlurredFrameFill: Boolean = false): Yield {
        var accepted = 0
        var wrongShape = 0
        var tooSmall = 0
        var unknown = 0
        for ((w, h) in sizes) {
            if (w <= 0 || h <= 0) {
                unknown++
                continue
            }
            when (CropRenderer.advertisedSizeRejection(w, h, display, allowBlurredFrameFill)) {
                null -> accepted++
                "COMPOSITION_CROPS_TOO_MUCH" -> wrongShape++
                else -> tooSmall++
            }
        }
        return Yield(accepted, wrongShape, tooSmall, unknown)
    }

    /** A markdown table per display: one row per source. */
    fun report(
        tallies: Map<String, Map<String, Yield>>,
        displayNames: List<String>,
        /** Sources the app skips for a display (their renditions cannot satisfy it), by display name. */
        notPolled: Map<String, Set<String>> = emptyMap()
    ): String = buildString {
        appendLine("# Live source coverage")
        appendLine()
        appendLine("Candidates are what each source's catalog returned for one small query per category.")
        appendLine("\"Unknown\" means the catalog gave no size, so the candidate is decided after download.")
        for (display in displayNames) {
            appendLine()
            appendLine("## $display")
            appendLine()
            appendLine("| Source | Usable | Accepted | Wrong shape | Too small | Unknown |")
            appendLine("|---|---|---|---|---|---|")
            for ((source, byDisplay) in tallies.toSortedMap()) {
                if (display in notPolled[source].orEmpty()) {
                    appendLine("| $source | not polled (its renditions cannot satisfy this display) | - | - | - | - |")
                    continue
                }
                val y = byDisplay[display] ?: continue
                appendLine("| $source | ${y.usable} usable of ${y.total} | ${y.accepted} | ${y.wrongShape} | ${y.tooSmall} | ${y.unknown} |")
            }
        }
    }
}
