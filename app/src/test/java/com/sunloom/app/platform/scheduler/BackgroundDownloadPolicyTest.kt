package com.sunloom.app.platform.scheduler

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Article X §2: background downloads are polite. They stay off metered networks (a phone hotspot counts), respect Data
 * Saver and Battery Saver, and never exceed a daily byte budget. An explicit tap on Refresh is the person's own request
 * and is not subject to these.
 */
@Requirement("X.2")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class BackgroundDownloadPolicyTest {
    private val mb = 1024L * 1024L

    private fun blocked(
        unmeteredOnly: Boolean = true,
        deferOnBatterySaver: Boolean = true,
        unmetered: Boolean = true,
        dataSaver: Boolean = false,
        batterySaver: Boolean = false,
        usedToday: Long = 0L
    ) = BackgroundDownloadPolicy.blockedBy(unmeteredOnly, deferOnBatterySaver, unmetered, dataSaver, batterySaver, usedToday)

    @Test
    fun `an unmetered network with nothing against it is allowed`() {
        assertEquals(DownloadBlock.NONE, blocked())
    }

    @Test
    fun `a metered network is refused by default`() {
        assertEquals(DownloadBlock.METERED_NETWORK, blocked(unmetered = false))
    }

    @Test
    fun `a person who allows metered networks gets them, unless Data Saver is on`() {
        assertEquals(DownloadBlock.NONE, blocked(unmeteredOnly = false, unmetered = false))
        assertEquals(DownloadBlock.DATA_SAVER, blocked(unmeteredOnly = false, unmetered = false, dataSaver = true))
    }

    @Test
    fun `Data Saver does not matter on an unmetered network`() {
        assertEquals(DownloadBlock.NONE, blocked(unmetered = true, dataSaver = true))
    }

    @Test
    fun `Battery Saver defers downloads only when the person asked for that`() {
        assertEquals(DownloadBlock.BATTERY_SAVER, blocked(batterySaver = true))
        assertEquals(DownloadBlock.NONE, blocked(deferOnBatterySaver = false, batterySaver = true))
    }

    @Test
    fun `the daily budget is 300 MB and a day that has used it is refused`() {
        assertEquals(300L * mb, BackgroundDownloadPolicy.DAILY_BUDGET_BYTES)
        assertEquals(DownloadBlock.NONE, blocked(usedToday = 299 * mb))
        assertEquals(DownloadBlock.DAILY_BUDGET, blocked(usedToday = 300 * mb))
        assertEquals(DownloadBlock.DAILY_BUDGET, blocked(usedToday = 900 * mb))
    }

    @Test
    fun `the network reason is reported before the others`() {
        assertEquals(DownloadBlock.METERED_NETWORK, blocked(unmetered = false, batterySaver = true, usedToday = 999 * mb))
    }

    // --- the daily budget's memory ---

    private lateinit var context: Context
    private val zone = ZoneId.of("Europe/Oslo")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("budget_test", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun budget() = BackgroundDataBudget(context.getSharedPreferences("budget_test", Context.MODE_PRIVATE), zone)

    @Test
    fun `bytes add up through the day`() {
        val b = budget()
        b.record(100 * mb, at(2026, 11, 11, 9, 0))
        b.record(50 * mb, at(2026, 11, 11, 15, 30))

        assertEquals(150 * mb, b.usedToday(at(2026, 11, 11, 23, 0)))
    }

    @Test
    fun `a new day starts from zero`() {
        val b = budget()
        b.record(280 * mb, at(2026, 11, 11, 23, 50))

        assertEquals(280 * mb, b.usedToday(at(2026, 11, 11, 23, 59)))
        assertEquals("the budget is per local day", 0L, b.usedToday(at(2026, 11, 12, 0, 5)))
    }

    @Test
    fun `recording after midnight does not carry yesterday forward`() {
        val b = budget()
        b.record(280 * mb, at(2026, 11, 11, 23, 50))

        b.record(10 * mb, at(2026, 11, 12, 0, 10))

        assertEquals(10 * mb, b.usedToday(at(2026, 11, 12, 1, 0)))
    }

    @Test
    fun `it survives being read by a new instance`() {
        budget().record(40 * mb, at(2026, 11, 11, 9, 0))

        assertEquals(40 * mb, budget().usedToday(at(2026, 11, 11, 10, 0)))
    }

    @Test
    fun `nothing negative or zero is recorded`() {
        val b = budget()
        b.record(-5, at(2026, 11, 11, 9, 0))
        b.record(0, at(2026, 11, 11, 9, 0))

        assertEquals(0L, b.usedToday(at(2026, 11, 11, 9, 1)))
    }
}
