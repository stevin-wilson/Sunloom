package com.sunloom.app.platform

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.platform.scheduler.ChangeAlarm
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The receiver wakes the process to re-arm the schedule (Articles IX §1 and X §3), so it must start work for the
 * actions it was registered for and the app's own change alarm, and ignore everything else without touching the
 * database or WorkManager. Every path that does work asks for the application context first, so a trap context that
 * records that request tells the two apart without needing a database: an ignored broadcast must never reach it, and
 * each accepted one must. (What the work then does needs real SQLite and runs only on CI.)
 */
@Requirement("IX.1", "X.3")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SunloomScheduleReceiverTest {

    private class WorkRequested : RuntimeException("the receiver asked for the application context to start work")

    /** A context that counts requests for the application context and then throws, so no work is ever started. */
    private class TrapContext(base: Context) : ContextWrapper(base) {
        var touched = 0
        override fun getApplicationContext(): Context {
            touched++
            throw WorkRequested()
        }
    }

    private fun trap() = TrapContext(ApplicationProvider.getApplicationContext())

    @Test
    fun `a missing intent is ignored`() {
        val context = trap()
        SunloomScheduleReceiver().onReceive(context, null)
        assertEquals(0, context.touched)
    }

    @Test
    fun `an intent with no action is ignored`() {
        val context = trap()
        SunloomScheduleReceiver().onReceive(context, Intent())
        assertEquals(0, context.touched)
    }

    @Test
    fun `actions the receiver was not registered for are ignored`() {
        val ignored = listOf(
            Intent.ACTION_BOOT_COMPLETED, // WorkManager's own receiver handles boot (see the class note)
            Intent.ACTION_BATTERY_LOW,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "com.example.SPOOFED_ACTION",
            "",
            // Close to a real one but not it: the match is exact.
            "android.intent.action.time_set",
            "android.intent.action.TIME_SET ",
        )
        ignored.forEach { action ->
            val context = trap()
            SunloomScheduleReceiver().onReceive(context, Intent(action))
            assertEquals("'$action' must be ignored", 0, context.touched)
        }
    }

    @Test
    fun `the change alarm and the clock and time zone changes start work`() {
        val accepted = listOf(
            ChangeAlarm.ACTION,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.intent.action.TIME_SET"
        )
        accepted.forEach { action ->
            val context = trap()
            try {
                SunloomScheduleReceiver().onReceive(context, Intent(action))
                fail("'$action' should have started work")
            } catch (_: WorkRequested) {
                // Expected: it reached for the application context, which is where its work begins.
            }
            assertEquals("'$action' should ask for the application context once", 1, context.touched)
        }
    }
}
