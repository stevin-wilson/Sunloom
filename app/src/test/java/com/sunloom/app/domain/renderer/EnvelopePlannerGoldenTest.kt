package com.sunloom.app.domain.renderer

import com.sunloom.app.domain.model.DisplayPolicy
import com.sunloom.app.domain.model.DisplayProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Pins [EnvelopePlanner.planEnvelope] over a spread of pictures, focal points, display sets and policies, so
 * reorganising the planner cannot change a crop or a sentence. The expected text is recorded in
 * `src/test/resources/golden/envelope_planner.txt`; delete that file and run once to record it again after an
 * intended change.
 */
class EnvelopePlannerGoldenTest {
    private val builtIn = DisplayProfile(0, "Built-in", 2560, 1600, physicalPpi = 226.0)
    private val external = DisplayProfile(1, "External", 1920, 1080, physicalPpi = 92.0, isPrimary = false)
    private val portrait = DisplayProfile(2, "Portrait", 1080, 1920, physicalPpi = 110.0, isPrimary = false)
    private val ultrawide = DisplayProfile(3, "Ultrawide", 3440, 1440, physicalPpi = 110.0, isPrimary = false)

    private val displaySets = listOf(
        "single" to listOf(builtIn),
        "pair" to listOf(builtIn, external),
        "triple" to listOf(builtIn, external, portrait),
        "wide" to listOf(builtIn, ultrawide)
    )
    private val sources = listOf(6000 to 4000, 3000 to 2000, 1920 to 1080, 800 to 600, 4000 to 6000, 8000 to 2000)
    private val focals = listOf(0.5 to 0.5, 0.1 to 0.2, 0.9 to 0.8)

    private fun render(): String = buildString {
        val planner = EnvelopePlanner()
        for ((setName, displays) in displaySets) for ((w, h) in sources) for ((fx, fy) in focals)
            for (policy in DisplayPolicy.entries) for (blur in listOf(false, true)) {
                val plan = planner.planEnvelope(w, h, fx, fy, displays, policy, blur)
                append("$setName ${w}x$h f=$fx,$fy $policy blur=$blur -> $plan\n")
            }
    }

    @Test
    fun `envelope plans are unchanged for a spread of pictures and display sets`() {
        val golden = File("src/test/resources/golden/envelope_planner.txt")
        val actual = render()
        if (!golden.exists()) {
            golden.parentFile.mkdirs()
            golden.writeText(actual)
            fail("Recorded ${golden.path}; run the test again.")
        }
        assertEquals(golden.readText().lines(), actual.lines())
    }
}
