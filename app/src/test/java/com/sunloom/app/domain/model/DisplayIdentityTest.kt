package com.sunloom.app.domain.model

import com.sunloom.app.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Article IV §6: a display set is identified by what the displays *are* (size, density, quality tier),
 * never by the ids Android hands out, so re-plugging the same monitor is not a display change.
 */
@Requirement("IV.6")
class DisplayIdentityTest {

    private val laptop = DisplayProfile(0, "Built-in", 2560, 1600, densityDpi = 240)
    private val ultrawide = DisplayProfile(3, "Ultrawide", 3440, 1440, densityDpi = 160)

    @Test
    fun `signature ignores displayId and name`() {
        val replugged = ultrawide.copy(displayId = 11, name = "DELL U3423WE")
        assertEquals(ultrawide.signature, replugged.signature)
    }

    @Test
    fun `signature still changes with size or density`() {
        assertNotEquals(ultrawide.signature, ultrawide.copy(widthPx = 3840, heightPx = 2160).signature)
        assertNotEquals(ultrawide.signature, ultrawide.copy(densityDpi = 240).signature)
    }

    @Test
    fun `display set signature is stable when a monitor is replugged under a new id`() {
        val before = displaySetSignature(listOf(laptop, ultrawide))
        val after = displaySetSignature(listOf(laptop, ultrawide.copy(displayId = 17)))
        assertEquals(before, after)
    }

    @Test
    fun `display set signature does not depend on enumeration order`() {
        assertEquals(
            displaySetSignature(listOf(laptop, ultrawide)),
            displaySetSignature(listOf(ultrawide, laptop))
        )
    }

    @Test
    fun `display set signature differs when a display is added or removed`() {
        assertNotEquals(displaySetSignature(listOf(laptop)), displaySetSignature(listOf(laptop, ultrawide)))
    }

    @Test
    fun `display set signature changes with which display is primary`() {
        val laptopPrimary = listOf(laptop.copy(isPrimary = true), ultrawide.copy(isPrimary = false))
        val externalPrimary = listOf(laptop.copy(isPrimary = false), ultrawide.copy(isPrimary = true))
        assertNotEquals(displaySetSignature(laptopPrimary), displaySetSignature(externalPrimary))
    }

    @Test
    fun `display set geometry key ignores ids and order but tracks the set of geometries`() {
        val docked = displaySetGeometryKey(listOf(laptop, ultrawide))
        assertEquals(docked, displaySetGeometryKey(listOf(ultrawide.copy(displayId = 42), laptop)))
        assertNotEquals(docked, displaySetGeometryKey(listOf(laptop)))
    }

    @Test
    fun `changing which display is primary changes the geometry key`() {
        // The planner anchors on the primary display, so the same screens with another primary plan differently.
        val laptopPrimary = listOf(laptop.copy(isPrimary = true), ultrawide.copy(isPrimary = false))
        val externalPrimary = listOf(laptop.copy(isPrimary = false), ultrawide.copy(isPrimary = true))
        assertNotEquals(displaySetGeometryKey(laptopPrimary), displaySetGeometryKey(externalPrimary))
    }

    @Test
    fun `two identical monitors are two displays`() {
        val twin = ultrawide.copy(displayId = 4)
        assertNotEquals(
            displaySetGeometryKey(listOf(laptop, ultrawide)),
            displaySetGeometryKey(listOf(laptop, ultrawide, twin))
        )
    }
}
