package com.sunloom.app.domain.attribution

import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.platform.export.exportTestRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttributionFormatterTest {

    @Test
    fun `cc by sa credit names title creator source license link and the modification note`() {
        val text = AttributionFormatter.format(exportTestRecord("/x.jpg", LicenseType.CC_BY_SA))
        assertTrue(text, text.contains("\"Alpine Lake: Dawn/Mist\" by Ada Photographer"))
        assertTrue(text, text.contains("Source: Wikimedia Commons — https://commons.wikimedia.org/wiki/File:Alpine.jpg"))
        assertTrue(text, text.contains("ShareAlike") && text.contains("(v4.0)"))
        assertTrue(text, text.contains("https://creativecommons.org/licenses/by-sa/4.0/"))
        assertTrue(text, text.contains("cropped and resized"))
    }

    @Test
    fun `public domain without a known creator omits the by clause`() {
        val text = AttributionFormatter.format(
            exportTestRecord("/x.jpg", LicenseType.PUBLIC_DOMAIN, creator = "")
        )
        assertFalse(text, text.contains(" by "))
        assertTrue(text, text.contains("Public Domain"))
    }

    @Test
    fun `seed wallpapers say they were generated on device and invent no url`() {
        val text = AttributionFormatter.format(
            exportTestRecord("/x.jpg", LicenseType.CC0, sourceId = "seed_reserve")
        )
        assertTrue(text, text.contains("Generated on-device by Sunloom"))
        assertFalse(text, text.contains("Source:"))
    }

    @Test
    fun `user provided files are labelled and carry no fabricated source or license`() {
        val text = AttributionFormatter.format(
            exportTestRecord("/x.jpg", LicenseType.USER_OWNED, sourceId = "user_local")
        )
        assertTrue(text, text.contains("user-provided file"))
        assertFalse(text, text.contains("Source:") || text.contains("License:"))
    }
}
