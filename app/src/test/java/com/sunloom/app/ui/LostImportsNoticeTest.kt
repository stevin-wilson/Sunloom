package com.sunloom.app.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.appContainer
import com.sunloom.app.domain.model.SourceIds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * After a restore, personal photos the backup could not bring back are gone for good. The person is told once, in
 * the words of the app, instead of finding the photos missing (Constitution Article IX §5).
 */
@Requirement("IX.5")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LostImportsNoticeTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `lost personal photos are reported once on the next load`() = runBlocking {
        val gateway = AndroidSunloomGateway(context)
        val first = gateway.loadInitialSnapshot()
        val seed = first.libraryWallpapers.first()
        val repo = context.appContainer().newRepository()
        for (n in 1..2) {
            repo.upsertWallpaper(
                seed.copy(
                    id = "lost-$n",
                    localFilePath = "/definitely/gone-$n.png",
                    fileSizeBytes = 123_456L,
                    provenance = seed.provenance.copy(sourceId = SourceIds.USER_LOCAL, assetId = "lost-$n")
                )
            )
        }

        val reported = gateway.loadInitialSnapshot()
        val expected = context.resources.getQuantityString(com.sunloom.app.R.plurals.msg_lost_imports, 2, 2)
        assertEquals(expected, reported.statusBannerMessage)
        assertTrue("it says how many", reported.statusBannerMessage.contains("2"))

        val again = gateway.loadInitialSnapshot()
        assertFalse("the notice is shown once", again.statusBannerMessage == expected)
    }

    @Test
    fun `nothing is reported when no personal photo is missing`() = runBlocking {
        val loaded = AndroidSunloomGateway(context).loadInitialSnapshot()
        assertFalse(loaded.statusBannerMessage.contains("restored"))
    }
}
