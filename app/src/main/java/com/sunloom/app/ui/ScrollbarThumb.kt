package com.sunloom.app.ui

/** Vertical position and size of a scrollbar thumb, in pixels within the viewport. */
internal data class ScrollbarThumb(val top: Float, val height: Float)

/**
 * Computes the thumb for a vertical scrollbar. Returns null when everything fits (nothing to scroll)
 * or the inputs are not yet meaningful (unmeasured content).
 *
 * @param viewport visible height in px
 * @param content total scrollable content height in px
 * @param offset current scroll offset in px (0 .. content - viewport)
 * @param minThumb smallest thumb height in px so it stays grabbable/visible on very long content
 */
internal fun scrollbarThumb(viewport: Float, content: Float, offset: Float, minThumb: Float): ScrollbarThumb? {
    if (viewport <= 0f || content <= viewport) return null
    if (!content.isFinite() || !offset.isFinite()) return null
    val height = (viewport * viewport / content).coerceAtLeast(minThumb).coerceAtMost(viewport)
    val maxOffset = content - viewport
    val fraction = (offset / maxOffset).coerceIn(0f, 1f)
    return ScrollbarThumb(top = (viewport - height) * fraction, height = height)
}
