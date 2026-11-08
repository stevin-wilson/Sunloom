package com.sunloom.app.platform

import com.sunloom.app.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Article V §2: independent lock-screen support is only claimed from evidence. Setting the home and lock wallpapers
 * together proves nothing (the system may simply share one picture), so only a lock-only apply can show it.
 */
@Requirement("V.2")
class LockSupportEvidenceTest {
    @Test
    fun `a combined home and lock apply is no evidence either way`() {
        assertNull(LockSupportEvidence.observe(wantsSystem = true, wantsLock = true, afterLockId = 7, lockChanged = true, result = 3))
        assertNull(LockSupportEvidence.observe(wantsSystem = true, wantsLock = true, afterLockId = -1, lockChanged = false, result = 0))
    }

    @Test
    fun `a home-only apply says nothing about the lock screen`() {
        assertNull(LockSupportEvidence.observe(wantsSystem = true, wantsLock = false, afterLockId = -1, lockChanged = false, result = 3))
    }

    @Test
    fun `a lock-only apply that took effect shows independent lock support`() {
        assertEquals(true, LockSupportEvidence.observe(wantsSystem = false, wantsLock = true, afterLockId = 9, lockChanged = true, result = 0))
        assertEquals(true, LockSupportEvidence.observe(wantsSystem = false, wantsLock = true, afterLockId = 9, lockChanged = false, result = 4))
    }

    @Test
    fun `a lock-only apply that left no lock wallpaper shows it is unsupported`() {
        assertEquals(false, LockSupportEvidence.observe(wantsSystem = false, wantsLock = true, afterLockId = -1, lockChanged = false, result = 4))
    }
}
