package com.sunloom.app.domain.renderer

import com.sunloom.app.Property
import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.CropRect
import com.sunloom.app.domain.model.DisplayPolicy
import com.sunloom.app.domain.model.DisplayProfile
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared wallpaper for several displays (Article IV §1, §2 and §4), checked for random pictures and random sets of
 * displays: every display is either served or openly left out, no crop leaves the picture or stretches it, the output
 * stays inside the 16 MP cap and never enlarges the crop, and the answer does not depend on the order the system lists
 * the displays in or get worse when the picture gets bigger.
 */
@Requirement("IV.1", "IV.2", "IV.4")
class EnvelopePlannerPropertyTest {

    private val planner = EnvelopePlanner()

    private val sizes = listOf(
        1280 to 800, 1366 to 768, 1920 to 1080, 1920 to 1200, 2560 to 1440, 2560 to 1600,
        3440 to 1440, 3840 to 2160, 1080 to 1920, 1440 to 2560, 5120 to 1440
    )

    /** One random picture, set of displays, policy and subject position: everything a plan depends on. */
    private class Scenario(
        val srcW: Int,
        val srcH: Int,
        val displays: List<DisplayProfile>,
        val policy: DisplayPolicy,
        val allowBlur: Boolean,
        val focalX: Double,
        val focalY: Double
    ) {
        override fun toString() =
            "source ${srcW}x$srcH, $policy, blur=$allowBlur, subject ($focalX, $focalY), displays " +
                displays.joinToString { "#${it.displayId} ${it.widthPx}x${it.heightPx}${if (it.isPrimary) "*" else ""}@${it.physicalPpi?.toInt()}" }
    }

    /** One to four displays: ids and sizes all different (so no ties), exactly one of them the primary. */
    private fun displays(case: Property.Case): List<DisplayProfile> {
        val count = case.int(1, 4)
        val chosen = mutableListOf<Pair<Int, Int>>()
        while (chosen.size < count) {
            val size = if (case.boolean()) case.oneOf(sizes) else case.int(800, 5_120) to case.int(600, 2_880)
            if (chosen.none { it.first.toLong() * it.second == size.first.toLong() * size.second }) chosen += size
        }
        val primary = case.int(0, count - 1)
        return chosen.mapIndexed { i, (w, h) ->
            DisplayProfile(i + 1, "Display ${i + 1}", w, h, isPrimary = i == primary, physicalPpi = case.double(80.0, 330.0))
        }
    }

    /** Draws a scenario and records it as the case's input, so any failure shows it. */
    private fun scenario(case: Property.Case, maxSide: Int = 9_000, displays: List<DisplayProfile> = displays(case)): Scenario {
        val s = Scenario(
            srcW = case.int(300, maxSide),
            srcH = case.int(300, maxSide),
            displays = displays,
            policy = case.oneOf(DisplayPolicy.values().toList()),
            allowBlur = case.boolean(),
            focalX = case.double(0.0, 1.0),
            focalY = case.double(0.0, 1.0)
        )
        case.input = s.toString()
        return s
    }

    private fun plan(s: Scenario, displays: List<DisplayProfile> = s.displays, scale: Int = 1) =
        planner.planEnvelope(s.srcW * scale, s.srcH * scale, s.focalX, s.focalY, displays, s.policy, s.allowBlur)

    private fun CropRect.insidePicture(srcW: Int, srcH: Int) =
        left >= 0 && top >= 0 && width >= 1 && height >= 1 && left + width <= srcW && top + height <= srcH

    private fun CropRect.hasShapeOf(d: DisplayProfile): Boolean {
        val aspect = d.widthPx.toDouble() / d.heightPx
        return abs(width - height * aspect) <= 0.5 + 1e-6 || abs(height - width / aspect) <= 0.5 + 1e-6
    }

    // --- Who is served, who is left out ---

