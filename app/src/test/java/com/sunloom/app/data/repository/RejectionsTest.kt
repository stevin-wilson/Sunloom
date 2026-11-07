package com.sunloom.app.data.repository

import com.sunloom.app.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Rejections come in two kinds that must never be mixed up: permanent ones (safety, licence, the user's own
 * choice) block an image on every display, display-scoped ones (size, quality, duplicates) only on the display
 * set they were judged for. One factory builds both so a call site cannot forget which flag or signature to set.
 */
@Requirement("VII.4")
class RejectionsTest {

    @Test
    fun `a permanent rejection is not tied to any display`() {
        val r = Rejections.permanent("a1", "met_cc0", "BLOCKLISTED_PERCEPTUAL_HASH", "matches a blocked image", nowMs = 42L)
        assertTrue(r.isPermanent)
        assertEquals("", r.displaySignature)
        assertEquals(42L, r.timestampEpochMs)
        assertEquals("a1", r.assetId)
        assertEquals("met_cc0", r.sourceId)
        assertEquals("BLOCKLISTED_PERCEPTUAL_HASH", r.reasonCode)
    }

    @Test
    fun `a display-scoped rejection carries the display signature and is not permanent`() {
        val r = Rejections.displayScoped("a2", "smk_open", "NEAR_DUPLICATE_OF_LIBRARY", "looks like one we have", "3440x1440@149", nowMs = 7L)
        assertFalse(r.isPermanent)
        assertEquals("3440x1440@149", r.displaySignature)
        assertEquals(7L, r.timestampEpochMs)
    }

    @Test
    fun `a display-scoped rejection without a signature is refused because it would never match`() {
        try {
            Rejections.displayScoped("a3", "nasa_library", "COMPOSITION_CROPS_TOO_MUCH", "x", "  ")
            fail("expected a blank display signature to be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("signature"))
        }
    }
}
