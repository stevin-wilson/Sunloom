package com.sunloom.app.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.appContainer
import com.sunloom.app.domain.model.SourceIds
import com.sunloom.app.domain.model.UserPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Resetting through the real gateway puts the app back in its fresh-install state (Article VIII §1). */
@Requirement("VIII.1")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ResetSunloomGatewayTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `reset forgets the person's choices and library and returns to setup`() = runBlocking {
        val gateway = AndroidSunloomGateway(context)
        val first = gateway.loadInitialSnapshot()
        gateway.savePreferences(first.preferences.copy(isSetupCompleted = true, autoChangeEnabled = false))
        val repo = context.appContainer().newRepository()
        val seed = first.libraryWallpapers.first()
        repo.upsertWallpaper(seed.copy(id = "mine-1", provenance = seed.provenance.copy(sourceId = SourceIds.USER_LOCAL, assetId = "mine-1")))
        assertTrue(repo.getAllWallpapers().any { it.id == "mine-1" })

        val after = gateway.resetSunloom()

        assertFalse("setup is shown again", after.preferences.isSetupCompleted)
        assertEquals("settings are back to defaults", UserPreferences().autoChangeEnabled, after.preferences.autoChangeEnabled)
        assertTrue("the person's photos are gone", after.libraryWallpapers.none { it.id == "mine-1" })
        assertTrue("the offline reserve is rebuilt so setup can preview it", after.libraryWallpapers.isNotEmpty())
    }

    @Test
    fun `reset puts the Daytime and Evening wallpapers back to Automatic and forgets the side last seen`() = runBlocking {
        val gateway = AndroidSunloomGateway(context)
        val first = gateway.loadInitialSnapshot()
        val ids = first.libraryWallpapers.map { it.id }
        gateway.savePreferences(first.preferences.copy(daytimePickId = ids[0], eveningPickId = ids[1]))
        context.appContainer().newRepository().setLastTargetSeen(com.sunloom.app.domain.model.BrightnessClass.DARK)

        val after = gateway.resetSunloom()

        assertEquals(null, after.preferences.daytimePickId)
        assertEquals(null, after.preferences.eveningPickId)
        assertEquals(null, context.appContainer().newRepository().getLastTargetSeen())
    }
}