    @Test
    fun `every display is either served or openly left out`() {
        Property.forAll("displays are served or disclosed") { case ->
            val s = scenario(case)
            val p = plan(s) ?: return@forAll
            val satisfied = p.satisfiedDisplays.map { it.displayId }
            val excluded = p.excludedDisplays.map { it.displayId }
            assertTrue("nobody is served", satisfied.isNotEmpty())
            assertEquals("a display is listed twice", satisfied.size + excluded.size, (satisfied + excluded).toSet().size)
            val sharpest = s.displays.maxBy { it.widthPx.toLong() * it.heightPx }.displayId
            if (s.policy == DisplayPolicy.OPTIMIZE_PRIMARY_SHARPEST || s.displays.size == 1) {
                // One crop for the sharpest display; the others use the system's own centre-crop of it.
                assertEquals(listOf(sharpest), satisfied)
                assertTrue(excluded.isEmpty())
            } else {
                assertEquals("served and left out must be every connected display", s.displays.map { it.displayId }.toSet(), (satisfied + excluded).toSet())
                assertEquals("every display left out must say why", excluded.toSet(), p.exclusionReasons.keys)
                assertTrue("a plan with no explanation", p.disclosureNote.isNotBlank())
            }
        }
    }

    @Test
    fun `the order the displays are listed in does not change who is served`() {
        Property.forAll("display order does not matter") { case ->
            val s = scenario(case)
            val a = plan(s)
            val b = plan(s, displays = s.displays.shuffled(case.random))
            assertEquals("whether any plan exists", a == null, b == null)
            if (a != null && b != null) {
                assertEquals(a.satisfiedDisplays.map { it.displayId }.toSet(), b.satisfiedDisplays.map { it.displayId }.toSet())
                assertEquals(a.envelopeCrop, b.envelopeCrop)
                assertEquals(a.outputWidthPx to a.outputHeightPx, b.outputWidthPx to b.outputHeightPx)
            }
        }
    }

    @Test
    fun `a single display gets exactly the crop the cropper gives for it`() {
        Property.forAll("one display, one crop") { case ->
            val s = scenario(case, displays = displays(case).take(1))
            val d = s.displays.single()
            val p = plan(s)
            val crop = CropRenderer().computeCropFromFocalPoint(s.srcW, s.srcH, s.focalX, s.focalY, d, s.allowBlur)
            assertEquals("a plan exists exactly when the picture suits the display", crop == null, p == null)
            if (p != null) assertEquals(crop, p.perDisplayCrops[d.displayId])
        }
    }

    // --- The crops themselves ---

    @Test
    fun `no crop leaves the picture or stretches it, and a blurred frame needs permission`() {
        Property.forAll("crops stay inside and keep their shape") { case ->
            val s = scenario(case)
            val p = plan(s) ?: return@forAll
            assertTrue("envelope ${p.envelopeCrop} is outside the picture", p.envelopeCrop.insidePicture(s.srcW, s.srcH))
            assertEquals("a crop for every display served", p.satisfiedDisplays.map { it.displayId }.toSet(), p.perDisplayCrops.keys)
            p.perDisplayCrops.forEach { (id, crop) ->
                val d = s.displays.first { it.displayId == id }
                assertTrue("crop for #$id $crop is outside the picture", crop.insidePicture(s.srcW, s.srcH))
                if (!crop.usesBlurredFrameFill) assertTrue("crop ${crop.width}x${crop.height} for #$id is not its shape", crop.hasShapeOf(d))
            }
            val anyBlurred = p.envelopeCrop.usesBlurredFrameFill || p.perDisplayCrops.values.any { it.usesBlurredFrameFill }
            assertTrue("a blurred frame without permission", !anyBlurred || s.allowBlur)
            if (p.envelopeCrop.usesBlurredFrameFill) {
                assertEquals("a blurred frame shows the whole picture", s.srcW to s.srcH, p.envelopeCrop.width to p.envelopeCrop.height)
            }
        }
    }

