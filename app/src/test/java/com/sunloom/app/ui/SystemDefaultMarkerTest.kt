package com.sunloom.app.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.appContainer
import com.sunloom.app.domain.model.ScheduleMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Putting the system default back is remembered, so a Follow-OS poll does not cover it up (Article X §1). */
@Requirement("X.1")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SystemDefaultMarkerTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `restoring the system default records it for the current theme`() = runBlocking {
        val gateway = AndroidSunloomGateway(context)
        val loaded = gateway.loadInitialSnapshot()
        gateway.savePreferences(loaded.preferences.copy(isSetupCompleted = true, scheduleMode = ScheduleMode.FOLLOW_OS_THEME))

        gateway.restoreSystemDefaultWallpaper()

        val repo = context.appContainer().newRepository()
        assertNull("no wallpaper of ours is current", repo.getCurrentWallpaperId())
        assertEquals("LIGHT:@system", repo.getFollowOsVerifiedKey()) // Robolectric's default theme is light
    }
}
