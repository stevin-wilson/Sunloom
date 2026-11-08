package com.sunloom.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CropRectTest {
    @Test
    fun `a whole-picture blurred fill keeps the full frame and measures coverage against the display`() {
        val display = DisplayProfile(0, "D", 2560, 1600)
        val rect = CropRect.wholePictureBlurred(1280, 400, display)
        assertEquals(0, rect.left)
        assertEquals(0, rect.top)
        assertEquals(1280, rect.width)
        assertEquals(400, rect.height)
        assertTrue(rect.usesBlurredFrameFill)
    }

    @Test
    fun `coverage is the smaller of the two axis ratios and the focal point is kept`() {
        val rect = CropRect.wholePictureBlurred(1280, 400, DisplayProfile(0, "D", 2560, 1600), focalX = 0.3, focalY = 0.7)
        assertEquals(0.25, rect.pixelCoverageRatio, 1e-9)
        assertEquals(0.3, rect.focalX, 1e-9)
        assertEquals(0.7, rect.focalY, 1e-9)
    }

    @Test
    fun `an unsized display does not divide by zero`() {
        val rect = CropRect.wholePictureBlurred(100, 100, DisplayProfile(0, "D", 0, 0))
        assertTrue(rect.pixelCoverageRatio.isFinite())
    }
}
