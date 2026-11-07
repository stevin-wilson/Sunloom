package com.sunloom.app.domain.renderer

import com.sunloom.app.domain.model.DisplayPolicy
import com.sunloom.app.domain.model.DisplayProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvelopePlannerTest {

    private val planner = EnvelopePlanner()

    @Test
    fun `planEnvelope satisfies both 16_10 Retina laptop and 21_9 UWQHD monitor with 3_2 source without upscaling`() {
        val laptop = DisplayProfile(
            displayId = 0,
            name = "GoogleBook 14 Retina",
            widthPx = 2560,
            heightPx = 1600,
            densityDpi = 240,
            physicalPpi = 227.0,
            isPrimary = true
        )
        val uwqhd = DisplayProfile(
            displayId = 1,
            name = "Dell 34 UWQHD",
            widthPx = 3440,
            heightPx = 1440,
            densityDpi = 160,
            physicalPpi = 109.7,
            isPrimary = false
        )

        // 4200x2800 (3:2 = 1.50) -> r_src.coerceIn(1.60, 2.3889) = 1.60 -> envelope 4200x2625
        val plan = planner.planEnvelope(
            sourceWidth = 4200,
            sourceHeight = 2800,
            focalX = 0.62,
            focalY = 0.48,
            connectedDisplays = listOf(laptop, uwqhd),
            policy = DisplayPolicy.SAME_ON_ALL_DISPLAYS,
            allowBlurredFrameFill = false
        )

        assertNotNull("Expected 4200x2800 3:2 source to satisfy both 16:10 and 21:9 natively", plan)
        val result = requireNotNull(plan)
        assertTrue(result.excludedDisplays.isEmpty())
        assertEquals(2, result.satisfiedDisplays.size)
        assertEquals(1.60, result.envelopeCrop.width.toDouble() / result.envelopeCrop.height.toDouble(), 0.01)

        // Verify predicted system center-crop on UWQHD (21:9) is >= 3440x1440 (100% native coverage)
        val uwqhdSlice = requireNotNull(result.perDisplayCrops[uwqhd.displayId])
        assertTrue("UWQHD center-crop width must be >= 3440, got ${uwqhdSlice.width}", uwqhdSlice.width >= 3440)
        assertTrue("UWQHD center-crop height must be >= 1440, got ${uwqhdSlice.height}", uwqhdSlice.height >= 1440)
        assertTrue("UWQHD coverage must be >= 1.0, got ${uwqhdSlice.pixelCoverageRatio}", uwqhdSlice.pixelCoverageRatio >= 1.0)
    }

    @Test
    fun `tiered physical PPI floor accepts 3200x1800 on 27-inch 4K 163ppi monitor while rejecting on 55-inch 4K 80ppi TV`() {
        val uhd27MidPpi = DisplayProfile(
            displayId = 1,
            name = "27-inch 4K Monitor",
            widthPx = 3840,
            heightPx = 2160,
            densityDpi = 160,
            physicalPpi = 163.0
        )
        val uhd55LowPpi = DisplayProfile(
            displayId = 2,
            name = "55-inch 4K TV",
            widthPx = 3840,
            heightPx = 2160,
            densityDpi = 160,
            physicalPpi = 80.1
        )

        // 3200 / 3840 = 0.833 >= 0.80 (MID_80 passes), < 1.00 (STANDARD_100 rejects)
        val acceptedMidPpi = planner.planEnvelope(
            sourceWidth = 3200,
            sourceHeight = 1800,
            focalX = 0.5,
            focalY = 0.5,
            connectedDisplays = listOf(uhd27MidPpi),
            policy = DisplayPolicy.SAME_ON_ALL_DISPLAYS,
            allowBlurredFrameFill = false
        )
        assertNotNull("Expected 3200x1800 (83% coverage) to pass 0.80 floor on 163 PPI 4K monitor", acceptedMidPpi)
        assertFalse(acceptedMidPpi!!.envelopeCrop.usesBlurredFrameFill)

        val rejectedLowPpi = planner.planEnvelope(
            sourceWidth = 3200,
            sourceHeight = 1800,
            focalX = 0.5,
            focalY = 0.5,
            connectedDisplays = listOf(uhd55LowPpi),
            policy = DisplayPolicy.SAME_ON_ALL_DISPLAYS,
            allowBlurredFrameFill = false
        )
        assertNull("Expected 3200x1800 (83% coverage) to be rejected on 80 PPI 4K TV (< 120 PPI -> 1.00 floor)", rejectedLowPpi)
    }

    @Test
    fun `planEnvelope excludes incompatible portrait outlier monitor cleanly instead of failing landscape displays`() {
        val laptop = DisplayProfile(0, "Laptop 16:10", 2560, 1600, densityDpi = 240, physicalPpi = 227.0, isPrimary = true)
        val uhdLandscape = DisplayProfile(1, "4K Landscape", 3840, 2160, densityDpi = 160, physicalPpi = 163.0, isPrimary = false)
        val uhdPortrait = DisplayProfile(2, "4K Portrait Side Monitor", 2160, 3840, densityDpi = 160, physicalPpi = 109.0, isPrimary = false)

        // A 3840x2400 landscape photo has height 2400 < 3840, so uhdPortrait (109 PPI -> 1.00 floor) cannot pass natively,
        // while laptop + uhdLandscape CAN pass natively together!
        val plan = planner.planEnvelope(
            sourceWidth = 3840,
            sourceHeight = 2400,
            focalX = 0.5,
            focalY = 0.5,
            connectedDisplays = listOf(laptop, uhdLandscape, uhdPortrait),
            policy = DisplayPolicy.SAME_ON_ALL_DISPLAYS,
            allowBlurredFrameFill = false
        )

        assertNotNull("Outlier portrait display must be excluded rather than dragging down landscape displays", plan)
        val result = requireNotNull(plan)
        assertEquals(2, result.satisfiedDisplays.size)
        assertEquals(listOf(uhdPortrait), result.excludedDisplays)
        assertTrue(result.disclosureNote.contains("4K Portrait Side Monitor"))
    }

    @Test
    fun `OPTIMIZE_PRIMARY_SHARPEST targets only highest-pixel-count connected display`() {
        val laptop = DisplayProfile(0, "Laptop 16:10", 2560, 1600, densityDpi = 240, physicalPpi = 227.0, isPrimary = true)
        val uwqhd = DisplayProfile(1, "UWQHD 21:9", 3440, 1440, densityDpi = 160, physicalPpi = 109.0, isPrimary = false)

        val plan = planner.planEnvelope(
            sourceWidth = 3840,
            sourceHeight = 2160,
            focalX = 0.5,
            focalY = 0.5,
            connectedDisplays = listOf(laptop, uwqhd),
            policy = DisplayPolicy.OPTIMIZE_PRIMARY_SHARPEST,
            allowBlurredFrameFill = false
        )

        assertNotNull(plan)
        val result = requireNotNull(plan)
        assertEquals(listOf(uwqhd), result.satisfiedDisplays)
        assertEquals(uwqhd.aspectRatio, result.envelopeCrop.width.toDouble() / result.envelopeCrop.height.toDouble(), 0.01)
    }

    @Test
    fun `planEnvelope rejects undersized 2400x1600 photo when UWQHD monitor is docked rather than falsely excluding UWQHD`() {
        val laptop = DisplayProfile(0, "Laptop 16:10 Retina", 2560, 1600, densityDpi = 240, physicalPpi = 227.0, isPrimary = true)
        val uwqhd = DisplayProfile(1, "UWQHD 21:9", 3440, 1440, densityDpi = 160, physicalPpi = 109.7, isPrimary = false)

        // 2400x1600 passes laptop's 0.65 Retina floor alone, but would upscale blurry on UWQHD (2400 < 3440).
        // Because UWQHD is a compatible landscape monitor (not a portrait/8K outlier), planEnvelope must return null!
        val rejected = planner.planEnvelope(
            sourceWidth = 2400,
            sourceHeight = 1600,
            focalX = 0.5,
            focalY = 0.5,
            connectedDisplays = listOf(laptop, uwqhd),
            policy = DisplayPolicy.SAME_ON_ALL_DISPLAYS,
            allowBlurredFrameFill = false
        )
        assertNull("Undersized 2400x1600 image must be rejected when docked to 3440x1440 UWQHD", rejected)
    }

    @Test
    fun `an excluded portrait display says it was excluded for its opposite orientation`() {
        val laptop = DisplayProfile(0, "Laptop 16:10", 2560, 1600, densityDpi = 240, physicalPpi = 227.0, isPrimary = true)
        val uhdLandscape = DisplayProfile(1, "4K Landscape", 3840, 2160, densityDpi = 160, physicalPpi = 163.0, isPrimary = false)
        val uhdPortrait = DisplayProfile(2, "4K Portrait Side Monitor", 2160, 3840, densityDpi = 160, physicalPpi = 109.0, isPrimary = false)

        val plan = requireNotNull(
            planner.planEnvelope(3840, 2400, 0.5, 0.5, listOf(laptop, uhdLandscape, uhdPortrait))
        )

        assertTrue(requireNotNull(plan.exclusionReasons[uhdPortrait.displayId]).contains("orientation"))
        assertTrue(plan.disclosureNote.contains("orientation"))
    }

    @Test
    fun `an excluded super-ultrawide says it was excluded for its aspect ratio`() {
        val laptop = DisplayProfile(0, "Laptop 16:10", 2560, 1600, densityDpi = 240, physicalPpi = 227.0, isPrimary = true)
        val superWide = DisplayProfile(1, "Super Ultrawide 32:9", 5120, 1440, densityDpi = 160, physicalPpi = 109.0, isPrimary = false)

        // 2400 px is too narrow for 5120, so the full set fails and the aspect outlier is dropped.
        val plan = requireNotNull(planner.planEnvelope(2400, 1500, 0.5, 0.5, listOf(laptop, superWide)))

        assertEquals(listOf(superWide), plan.excludedDisplays)
        assertTrue(requireNotNull(plan.exclusionReasons[superWide.displayId]).contains("aspect"))
    }

    @Test
    fun `an excluded display beyond the 16 MP cap says so`() {
        val laptop = DisplayProfile(0, "Laptop 16:10", 2560, 1600, densityDpi = 240, physicalPpi = 227.0, isPrimary = true)
        val tv8k = DisplayProfile(1, "8K TV", 7680, 4320, densityDpi = 160, physicalPpi = 80.0, isPrimary = false)

        val plan = requireNotNull(planner.planEnvelope(10_000, 10_000, 0.5, 0.5, listOf(laptop, tv8k)))

        assertEquals(listOf(tv8k), plan.excludedDisplays)
        assertTrue(requireNotNull(plan.exclusionReasons[tv8k.displayId]).contains("16 MP"))
    }

    @Test
    fun `a subject near the edge is reported as cut off on a narrow portrait display`() {
        // Strict (98%) tier: a narrow compromise envelope would be too soft for this panel.
        val laptop = DisplayProfile(0, "Laptop 16:10", 2560, 1600, densityDpi = 160, isPrimary = true)
        val portrait = DisplayProfile(1, "Portrait Monitor", 1200, 1920, densityDpi = 160, isPrimary = false)

        val plan = requireNotNull(planner.planEnvelope(4800, 3000, 0.02, 0.5, listOf(laptop, portrait)))

        assertEquals(listOf(portrait), plan.subjectClippedDisplays)
        assertTrue(plan.disclosureNote.contains("Portrait Monitor"))
        assertTrue(plan.disclosureNote.contains("cut off"))
    }

    @Test
    fun `a centred subject is cut off on no display`() {
        // Strict (98%) tier: a narrow compromise envelope would be too soft for this panel.
        val laptop = DisplayProfile(0, "Laptop 16:10", 2560, 1600, densityDpi = 160, isPrimary = true)
        val portrait = DisplayProfile(1, "Portrait Monitor", 1200, 1920, densityDpi = 160, isPrimary = false)

        val plan = requireNotNull(planner.planEnvelope(4800, 3000, 0.5, 0.5, listOf(laptop, portrait)))

        assertTrue(plan.subjectClippedDisplays.isEmpty())
        assertFalse(plan.disclosureNote.contains("cut off"))
    }

    @Test
    fun `a high-density panel lets the envelope narrow so the subject survives on a portrait display`() {
        val laptop = DisplayProfile(0, "Laptop 16:10 Retina", 2560, 1600, densityDpi = 240, physicalPpi = 227.0, isPrimary = true)
        val portrait = DisplayProfile(1, "Portrait Monitor", 1200, 1920, densityDpi = 160, isPrimary = false)

        val plan = requireNotNull(planner.planEnvelope(4800, 3000, 0.02, 0.5, listOf(laptop, portrait)))

        assertTrue(plan.subjectClippedDisplays.isEmpty())
        assertTrue(plan.excludedDisplays.isEmpty())
    }

    @Test
    fun `single-display and sharpest-only plans keep their own disclosure wording`() {
        val laptop = DisplayProfile(0, "Laptop 16:10", 2560, 1600, densityDpi = 240, physicalPpi = 227.0, isPrimary = true)
        val uwqhd = DisplayProfile(1, "UWQHD 21:9", 3440, 1440, densityDpi = 160, physicalPpi = 109.0, isPrimary = false)

        val single = requireNotNull(planner.planEnvelope(3840, 2400, 0.5, 0.5, listOf(laptop)))
        assertTrue(single.disclosureNote, single.disclosureNote.startsWith("Native crop for"))

        val sharpest = requireNotNull(
            planner.planEnvelope(3840, 2160, 0.5, 0.5, listOf(laptop, uwqhd), DisplayPolicy.OPTIMIZE_PRIMARY_SHARPEST)
        )
        assertTrue(sharpest.disclosureNote, sharpest.disclosureNote.startsWith("Optimized crop for sharpest display"))
    }
}
