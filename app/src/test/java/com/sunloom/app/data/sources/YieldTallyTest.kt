package com.sunloom.app.data.sources

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.DisplayProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Article X §4: the tally behind the opt-in live coverage probe, tested offline on synthetic sizes. */
@Requirement("X.4")
class YieldTallyTest {

    private val ultrawide = DisplayProfile(0, "3440x1440", 3440, 1440, densityDpi = 149)

    @Test
    fun `sizes are sorted into usable, wrong-shaped, too-small and unknown`() {
        val sizes = listOf(
            4000 to 2250,  // accepted
            8000 to 2500,  // accepted
            3000 to 4000,  // crop would waste it
            1600 to 1000,  // too small
            0 to 0         // the catalog gave no size: decided only after downloading
        )
        val yield = YieldTally.of(sizes, ultrawide)
        assertEquals(YieldTally.Yield(accepted = 2, wrongShape = 1, tooSmall = 1, unknown = 1), yield)
        assertEquals(5, yield.total)
        assertEquals(3, yield.usable)
    }

    @Test
    fun `an empty fetch is an empty tally`() {
        assertEquals(0, YieldTally.of(emptyList(), ultrawide).total)
    }

    @Test
    fun `a source the app would not poll for a display is reported as not polled, not as usable`() {
        val tallies = mapOf(
            "artic_cc0" to mapOf("3440x1440" to YieldTally.Yield(0, 0, 0, 11), "1366x768" to YieldTally.Yield(0, 0, 0, 11))
        )
        val report = YieldTally.report(
            tallies,
            displayNames = listOf("3440x1440", "1366x768"),
            notPolled = mapOf("artic_cc0" to setOf("3440x1440"))
        )
        val lines = report.lines()
        assertTrue(lines.single { it.startsWith("| artic_cc0 | not polled") }.isNotBlank())
        assertTrue("still reported where it is polled", lines.any { it.startsWith("| artic_cc0 | 11 usable of 11") })
    }

    @Test
    fun `the report names every source and display and says what unknown means`() {
        val tallies = mapOf(
            "wikimedia_featured" to mapOf("3440x1440" to YieldTally.Yield(5, 1, 0, 0)),
            "cma_cc0" to mapOf("3440x1440" to YieldTally.Yield(0, 3, 4, 1))
        )
        val report = YieldTally.report(tallies, displayNames = listOf("3440x1440"))
        assertTrue(report.contains("wikimedia_featured"))
        assertTrue(report.contains("cma_cc0"))
        assertTrue(report.contains("3440x1440"))
        assertTrue(report.contains("5 usable of 6"))
        assertTrue(report.contains("1 usable of 8"))
        assertTrue("unknown must be explained", report.contains("decided after download"))
    }
}
