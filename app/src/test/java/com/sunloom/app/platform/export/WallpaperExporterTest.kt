package com.sunloom.app.platform.export

import android.app.Application
import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.domain.model.LicenseType
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class WallpaperExporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var exporter: WallpaperExporter
    private lateinit var cache: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        cache = tmp.newFolder("cache")
        exporter = WallpaperExporter(context.contentResolver, cache)
    }

    private fun jpeg(name: String): File {
        val file = tmp.newFile(name)
        val bmp = Bitmap.createBitmap(32, 20, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return file
    }

    @Test
    fun `jpeg export embeds the credit and leaves the source file untouched`() {
        val source = jpeg("a.jpg")
        val before = source.readBytes()
        val record = exportTestRecord(source.absolutePath, LicenseType.CC_BY_SA)
        val out = tmp.newFile("out.jpg")

        assertTrue(exporter.writeImage(record, Uri.fromFile(out)))

        val exif = ExifInterface(out.absolutePath)
        assertTrue(exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)!!.contains("Ada Photographer"))
        assertEquals("Ada Photographer", exif.getAttribute(ExifInterface.TAG_ARTIST))
        assertTrue(source.readBytes().contentEquals(before))
        assertEquals("no temp files left behind", 0, cache.listFiles()!!.size)
    }

    @Test
    fun `cc by and cc by sa require the companion attribution file but cc0 does not`() {
        val path = jpeg("b.jpg").absolutePath
        assertTrue(exporter.requiresAttributionFile(exportTestRecord(path, LicenseType.CC_BY)))
        assertTrue(exporter.requiresAttributionFile(exportTestRecord(path, LicenseType.CC_BY_SA)))
        assertFalse(exporter.requiresAttributionFile(exportTestRecord(path, LicenseType.CC0)))
        assertFalse(exporter.requiresAttributionFile(exportTestRecord(path, LicenseType.PUBLIC_DOMAIN)))
    }

    @Test
    fun `attribution file contains the formatted credit`() {
        val record = exportTestRecord(jpeg("c.jpg").absolutePath, LicenseType.CC_BY)
        val out = tmp.newFile("credit.txt")
        assertTrue(exporter.writeAttribution(record, Uri.fromFile(out)))
        assertTrue(out.readText().contains("by Ada Photographer"))
    }

    @Test
    fun `rejected ineligible or missing wallpapers are never exportable`() {
        val path = jpeg("d.jpg").absolutePath
        assertFalse(exporter.isExportable(exportTestRecord(path, rejected = true)))
        assertFalse(exporter.isExportable(exportTestRecord(path, eligible = false)))
        assertFalse(exporter.isExportable(exportTestRecord(File(tmp.root, "gone.jpg").absolutePath)))
        val out = tmp.newFile("blocked.jpg")
        assertFalse(exporter.writeImage(exportTestRecord(path, rejected = true), Uri.fromFile(out)))
        assertEquals(0L, out.length())
    }

    @Test
    fun `suggested names are filesystem safe and keep the extension`() {
        val record = exportTestRecord(jpeg("e.jpg").absolutePath)
        assertEquals("Alpine Lake Dawn Mist.jpg", exporter.suggestedImageName(record))
        assertEquals("Alpine Lake Dawn Mist-attribution.txt", exporter.suggestedAttributionName(record))
        assertEquals("image/jpeg", exporter.mimeTypeFor(record))
    }
}
