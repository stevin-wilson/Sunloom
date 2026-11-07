package com.sunloom.app.data.sources

import com.sunloom.app.Requirement
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Schedule A (NASA): public domain only when no third-party credit is named. Credits are matched as whole words. */
@Requirement("VI.5")
class NasaCreditTest {
    private val nasa = NasaLibrarySourceAdapter()

    private fun third(description: String, photographer: String = "", secondary: String = "") =
        nasa.hasThirdPartyCopyrightOrCredit(description, photographer, secondary)

    @Test
    fun `a person whose name merely contains an agency abbreviation is a third party`() {
        assertTrue(third("Courtesy of Marcus Smith"))
        assertTrue(third("Image credit: Research Team Alpha"))
        assertTrue(third("Photo credit: Jscott Photography"))
    }

    @Test
    fun `genuine government credits still pass`() {
        assertFalse(third("Courtesy of NASA/JPL-Caltech"))
        assertFalse(third("Image credit: NASA Goddard Space Flight Center"))
        assertFalse(third("Credit: U.S. Geological Survey"))
        assertFalse(third("Courtesy of NASA/ARC"))
        assertFalse(third("Image credit: NASA, ESA, CSA, and STScI"))
    }
}
