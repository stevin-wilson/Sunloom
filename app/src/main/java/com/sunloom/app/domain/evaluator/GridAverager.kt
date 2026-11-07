package com.sunloom.app.domain.evaluator

/**
 * Reduces a picture to the coarse RGB grid the [ImageEvaluator] judges (Article IV §3).
 *
 * Every cell is the plain average of all source pixels it covers. A single bilinear sample per cell, as a one-step
 * bitmap downscale gives, reads only a few pixels of a 64x reduction, so a detailed photograph's lightness (and
 * therefore its Light/Balanced/Dark class) depended on which pixels happened to be sampled and on the decode size.
 *
 * Averaging is done on the stored sRGB values, as before; the evaluator converts each cell to luminance itself.
 */
object GridAverager {

    /**
     * Averages [width] x [height] pixels, supplied one row at a time by [readRow] (which fills the buffer it is
     * given with the 0xAARRGGBB or 0xRRGGBB values of row `y`), into a [gridW] x [gridH] grid of 0xRRGGBB ints.
     * Streaming rows keeps a large bitmap from being copied into memory a second time.
     */
    fun average(
        width: Int,
        height: Int,
        gridW: Int,
        gridH: Int,
        readRow: (y: Int, into: IntArray) -> Unit
    ): IntArray {
        require(width > 0 && height > 0 && gridW > 0 && gridH > 0) { "sizes must be positive" }
        // Cell [g] covers source columns [start, end); a source narrower than the grid repeats its pixels.
        val xStart = IntArray(gridW) { (it.toLong() * width / gridW).toInt().coerceIn(0, width - 1) }
        val xEnd = IntArray(gridW) { g -> maxOf(((g + 1).toLong() * width / gridW).toInt(), xStart[g] + 1).coerceAtMost(width) }
        val row = IntArray(width)
        val sums = LongArray(gridW * 3)
        val result = IntArray(gridW * gridH)

        for (gy in 0 until gridH) {
            val y0 = (gy.toLong() * height / gridH).toInt().coerceIn(0, height - 1)
            val y1 = maxOf(((gy + 1).toLong() * height / gridH).toInt(), y0 + 1).coerceAtMost(height)
            sums.fill(0L)
            for (y in y0 until y1) {
                readRow(y, row)
                for (gx in 0 until gridW) {
                    var r = 0L
                    var g = 0L
                    var b = 0L
                    for (x in xStart[gx] until xEnd[gx]) {
                        val p = row[x]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                    }
                    sums[gx * 3] += r
                    sums[gx * 3 + 1] += g
                    sums[gx * 3 + 2] += b
                }
            }
            val rowsInCell = y1 - y0
            for (gx in 0 until gridW) {
                val count = (xEnd[gx] - xStart[gx]).toLong() * rowsInCell
                val r = ((sums[gx * 3] + count / 2) / count).toInt()
                val g = ((sums[gx * 3 + 1] + count / 2) / count).toInt()
                val b = ((sums[gx * 3 + 2] + count / 2) / count).toInt()
                result[gy * gridW + gx] = (r shl 16) or (g shl 8) or b
            }
        }
        return result
    }

    /** Convenience for pixels already in memory (row-major, [width] x [height]). */
    fun average(pixels: IntArray, width: Int, height: Int, gridW: Int, gridH: Int): IntArray {
        require(pixels.size >= width * height) { "pixels shorter than width x height" }
        return average(width, height, gridW, gridH) { y, into -> System.arraycopy(pixels, y * width, into, 0, width) }
    }
}
