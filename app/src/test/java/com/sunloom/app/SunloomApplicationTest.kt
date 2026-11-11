package com.sunloom.app

import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.domain.model.ScheduleMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Article X §1: in Follow-OS mode a change of the system theme is acted on at once while the app is running - and only
 * then: not for other schedules, not while paused, and not for configuration changes that leave the theme as it was.
 */
@Requirement("X.1")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = SunloomApplication::class)
class SunloomApplicationTest {
    private lateinit var app: SunloomApplication
    private var changes = 0

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.reactionScope = CoroutineScope(Dispatchers.Unconfined) // run the reaction inline so the test can see it
        app.runChangeNow = { changes++ }
        changes = 0
    }

    private fun configIn(night: Boolean) = Configuration().apply {
        uiMode = (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO) or Configuration.UI_MODE_TYPE_NORMAL
    }

    private fun prefs(mode: ScheduleMode, paused: Boolean = false) {
        val repo = app.container.newRepository()
        repo.savePreferences(repo.loadPreferences().copy(isSetupCompleted = true, autoChangeEnabled = true, isPaused = paused, scheduleMode = mode))
    }

    @Test
    fun `a theme change in follow-OS mode runs the change at once`() {
        prefs(ScheduleMode.FOLLOW_OS_THEME)
        app.onConfigurationChanged(configIn(night = false))
        assertEquals("the first reading only sets the baseline", 0, changes)

        app.onConfigurationChanged(configIn(night = true))
        assertEquals(1, changes)

        app.onConfigurationChanged(configIn(night = false))
        assertEquals("and back again", 2, changes)
    }

    @Test
    fun `a configuration change that leaves the theme alone does nothing`() {
        prefs(ScheduleMode.FOLLOW_OS_THEME)
        app.onConfigurationChanged(configIn(night = true))
        changes = 0

        app.onConfigurationChanged(configIn(night = true).apply { fontScale = 1.3f })

        assertEquals(0, changes)
    }

    @Test
    fun `other schedules do not follow the system theme`() {
        prefs(ScheduleMode.SUNRISE_SUNSET)
        app.onConfigurationChanged(configIn(night = false))

        app.onConfigurationChanged(configIn(night = true))

        assertEquals(0, changes)
    }

    @Test
    fun `nothing happens while automation is paused`() {
        prefs(ScheduleMode.FOLLOW_OS_THEME, paused = true)
        app.onConfigurationChanged(configIn(night = false))

        app.onConfigurationChanged(configIn(night = true))

        assertEquals(0, changes)
    }
}
