package com.sunloom.app.platform

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A failed wallpaper change must never crash the app or the worker, but a broken program (any other Error) must not
 * be swallowed either.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class WallpaperChangeGuardTest {

    @Test
    fun `a change that succeeds is reported as it returned`() {
        assertTrue(guardedWallpaperChange({ fail("host did not refuse") }) { true })
        assertFalse(guardedWallpaperChange({ fail("host did not refuse") }) { false })
    }

    @Test
    fun `a host refusal is reported and noted`() {
        var refused = false
        val result = guardedWallpaperChange({ refused = true }) { throw SecurityException("no") }
        assertFalse(result)
        assertTrue(refused)
    }

    @Test
    fun `an ordinary failure is a failed change, not a crash`() {
        var refused = false
        assertFalse(guardedWallpaperChange({ refused = true }) { throw IllegalStateException("boom") })
        assertFalse(refused)
    }

    @Test
    fun `running out of memory is a failed change, not a crash`() {
        assertFalse(guardedWallpaperChange({}) { throw OutOfMemoryError("envelope too large") })
    }

    @Test
    fun `any other Error still propagates`() {
        try {
            guardedWallpaperChange({}) { throw AssertionError("a bug") }
            fail("Expected the Error to propagate")
        } catch (e: AssertionError) {
            assertEquals("a bug", e.message)
        }
    }
}
