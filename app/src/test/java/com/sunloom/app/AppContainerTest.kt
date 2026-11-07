package com.sunloom.app

import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** One place builds the app's long-lived collaborators, so workers, the receiver and the UI cannot drift apart. */
@RunWith(AndroidJUnit4::class)
class AppContainerTest {

    @Test
    @Config(sdk = [34])
    fun `the manifest names the Application class and the container hangs off it`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val info = app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA)
        assertEquals(SunloomApplication::class.java.name, info.className)
        assertSame(
            "the same container is handed out every time",
            (app as SunloomApplication).container,
            app.appContainer()
        )
    }

    @Test
    @Config(sdk = [34], application = Application::class)
    fun `a plain Application still gets a working container`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertNotNull(app.appContainer())
        assertNotNull(app.appContainer().newRepository())
    }
}
