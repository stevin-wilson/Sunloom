package com.sunloom.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The ViewModel has no Context, so JVM tests use [UiStrings.English]. This keeps that copy identical to the
 * text in `strings.xml`, which is what users see.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
@Requirement("XII.1")
class UiStringsTest {

    @Test
    fun `the Android strings match the English defaults the tests use`() {
        val android = AndroidUiStrings(ApplicationProvider.getApplicationContext())
        val english = UiStrings.English
        assertEquals(english.loadingLibrary, android.loadingLibrary)
        assertEquals(english.manualModeEnabled, android.manualModeEnabled)
        assertEquals(english.settingNotSaved, android.settingNotSaved)
        assertEquals(english.storageProblem, android.storageProblem)
        assertEquals(english.fetchingCatalogs, android.fetchingCatalogs)
        assertEquals(english.scheduleEnabled("Sunrise & Sunset"), android.scheduleEnabled("Sunrise & Sunset"))
        assertEquals(english.syncInterrupted(null), android.syncInterrupted(null))
        assertEquals(english.syncInterrupted("timeout"), android.syncInterrupted("timeout"))
        for (n in listOf(1, 5)) assertEquals(english.batchChecked(n), android.batchChecked(n))
        assertEquals(english.importingPhoto(2, 5), android.importingPhoto(2, 5))
        for (n in listOf(1, 2, 7)) assertEquals(english.wallpaperCount(n), android.wallpaperCount(n))
    }
}
