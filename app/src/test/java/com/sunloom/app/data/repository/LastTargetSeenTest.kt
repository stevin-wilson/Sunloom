package com.sunloom.app.data.repository

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.BrightnessClass
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Article III §1 (v0.59): a chosen picture is applied when its period starts, so the app remembers which side (daytime
 * or evening) it last handled. It is derived state: kept in the file Auto Backup does not restore, because a side
 * remembered on another device says nothing about what is on this one's screen.
 */
@Requirement("III.1")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LastTargetSeenTest {
    private lateinit var context: Context
    private lateinit var repo: LocalLibraryRepository

    private fun state() = context.getSharedPreferences("sunloom_state", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("sunloom_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        state().edit().clear().commit()
        repo = LocalLibraryRepository(context = context, sourceAdapters = emptyList(), store = InMemoryLibraryStore())
    }

    @Test
    fun `no side is remembered until one is recorded`() {
        assertNull(repo.getLastTargetSeen())
    }

    @Test
    fun `a recorded side is read back and the latest one wins`() {
        repo.setLastTargetSeen(BrightnessClass.LIGHT)
        assertEquals(BrightnessClass.LIGHT, repo.getLastTargetSeen())

        repo.setLastTargetSeen(BrightnessClass.DARK)
        assertEquals(BrightnessClass.DARK, repo.getLastTargetSeen())
    }

    @Test
    fun `a balanced target is not remembered as a side`() {
        repo.setLastTargetSeen(BrightnessClass.DARK)

        repo.setLastTargetSeen(BrightnessClass.NEUTRAL)

        assertEquals(BrightnessClass.DARK, repo.getLastTargetSeen())
    }

    @Test
    fun `a stored value that is not a side reads as nothing remembered`() {
        state().edit().putString("last_target_seen", "NOT_A_SIDE").commit()
        assertNull(repo.getLastTargetSeen())

        state().edit().putString("last_target_seen", BrightnessClass.NEUTRAL.name).commit()
        assertNull(repo.getLastTargetSeen())

        state().edit().remove("last_target_seen").putInt("last_target_seen", 3).commit()
        assertNull(repo.getLastTargetSeen())
    }

    @Test
    fun `it is kept in the state file, not the preferences file a backup restores`() {
        repo.setLastTargetSeen(BrightnessClass.LIGHT)

        assertEquals("LIGHT", state().getString("last_target_seen", null))
        assertFalse(
            context.getSharedPreferences("sunloom_preferences", Context.MODE_PRIVATE).contains("last_target_seen")
        )
    }

    @Test
    fun `applying a wallpaper does not change which side is remembered`() {
        repo.setLastTargetSeen(BrightnessClass.DARK)

        repo.recordWallpaperApplied("any")

        assertEquals(BrightnessClass.DARK, repo.getLastTargetSeen())
    }

    @Test
    fun `resetting Sunloom forgets the side`() {
        repo.setLastTargetSeen(BrightnessClass.LIGHT)

        runBlocking { repo.wipeAllData() }

        assertNull(repo.getLastTargetSeen())
    }
}
