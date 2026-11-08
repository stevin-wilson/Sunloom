package com.sunloom.app.data.repository

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.data.sources.SourceRegistry
import com.sunloom.app.domain.model.DisplayPolicy
import com.sunloom.app.domain.model.LockWorkspacePolicy
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Article II / III / X §3: what the person chose is stored and read back faithfully, and bad values never crash. */
@Requirement("II.1")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PreferencesStoreTest {
    private lateinit var raw: android.content.SharedPreferences
    private lateinit var store: PreferencesStore

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        raw = context.getSharedPreferences("preferences_store_test", Context.MODE_PRIVATE)
        raw.edit().clear().commit()
        store = PreferencesStore(raw)
    }

    @Test
    fun `an empty file reads as the defaults`() {
        assertEquals(UserPreferences(), store.load().copy(enabledSources = UserPreferences().enabledSources))
        assertFalse(store.load().isSetupCompleted)
        assertTrue(store.load().autoChangeEnabled)
    }

    @Test
    fun `what is saved is read back`() {
        val chosen = UserPreferences(
            isSetupCompleted = true, autoChangeEnabled = false, isPaused = true,
            scheduleMode = ScheduleMode.FIXED_CLOCK, selectedCityName = "Oslo, Norway",
            latitude = 59.91, longitude = 10.75, fixedLightHour = 6, fixedDarkHour = 21, rotationIntervalHours = 3,
            enabledCategories = setOf(SafeCategory.SPACE_ASTRONOMY),
            enabledSources = setOf("nasa_library"),
            displayPolicy = DisplayPolicy.OPTIMIZE_PRIMARY_SHARPEST, lockWorkspacePolicy = LockWorkspacePolicy.LOCK_ONLY,
            allowBlurredFrameFill = true, wifiOnlyDownloads = true, deferOnBatterySaver = false,
            maxCacheBytes = 123_456_789L, baselineRestorePath = "/tmp/base.png"
        )
        store.save(chosen)
        assertEquals(chosen, store.load())
    }

    @Test
    fun `unknown stored values fall back instead of crashing`() {
        raw.edit().putString("schedule_mode", "NOPE").putString("display_policy", "NOPE")
            .putString("lock_workspace_policy", "NOPE").putStringSet("enabled_categories", setOf("NOT_A_CATEGORY")).commit()
        val loaded = store.load()
        assertEquals(ScheduleMode.SUNRISE_SUNSET, loaded.scheduleMode)
        assertEquals(DisplayPolicy.SAME_ON_ALL_DISPLAYS, loaded.displayPolicy)
        assertEquals(LockWorkspacePolicy.SAME_ON_BOTH, loaded.lockWorkspacePolicy)
        assertEquals(UserPreferences().enabledCategories, loaded.enabledCategories)
    }

    @Test
    fun `hours are kept within their range`() {
        store.save(UserPreferences(fixedLightHour = 99, fixedDarkHour = -4, rotationIntervalHours = 400))
        val loaded = store.load()
        assertEquals(23, loaded.fixedLightHour)
        assertEquals(0, loaded.fixedDarkHour)
        assertEquals(24, loaded.rotationIntervalHours)
    }

    @Test
    fun `sources that are not admitted are dropped, and the admitted ones are returned`() {
        val admitted = store.save(UserPreferences(enabledSources = setOf("nasa_library", "not_a_real_source")))
        assertEquals(setOf("nasa_library"), admitted)
        assertEquals(setOf("nasa_library"), store.load().enabledSources)
    }

    @Test
    fun `a source admitted since the last run is switched on for someone who already had a choice`() {
        raw.edit().putStringSet("enabled_sources", setOf("wikimedia_featured")).remove("known_admitted_sources").commit()
        val loaded = store.load()
        val newlyAdmitted = SourceRegistry.admittedSources().map { it.sourceId }.toSet() -
            setOf("seed_reserve", "wikimedia_featured", "nasa_library", "artic_cc0", "met_museum_cc0", "user_local")
        assertTrue("expected some newly admitted source", newlyAdmitted.isNotEmpty())
        assertTrue(loaded.enabledSources.containsAll(newlyAdmitted + "wikimedia_featured"))
        assertTrue("the merge is remembered", raw.getStringSet("known_admitted_sources", null)!!.containsAll(newlyAdmitted))
    }

    @Test
    fun `the old 250 MB cache default moves to the new default once`() {
        raw.edit().putLong("max_cache_bytes", UserPreferences.LEGACY_MAX_CACHE_BYTES).commit()
        assertEquals(UserPreferences.DEFAULT_MAX_CACHE_BYTES, store.load().maxCacheBytes)
        // A later, deliberate choice of 250 MB is respected.
        raw.edit().putLong("max_cache_bytes", UserPreferences.LEGACY_MAX_CACHE_BYTES).commit()
        assertEquals(UserPreferences.LEGACY_MAX_CACHE_BYTES, store.load().maxCacheBytes)
    }

    @Test
    fun `any other cache size is kept`() {
        raw.edit().putLong("max_cache_bytes", 500L * 1024L * 1024L).commit()
        assertEquals(500L * 1024L * 1024L, store.load().maxCacheBytes)
    }

    @Test
    fun `the stored blurred-fill choice can be read without loading everything`() {
        assertFalse(store.allowsBlurredFill())
        store.save(UserPreferences(allowBlurredFrameFill = true))
        assertTrue(store.allowsBlurredFill())
    }
}
