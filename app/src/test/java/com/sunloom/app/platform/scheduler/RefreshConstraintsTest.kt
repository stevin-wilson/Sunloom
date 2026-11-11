package com.sunloom.app.platform.scheduler

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import com.sunloom.app.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Article X §2: a background download waits for what it needs - a network of the right kind, a battery that is not low
 * when asked, and room on the device - and a change to those needs reaches installs that already have the job queued.
 */
@Requirement("X.2")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class RefreshConstraintsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the refresh waits for free storage`() {
        val constraints = SunloomWallpaperScheduler(context).buildRefreshConstraints(wifiOnly = false, deferBatterySaver = false)

        assertTrue("downloading into a nearly full disk only makes things worse", constraints.requiresStorageNotLow())
    }

    @Test
    fun `network and battery needs are unchanged`() {
        val scheduler = SunloomWallpaperScheduler(context)

        assertEquals(NetworkType.UNMETERED, scheduler.buildRefreshConstraints(true, false).requiredNetworkType)
        assertEquals(NetworkType.CONNECTED, scheduler.buildRefreshConstraints(false, false).requiredNetworkType)
        assertTrue(scheduler.buildRefreshConstraints(false, true).requiresBatteryNotLow())
    }

    @Test
    fun `the constraint signature changes with the storage need so queued jobs are updated`() {
        // Installs that queued the job before this constraint existed stored this signature.
        val before = "false:false:${RefreshSchedulePolicy.REFRESH_INTERVAL_HOURS}h"

        assertNotEquals(before, SunloomWallpaperScheduler.refreshConstraintsSignature(wifiOnly = false, deferBatterySaver = false))
    }

    @Test
    fun `the signature still tells different network and battery needs apart`() {
        val signatures = listOf(false to false, false to true, true to false, true to true)
            .map { (wifi, battery) -> SunloomWallpaperScheduler.refreshConstraintsSignature(wifi, battery) }

        assertEquals(4, signatures.toSet().size)
    }
}
