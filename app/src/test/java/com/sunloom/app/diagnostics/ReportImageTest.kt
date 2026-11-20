package com.sunloom.app.diagnostics

import com.sunloom.app.Requirement
import com.sunloom.app.data.testWallpaper
import com.sunloom.app.domain.model.SourceIds
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Report this image": a pre-filled GitHub issue that names an image by its public source details so the maintainer
 * can review it and, if confirmed, add it to the denylist in a later release (Article VII §11; VIII §2). It never
 * carries the picture, a file path, or anything about the device, and it is never offered for the user's own images.
 */
@Requirement("VII.11", "VIII.2")
class ReportImageTest {
    private val info = AppInfo("2.0.0", 20, "github", 34, "Test Book")
    private val remote = testWallpaper("met-123", sourceId = "met_museum_cc0")

    private fun fields(url: String): Map<String, String> =
        url.substringAfter("?").split("&").associate {
            URLDecoder.decode(it.substringBefore("="), "UTF-8") to URLDecoder.decode(it.substringAfter("="), "UTF-8")
        }

    @Test
    fun `the link opens the image report form on the project`() {
        val url = ReportImage.issueUrl(info, remote)!!

        assertTrue(url.startsWith("https://github.com/stevin-wilson/Sunloom/issues/new?"))
        assertEquals("image_report.yml", fields(url)["template"])
        assertTrue(fields(url).getValue("title").contains("Wallpaper met-123"))
    }

    @Test
    fun `the details name the image by its public source information and the gate that approved it`() {
        val details = fields(ReportImage.issueUrl(info, remote)!!).getValue("details")

        assertTrue(details.contains("met_museum_cc0"))
        assertTrue(details.contains("met-123"))
        assertTrue(details.contains("https://commons.wikimedia.org/wiki/met-123"))
        assertTrue(details.contains(remote.provenance.safetyGateVersion))
        assertTrue(details.contains("2.0.0 (20) github"))
    }

    @Test
    fun `the details carry no file path, no checksum of the file and nothing about the device`() {
        val record = remote.copy(localFilePath = "/data/user/0/com.sunloom.app/files/wallpapers/met-123.jpg")
        val details = fields(ReportImage.issueUrl(info, record)!!).getValue("details")

        assertFalse(details.contains("/data/user"))
        assertFalse(details.contains("Test Book"))
        assertFalse(details.contains(record.provenance.sha256Checksum))
    }

    @Test
    fun `a path or content address in the title is scrubbed`() {
        val record = remote.copy(title = "Scan of content://media/external/images/42 and /sdcard/Pictures/a.jpg")
        val url = ReportImage.issueUrl(info, record)!!

        assertFalse(url.contains("content%3A%2F%2F"))
        assertFalse(URLDecoder.decode(url, "UTF-8").contains("/sdcard"))
    }

    @Test
    fun `a very long title or address still gives a link a browser accepts`() {
        val long = "x".repeat(5_000)
        val record = remote.copy(
            title = long,
            provenance = remote.provenance.copy(originalPageUrl = "https://example.org/$long", attributionText = long)
        )

        assertTrue(ReportImage.issueUrl(info, record)!!.length < 2_000)
    }

    @Test
    fun `a title and creator in a script that expands when encoded still give a short link`() {
        val cjk = "\u6771\u4eac\u306e\u591c\u666f".repeat(40)
        val record = remote.copy(
            title = cjk,
            provenance = remote.provenance.copy(creatorName = cjk, sourceName = cjk, safetyClassificationSource = cjk)
        )

        val url = ReportImage.issueUrl(info, record)!!
        assertTrue("was ${url.length}", url.length <= 2_000)
        assertTrue("it still names the picture", fields(url).getValue("details").contains("met-123"))
    }

    @Test
    fun `a source page address is kept whole when it fits and loses only its query when it does not`() {
        val short = "https://commons.wikimedia.org/wiki/File:Example.jpg?uselang=en"
        assertTrue(fields(ReportImage.issueUrl(info, remote.copy(provenance = remote.provenance.copy(originalPageUrl = short)))!!)
            .getValue("details").contains(short))

        val long = "https://museum.example/object/42?" + "q=x&".repeat(200)
        val details = fields(ReportImage.issueUrl(info, remote.copy(provenance = remote.provenance.copy(originalPageUrl = long)))!!)
            .getValue("details")
        assertTrue("the address still works", details.contains("Source page: https://museum.example/object/42\n"))
    }

    @Test
    fun `pictures from the sources can be reported`() {
        assertTrue(ReportImage.canReport(remote))
        assertNotNull(ReportImage.issueUrl(info, remote))
    }

    @Test
    fun `the user's own imported photos cannot be reported`() {
        val imported = testWallpaper("local-1", sourceId = SourceIds.USER_LOCAL)

        assertFalse(ReportImage.canReport(imported))
        assertNull(ReportImage.issueUrl(info, imported))
    }

    @Test
    fun `the saved original wallpaper cannot be reported`() {
        val baseline = testWallpaper("baseline-1", sourceId = "met_museum_cc0", isBaselineRestore = true)

        assertFalse(ReportImage.canReport(baseline))
        assertNull(ReportImage.issueUrl(info, baseline))
    }
}
