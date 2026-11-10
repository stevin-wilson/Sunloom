package com.sunloom.app.ui

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** On a real Android runtime, the single activity starts and stays up (Constitution Article XVI). */
@RunWith(AndroidJUnit4::class)
class AppLaunchSmokeTest {
    @Test
    fun mainActivityLaunchesAndResumes() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
