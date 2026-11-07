package com.sunloom.app.domain.evaluator

import com.sunloom.app.Requirement
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Article IV §3: brightness is judged on the picture, so the coarse grid the evaluator sees must be the average
 * of every pixel in each cell. A single point sample per cell made a detailed photograph's class depend on
 * which pixels happened to be sampled (the same skyline was Light at one decode size and Balanced at another).
 */
@Requirement("IV.3")
class GridAveragerTest {

    private fun channel(rgb: Int, shift: Int) = (rgb shr shift) and 0xFF
    private fun assertRgbNear(expected: Int, actual: Int, tolerance: Int = 1) {
        for (shift in intArrayOf(16, 8, 0)) {
            val e = channel(expected, shift)
            val a = channel(actual, shift)
            assertTrue("channel@$shift expected $e got $a (tolerance $tolerance)", kotlin.math.abs(e - a) <= tolerance)
        }
    }

    @Test
    fun `a uniform picture keeps its colour in every cell`() {
        val pixels = IntArray(200 * 120) { 0x336699 }
        val grid = GridAverager.average(pixels, 200, 120, 32, 20)
        assertEquals(32 * 20, grid.size)
        grid.forEach { assertEquals(0x336699, it) }
    }

    @Test
    fun `fine detail averages to its mean instead of whatever pixel was sampled`() {
        // 1 px black/white checkerboard: a point sample would be pure black or pure white.
        val w = 128
        val h = 80
        val pixels = IntArray(w * h) { if ((it % w + it / w) % 2 == 0) 0x000000 else 0xFFFFFF }
        val grid = GridAverager.average(pixels, w, h, 32, 20)
        grid.forEach { assertRgbNear(0x808080, it) }
    }

    @Test
    fun `a boundary inside a cell is averaged in proportion`() {
        // 100 px wide, 3 cells (33 px each): cell 1 spans x 33..65, red up to x 49 (17 px) and blue after (16 px).
        val w = 100
        val h = 6
        val pixels = IntArray(w * h) { if (it % w < 50) 0xFF0000 else 0x0000FF }
        val grid = GridAverager.average(pixels, w, h, 3, 1)
        assertRgbNear(0xFF0000, grid[0])
        assertRgbNear((255 * 17 / 33 shl 16) or (255 * 16 / 33), grid[1])
        assertRgbNear(0x0000FF, grid[2])
    }

    @Test
    fun `alpha bits are ignored`() {
        val pixels = IntArray(40 * 20) { 0x80112233.toInt() }
        GridAverager.average(pixels, 40, 20, 8, 4).forEach { assertEquals(0x112233, it) }
    }

    @Test
    fun `a source smaller than the grid is stretched without crashing`() {
        val pixels = intArrayOf(0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF)
        val grid = GridAverager.average(pixels, 2, 2, 4, 4)
        assertEquals(16, grid.size)
        assertEquals(0xFF0000, grid[0])
        assertEquals(0xFFFFFF, grid[15])
    }

    @Test
    fun `the grid barely changes between a decode and its half-size decode`() {
        // The app decodes at 2048 px when fetching but at 512 px when re-validating the same file.
        val w = 512
        val h = 320
        val rnd = Random(42)
        val full = IntArray(w * h) {
            val v = 40 + rnd.nextInt(180)
            (v shl 16) or ((v * 9 / 10) shl 8) or (v * 8 / 10)
        }
        val half = IntArray((w / 2) * (h / 2))
        for (y in 0 until h / 2) for (x in 0 until w / 2) {
            var r = 0; var g = 0; var b = 0
            for (dy in 0..1) for (dx in 0..1) {
                val p = full[(2 * y + dy) * w + 2 * x + dx]
                r += channel(p, 16); g += channel(p, 8); b += channel(p, 0)
            }
            half[y * (w / 2) + x] = ((r / 4) shl 16) or ((g / 4) shl 8) or (b / 4)
        }
        val a = GridAverager.average(full, w, h, 32, 20)
        val b = GridAverager.average(half, w / 2, h / 2, 32, 20)
        for (i in a.indices) assertRgbNear(a[i], b[i], tolerance = 2)
    }

    @Test
    fun `rows can be streamed so a large bitmap is never copied whole`() {
        val w = 64
        val h = 40
        val pixels = IntArray(w * h) { if (it / w < 20) 0xFFFFFF else 0x000000 }
        var rowsRead = 0
        val grid = GridAverager.average(w, h, 4, 2) { y, into ->
            rowsRead++
            System.arraycopy(pixels, y * w, into, 0, w)
        }
        assertEquals(h, rowsRead)
        assertEquals(0xFFFFFF, grid[0])
        assertEquals(0x000000, grid[4])
    }
}
