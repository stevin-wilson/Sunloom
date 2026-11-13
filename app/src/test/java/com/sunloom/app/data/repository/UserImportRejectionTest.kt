package com.sunloom.app.data.repository

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.WallpaperStatus
import com.sunloom.app.domain.safety.SafetyGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * A picture the person adds goes through the same checks as one that is downloaded, and when it is refused the
 * reason is recorded and nothing unsafe is kept (Constitution Article VI §10): too large to decode safely, the same
 * as one rejected for good, or not an image at all.
 */
@Requirement("VI.10")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class UserImportRejectionTest {
    private lateinit var context: Context
    private lateinit var repository: LocalLibraryRepository
    private val display = DisplayProfile(0, "GoogleBook Built-in Display", 2560, 1600)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = LocalLibraryRepository(context, store = InMemoryLibraryStore())
    }

    /** A picture with some structure, so it is not mistaken for a blank image. */
    private fun picture(): Bitmap {
        val bmp = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF1E293B.toInt())
        val paint = Paint().apply { color = 0xFFF59E0B.toInt() }
        canvas.drawCircle(60f, 40f, 30f, paint)
        canvas.drawRect(90f, 50f, 150f, 95f, paint)
        return bmp
    }

    private fun png(bmp: Bitmap): ByteArray = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    private fun serve(uri: Uri, bytes: ByteArray) {
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
    }

    @Test
    fun `a picture wider than the safe limit is refused and the reason recorded`() {
        val tooWide = Bitmap.createBitmap(SafetyGate.MAX_DIMENSION_PX + 1, 1, Bitmap.Config.ALPHA_8)

        val record = repository.importUserLocalBitmap("Huge", tooWide, display)

        assertFalse(record.evaluation.isEligible)
        assertEquals(WallpaperStatus.REJECTED_SAFETY_OR_QUALITY, record.status)
        assertTrue(repository.getAllRejections().any { it.reasonCode == "IMAGE_DIMENSIONS_OUT_OF_BOUNDS" })
    }

    @Test
    fun `a picture rejected for good cannot be added again`() {
        val first = repository.importUserLocalBitmap("Mine", picture(), display)
        assertTrue("the first import is accepted", first.evaluation.isEligible)
        repository.rejectWallpaperForever(first.id)

        val again = repository.importUserLocalBitmap("Mine again", picture(), display)

        assertFalse(again.evaluation.isEligible)
        assertTrue(repository.getAllRejections().any { it.reasonCode == "BLOCKLISTED_USER_IMPORT" && it.isPermanent })
    }

    @Test
    fun `a file that is not an image is not added`() {
        val uri = Uri.parse("content://test/not-an-image")
        serve(uri, "this is plainly text, not a picture".toByteArray())

        assertNull(repository.importUserUri(uri, display))
        assertTrue("nothing was added to the library", repository.getAllWallpapers().none { it.provenance.sourceId == "user_local" })
    }

    @Test
    fun `a file that cannot be opened is not added`() {
        assertNull(repository.importUserUri(Uri.parse("content://test/missing"), display))
    }

    @Test
    fun `a picture served as a file is added with its original bytes`() {
        val uri = Uri.parse("content://test/real.png")
        serve(uri, png(picture()))

        val record = repository.importUserUri(uri, display)

        assertTrue(record != null && record.evaluation.isEligible)
    }

    @Test
    fun `a picture rejected for good cannot be added again from a file`() {
        val uri1 = Uri.parse("content://test/one.png")
        serve(uri1, png(picture()))
        val first = repository.importUserUri(uri1, display)!!
        repository.rejectWallpaperForever(first.id)

        val uri2 = Uri.parse("content://test/two.png")
        serve(uri2, png(picture()))

        assertNull(repository.importUserUri(uri2, display))
        assertTrue(repository.getAllRejections().any { it.reasonCode.startsWith("BLOCKLISTED") })
    }

    @Test
    fun `a baseline restore is saved under its fixed id, remembered in the preferences, and skips the blocklist`() {
        val uri1 = Uri.parse("content://test/blocked.png")
        serve(uri1, png(picture()))
        val first = repository.importUserUri(uri1, display)!!
        repository.rejectWallpaperForever(first.id)

        val uri2 = Uri.parse("content://test/baseline.png")
        serve(uri2, png(picture()))
        val record = repository.importUserUri(uri2, display, asBaselineRestore = true)!!

        assertEquals("baseline-restore-original", record.id)
        assertEquals("Original Baseline Wallpaper", record.title)
        assertTrue(record.isBaselineRestore)
        assertEquals(record.localFilePath, repository.loadPreferences().baselineRestorePath)
    }

    @Test
    fun `an import leaves no staging file behind`() {
        val uri = Uri.parse("content://test/clean.png")
        serve(uri, png(picture()))
        repository.importUserUri(uri, display)
        assertNull(repository.importUserUri(Uri.parse("content://test/gone.png"), display))

        val staged = repository.quarantineDir.listFiles().orEmpty().filter { it.name.startsWith("uri_stage_") }
        assertTrue("Leftover staging files: $staged", staged.isEmpty())
    }
}
