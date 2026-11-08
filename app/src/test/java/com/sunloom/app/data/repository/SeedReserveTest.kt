package com.sunloom.app.data.repository

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.DisplayPolicy
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.renderer.CropRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Article IX §1: the offline seeds are rendered for the connected display set; which target is a pure decision. */
@Requirement("IX.1")
class SeedReserveTest {
    private val laptop = DisplayProfile(0, "Built-in", 2560, 1600, isPrimary = true, densityDpi = 220)
    private val monitor = DisplayProfile(1, "Monitor", 3840, 2160, densityDpi = 160)
    private val portrait = DisplayProfile(2, "Portrait", 1440, 2560, densityDpi = 160)

    @Test
    fun `no usable display gives no target`() {
        assertNull(SeedReserve.renderTarget(emptyList(), DisplayPolicy.SAME_ON_ALL_DISPLAYS))
        assertNull(SeedReserve.renderTarget(listOf(DisplayProfile(0, "x", 0, 0)), DisplayPolicy.SAME_ON_ALL_DISPLAYS))
    }

    @Test
    fun `a single display is its own target`() {
        assertEquals(laptop, SeedReserve.renderTarget(listOf(laptop), DisplayPolicy.SAME_ON_ALL_DISPLAYS))
    }

    @Test
    fun `sharpest-only policy renders for the largest display`() {
        assertEquals(monitor, SeedReserve.renderTarget(listOf(laptop, monitor), DisplayPolicy.OPTIMIZE_PRIMARY_SHARPEST))
    }

    @Test
    fun `compatible displays share one envelope as wide and as tall as the widest and tallest`() {
        val target = SeedReserve.renderTarget(listOf(laptop, monitor), DisplayPolicy.SAME_ON_ALL_DISPLAYS)!!
        val (w, h) = CropRenderer.budgetedSize(3840, 2160)
        assertEquals("Multi-Display Envelope", target.name)
        assertEquals(w, target.widthPx)
        assertEquals(h, target.heightPx)
        assertEquals(160, target.densityDpi)
        assertTrue(target.isPrimary)
    }

    @Test
    fun `a display of the opposite orientation is left out of the envelope`() {
        val target = SeedReserve.renderTarget(listOf(laptop, portrait), DisplayPolicy.SAME_ON_ALL_DISPLAYS)!!
        assertEquals("only the anchor's own orientation counts", laptop.name, target.name)
        assertEquals(CropRenderer.budgetedSize(2560, 1600).first, target.widthPx)
    }

    @Test
    fun `a small display is scaled up so the seed is not rendered tiny`() {
        val (w, h) = SeedReserve.targetSize(DisplayProfile(0, "Small", 200, 100))
        assertTrue("min edge is raised to 400, got ${w}x$h", minOf(w, h) >= 400)
        assertEquals("aspect ratio is kept", 2.0, w.toDouble() / h, 0.01)
    }

    @Test
    fun `a normal display keeps its size within the render budget`() {
        assertEquals(CropRenderer.budgetedSize(2560, 1600), SeedReserve.targetSize(laptop))
    }
}