    @Test
    fun `the output stays within the 16 MP cap and never enlarges the crop`() {
        Property.forAll("output size") { case ->
            val p = plan(scenario(case)) ?: return@forAll
            assertTrue("output ${p.outputWidthPx}x${p.outputHeightPx} is empty", p.outputWidthPx >= 1 && p.outputHeightPx >= 1)
            assertTrue(
                "output ${p.outputWidthPx}x${p.outputHeightPx} is over 16 MP",
                p.outputWidthPx.toLong() * p.outputHeightPx <= 16_000_000L
            )
            if (!p.envelopeCrop.usesBlurredFrameFill) {
                assertTrue(
                    "output ${p.outputWidthPx}x${p.outputHeightPx} is larger than the ${p.envelopeCrop.width}x${p.envelopeCrop.height} crop it comes from",
                    p.outputWidthPx <= p.envelopeCrop.width && p.outputHeightPx <= p.envelopeCrop.height
                )
            }
        }
    }

    @Test
    fun `a bigger picture of the same shape is never refused when a smaller one was accepted`() {
        Property.forAll("more pixels never hurt") { case ->
            val s = scenario(case, maxSide = 4_500)
            val factor = case.int(2, 4)
            case.input = "$s, enlarged x$factor"
            if (plan(s) != null) assertNotNull("accepted at ${s.srcW}x${s.srcH} but refused enlarged x$factor", plan(s, scale = factor))
        }
    }

    // --- Corrupt or missing input ---

    private val laptop = DisplayProfile(1, "Laptop", 2560, 1600, isPrimary = true, physicalPpi = 227.0)
    private val monitor = DisplayProfile(2, "Monitor", 3440, 1440, isPrimary = false, physicalPpi = 110.0)

    @Test
    fun `a corrupt subject position does not crash the plan`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { bad ->
            DisplayPolicy.values().forEach { policy ->
                val p = planner.planEnvelope(5_000, 3_000, bad, bad, listOf(laptop, monitor), policy, allowBlurredFrameFill = true)
                if (p != null) {
                    assertTrue("subject $bad, $policy: ${p.envelopeCrop}", p.envelopeCrop.insidePicture(5_000, 3_000))
                    assertFalse("subject $bad, $policy gave a non-finite focal point", p.envelopeCrop.focalX.isNaN() || p.envelopeCrop.focalY.isNaN())
                }
            }
        }
    }

    @Test
    fun `a corrupt subject position is never stored in a plan, even in a blurred-frame one`() {
        // A 2000x1500 picture is too small to crop natively for these two displays, but fits them inside a blurred frame.
        val ds = listOf(laptop, monitor)
        val normal = planner.planEnvelope(2_000, 1_500, 0.5, 0.5, ds, DisplayPolicy.SAME_ON_ALL_DISPLAYS, allowBlurredFrameFill = true)
        assertTrue("precondition: this picture is shown in a blurred frame, got $normal", normal?.envelopeCrop?.usesBlurredFrameFill == true)

        val corrupt = planner.planEnvelope(2_000, 1_500, Double.NaN, Double.NaN, ds, DisplayPolicy.SAME_ON_ALL_DISPLAYS, allowBlurredFrameFill = true)
        assertNotNull(corrupt)
        val crops = listOf(corrupt!!.envelopeCrop) + corrupt.perDisplayCrops.values
        crops.forEach { assertEquals("a corrupt subject position is read as the centre: $it", 0.5 to 0.5, it.focalX to it.focalY) }
    }

    @Test
    fun `no displays, or only displays with no size, give no plan`() {
        assertNull(planner.planEnvelope(4_000, 3_000, 0.5, 0.5, emptyList()))
        assertNull(planner.planEnvelope(4_000, 3_000, 0.5, 0.5, listOf(DisplayProfile(1, "Off", 0, 0))))
        val ok = DisplayProfile(1, "Ok", 1_920, 1_080, physicalPpi = 140.0)
        assertNull(planner.planEnvelope(0, 3_000, 0.5, 0.5, listOf(ok)))
        assertNull(planner.planEnvelope(4_000, -3, 0.5, 0.5, listOf(ok)))
    }
}
