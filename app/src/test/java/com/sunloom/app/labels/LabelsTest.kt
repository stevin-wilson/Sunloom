package com.sunloom.app.labels

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.data.sources.SourceRegistry
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.DisplayPolicy
import com.sunloom.app.domain.model.DisplayQualityTier
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.LockWorkspacePolicy
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.SourceHealthState
import com.sunloom.app.domain.model.WallpaperStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Every choice has a label in strings.xml, and the English there is the English the domain model has always shown, so
 * moving the words out of the code changed nothing for the person reading them (Constitution Article XII §1).
 */
@Requirement("XII.1")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LabelsTest {
    private val r get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    @Test
    fun `categories`() = SafeCategory.entries.forEach {
        assertEquals(it.displayName, it.title(r)); assertEquals(it.description, it.summary(r))
    }

    @Test
    fun `licences, brightness, status, health, lock and display policies`() {
        LicenseType.entries.forEach { assertEquals(it.label, it.title(r)) }
        BrightnessClass.entries.forEach { assertEquals(it.displayName, it.title(r)) }
        WallpaperStatus.entries.forEach { assertEquals(it.label, it.title(r)) }
        SourceHealthState.entries.forEach { assertEquals(it.label, it.title(r)) }
        LockWorkspacePolicy.entries.forEach { assertEquals(it.label, it.title(r)) }
        DisplayPolicy.entries.forEach { assertEquals(it.label, it.title(r)) }
        DisplayQualityTier.entries.forEach { assertEquals(it.shortLabel, it.title(r)) }
    }

    @Test
    fun `every section has a name`() {
        val names = com.sunloom.app.ui.DesktopNavTab.entries.map { it.title(r) }
        assertEquals(listOf("Home", "Reserve", "Sources", "Schedule", "Storage & About"), names)
    }

    @Test
    fun `schedule modes`() = ScheduleMode.entries.forEach {
        assertEquals(it.label, it.title(r)); assertEquals(it.plainDescription, it.summary(r))
    }

    @Test
    fun `every admitted source`() = SourceRegistry.admittedSources().forEach {
        assertEquals(it.sourceName, it.title(r)); assertEquals(it.plainDescription, it.summary(r))
    }
}
