package com.sunloom.app.domain.evaluator

import com.sunloom.app.domain.model.DisplayProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * Pins the exact output of [ImageEvaluator.evaluateRgbGrid] over a spread of pictures and displays, so a change to
 * how it is organised cannot shift a single number or sentence. The expected text is recorded in
 * `src/test/resources/golden/image_evaluator.txt`; delete that file and run once to record it again after an
 * intended change to the scoring.
 */
class ImageEvaluatorGoldenTest {
    private val gridW = 32
    private val gridH = 20

    private val displays = listOf(
        DisplayProfile(0, "standard", 2560, 1600),
        DisplayProfile(1, "high-dpi", 3840, 2400, physicalPpi = 250.0),
        DisplayProfile(2, "mid-ppi", 1920, 1200, physicalPpi = 150.0),
        DisplayProfile(3, "portrait", 1080, 1920)
    )

    private val imageSizes = listOf(4000 to 3000, 1920 to 1080, 800 to 600, 1000 to 4000, 3840 to 2160)

    private fun rgb(r: Int, g: Int, b: Int) = (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun grids(): Map<String, IntArray> {
        val out = LinkedHashMap<String, IntArray>()
        val rnd = Random(42)
        out["noise"] = IntArray(gridW * gridH) { rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) }
        out["night-with-moon"] = IntArray(gridW * gridH) { i ->
            val x = i % gridW
            val y = i / gridW
            if (x in 24..27 && y in 3..6) rgb(250, 250, 240) else rgb(10 + rnd.nextInt(8), 12, 30 + rnd.nextInt(10))
        }
        out["bright-sky-gradient"] = IntArray(gridW * gridH) { i -> val y = i / gridW; rgb(120 + y * 6, 170 + y * 3, 240) }
        out["warm-dusk"] = IntArray(gridW * gridH) { i -> val x = i % gridW; rgb(200 + x, 90 + x, 40) }
        out["black-frame"] = IntArray(gridW * gridH) { i ->
            val x = i % gridW
            val y = i / gridW
            if (x < 3 || x >= gridW - 3 || y < 2 || y >= gridH - 2) rgb(0, 0, 0) else rgb(180 + rnd.nextInt(40), 160, 140 + rnd.nextInt(60))
        }
        out["white-mat-dark-photo"] = IntArray(gridW * gridH) { i ->
            val x = i % gridW
            val y = i / gridW
            if (x < 2 || x >= gridW - 2 || y < 2 || y >= gridH - 2) rgb(255, 255, 255) else rgb(20 + rnd.nextInt(30), 40, 25)
        }
        out["uniform-mid"] = IntArray(gridW * gridH) { rgb(128, 128, 128) }
        out["glare-top-left"] = IntArray(gridW * gridH) { i ->
            val x = i % gridW
            val y = i / gridW
            if (x < 10 && y < 6) rgb(255, 255, 250) else rgb(60, 70 + rnd.nextInt(20), 90)
        }
        return out
    }

    private fun render(): String = buildString {
        val evaluator = ImageEvaluator()
        for ((gridName, grid) in grids()) {
            for ((w, h) in imageSizes) {
                for (display in displays) {
                    for (blur in listOf(false, true)) {
                        val r = evaluator.evaluateRgbGrid(w, h, grid, gridW, gridH, display, allowBlurredFrameFill = blur)
                        append("$gridName ${w}x$h ${display.name} blur=$blur -> $r\n")
                    }
                }
            }
        }
        // Invalid inputs
        val g = grids().values.first()
        append("invalid-size -> ${evaluator.evaluateRgbGrid(0, 100, g, gridW, gridH, displays[0])}\n")
        append("short-grid -> ${evaluator.evaluateRgbGrid(100, 100, IntArray(5), gridW, gridH, displays[0])}\n")
    }

    @Test
    fun `evaluateRgbGrid output is unchanged for a spread of pictures and displays`() {
        val golden = File("src/test/resources/golden/image_evaluator.txt")
        val actual = render()
        if (!golden.exists()) {
            golden.parentFile.mkdirs()
            golden.writeText(actual)
            fail("Recorded ${golden.path}; run the test again.")
        }
        assertEquals(golden.readText().lines(), actual.lines())
    }
}
