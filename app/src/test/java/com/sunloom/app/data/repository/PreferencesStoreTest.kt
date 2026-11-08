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
            enabledSources = setOf(SourceRegistry.admittedSources().first().sourceId),
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
        val real = SourceRegistry.admittedSources().first().sourceId
        val result = store.save(UserPreferences(enabledSources = setOf(real, "not_a_real_source")))
        assertEquals(setOf(real), result.enabledSources)
        assertEquals(setOf(real), store.load().enabledSources)
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
    fun `the default sources come from the registry, and NASA is not among them`() {
        val defaults = UserPreferences().enabledSources
        assertFalse("NASA is opt-in", "nasa_library" in defaults)
        assertEquals(SourceRegistry.defaultEnabledSourceIds(), defaults)
        assertTrue("every default is an admitted source", defaults.all { SourceRegistry.isSourceAdmitted(it) })
        assertTrue(defaults.containsAll(setOf("seed_reserve", "user_local", "wikimedia_featured", "artic_cc0", "met_museum_cc0")))
    }

    @Test
    fun `someone who had NASA on by default has it switched off once, and can switch it back on`() {
        raw.edit()
            .putStringSet("enabled_sources", setOf("seed_reserve", "wikimedia_featured", "nasa_library", "user_local"))
            .putStringSet("known_admitted_sources", SourceRegistry.admittedSources().map { it.sourceId }.toSet())
            .commit()
        val first = store.load()
        assertFalse("nasa_library" in first.enabledSources)
        assertTrue("wikimedia_featured" in first.enabledSources)

        // The person turns NASA on again: that choice is kept, the reset does not run twice.
        store.save(first.copy(enabledSources = first.enabledSources + "nasa_library"))
        assertTrue("nasa_library" in store.load().enabledSources)
        assertTrue("nasa_library" in PreferencesStore(raw).load().enabledSources)
    }

    @Test
    fun `a source that is off by default is not switched on when it is newly admitted`() {
        raw.edit().putStringSet("enabled_sources", setOf("wikimedia_featured")).remove("known_admitted_sources").commit()
        val loaded = store.load()
        assertFalse("an opt-in source stays off for existing users", "nasa_library" in loaded.enabledSources)
        assertTrue("a default-on source admitted since is switched on", "smk_open" in loaded.enabledSources)
    }

    @Test
    fun `a source that no longer exists is dropped without error`() {
        raw.edit()
            .putStringSet("enabled_sources", setOf("wikimedia_featured", "a_source_removed_later"))
            .putStringSet("known_admitted_sources", SourceRegistry.admittedSources().map { it.sourceId }.toSet() + "a_source_removed_later")
            .commit()
        assertEquals(setOf("wikimedia_featured"), store.load().enabledSources)
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
    fun `saving reports whether the blurred-fill choice changed, decided in the same step as the write`() {
        assertFalse(store.save(UserPreferences(allowBlurredFrameFill = false)).blurredFillChanged)
        assertTrue(store.save(UserPreferences(allowBlurredFrameFill = true)).blurredFillChanged)
        assertFalse(store.save(UserPreferences(allowBlurredFrameFill = true)).blurredFillChanged)
        assertTrue(store.save(UserPreferences(allowBlurredFrameFill = false)).blurredFillChanged)
    }

    @Test
    fun `a 250 MB choice saved before anything was loaded is not migrated away afterwards`() {
        // A save is a deliberate choice: it must settle the one-time migration instead of leaving it to override it.
        store.save(UserPreferences(maxCacheBytes = UserPreferences.LEGACY_MAX_CACHE_BYTES))
        assertEquals(UserPreferences.LEGACY_MAX_CACHE_BYTES, store.load().maxCacheBytes)
    }
}
