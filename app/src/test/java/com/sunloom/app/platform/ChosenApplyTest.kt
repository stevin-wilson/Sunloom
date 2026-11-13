package com.sunloom.app.platform

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.data.testWallpaper
import com.sunloom.app.domain.model.CropRect
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.LockWorkspacePolicy
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.model.WallpaperStatus
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Article III §1 / IV §4 (v0.59): a picture the person chose for the daytime or evening is shown even when it is too
 * small for the display. It skips only the resolution-quality checks and falls back to a blurred frame fill; nothing
 * else that refuses a picture (rejected, unsafe, unknown size) is skipped.
 */
@Requirement("III.1")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ChosenApplyTest {
    private lateinit var context: Context
    private val ultrawide = DisplayProfile(0, "External Ultrawide", 3440, 1440, isPrimary = true)

    private class RecordingGateway(private val displays: List<DisplayProfile>) : SystemWallpaperGateway {
        var applied = 0
        var width = 0
        var height = 0
        override fun isWallpaperSupported() = true
        override fun isSetWallpaperAllowed() = true
        override fun getConnectedDisplays() = displays
        override fun applyBitmapToDestination(bitmap: Bitmap, flags: Int): Boolean {
            applied++
            width = bitmap.width
            height = bitmap.height
            return true
        }
        override fun isUnmeteredNetwork() = true
        override fun isBatterySaverActive() = false
    }

    private lateinit var gateway: RecordingGateway
    private lateinit var adapter: GoogleBookPlatformAdapter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        gateway = RecordingGateway(listOf(ultrawide))
        adapter = GoogleBookPlatformAdapter(context, gateway)
    }

    /** A fetched picture of 400 x 300 that the display pass has already marked as too small for the ultrawide. */
    private fun tooSmall(
        reasonCode: String = "RESOLUTION_BELOW_DISPLAY_TARGET",
        rejected: Boolean = false
    ): WallpaperRecord {
        val file = File(context.cacheDir, "chosen-small.png")
        val bmp = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF446688.toInt()) }
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        val base = testWallpaper("small", localFilePath = file.absolutePath, croppedFilePath = file.absolutePath, fileSizeBytes = file.length())
        return base.copy(
            isRejected = rejected,
            provenance = base.provenance.copy(originalWidth = 400, originalHeight = 300, renditionWidth = 400, renditionHeight = 300),
            evaluation = base.evaluation.copy(
                isEligible = false,
                rejectionReasonCode = reasonCode,
                cropRect = CropRect(0, 0, 400, 300, 0.5, 0.5, false, 0.1)
            )
        )
    }

    private fun apply(record: WallpaperRecord, chosen: Boolean) = adapter.applyWallpaperAtomically(
        record = record,
        policy = LockWorkspacePolicy.SAME_ON_BOTH,
        targetDisplay = ultrawide,
        connectedDisplays = listOf(ultrawide),
        chosen = chosen
    )

    @Test
    fun `the same too-small picture is refused when it was not chosen`() {
        assertEquals(WallpaperStatus.FAILED_TEMPORARILY, apply(tooSmall(), chosen = false))
        assertEquals(0, gateway.applied)
    }

    @Test
    fun `a chosen picture that is too small for the display is applied with a blurred frame fill`() {
        val status = apply(tooSmall(), chosen = true)

        assertEquals(WallpaperStatus.APPLIED_SUCCESSFULLY, status)
        assertEquals(1, gateway.applied)
        // The canvas is the display's own shape; the small picture sits in it with a blurred backdrop.
        val aspect = gateway.width.toDouble() / gateway.height
        assertEquals(ultrawide.aspectRatio, aspect, 0.02)
    }

    @Test
    fun `both resolution quality verdicts are skipped for a chosen picture`() {
        listOf("INSUFFICIENT_POST_CROP_RESOLUTION", "COMPOSITION_CROPS_TOO_MUCH", "RESOLUTION_BELOW_DISPLAY_TARGET").forEach { code ->
            val status = apply(tooSmall(reasonCode = code), chosen = true)
            assertEquals(code, WallpaperStatus.APPLIED_SUCCESSFULLY, status)
        }
    }

    @Test
    fun `a chosen picture that was rejected is still refused`() {
        assertEquals(WallpaperStatus.FAILED_TEMPORARILY, apply(tooSmall(rejected = true), chosen = true))
        assertEquals(0, gateway.applied)
    }

    @Test
    fun `a chosen picture with an unreadable size is still refused`() {
        assertEquals(WallpaperStatus.FAILED_TEMPORARILY, apply(tooSmall(reasonCode = "INVALID_DIMENSIONS"), chosen = true))
        assertEquals(0, gateway.applied)
    }

    @Test
    fun `a chosen picture refused for any other reason is still refused`() {
        assertEquals(WallpaperStatus.FAILED_TEMPORARILY, apply(tooSmall(reasonCode = "SAFETY_UNSAFE_CONTENT"), chosen = true))
        assertEquals(0, gateway.applied)
    }

    @Test
    fun `a chosen picture whose file is gone is refused`() {
        val gone = tooSmall().copy(localFilePath = File(context.cacheDir, "nope.png").absolutePath)
        assertEquals(WallpaperStatus.FAILED_TEMPORARILY, apply(gone, chosen = true))
    }

    @Test
    fun `choosing does not change how a picture that is large enough is applied`() {
        val file = File(context.cacheDir, "chosen-large.png")
        val bmp = Bitmap.createBitmap(4000, 2500, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF668844.toInt()) }
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        val base = testWallpaper("large", localFilePath = file.absolutePath, croppedFilePath = file.absolutePath, fileSizeBytes = file.length())
        val large = base.copy(
            provenance = base.provenance.copy(originalWidth = 4000, originalHeight = 2500, renditionWidth = 4000, renditionHeight = 2500),
            evaluation = base.evaluation.copy(isEligible = true, rejectionReasonCode = null)
        )

        assertEquals(WallpaperStatus.APPLIED_SUCCESSFULLY, apply(large, chosen = false))
        val plainW = gateway.width
        val plainH = gateway.height

        assertEquals(WallpaperStatus.APPLIED_SUCCESSFULLY, apply(large, chosen = true))
        assertEquals(plainW, gateway.width)
        assertEquals(plainH, gateway.height)
    }
}
