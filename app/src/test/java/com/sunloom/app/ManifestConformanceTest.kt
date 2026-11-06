package com.sunloom.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Checks the source manifest against the Constitution's platform obligations, so a casual edit cannot
 * lock the window size, add a dangerous permission or open a second window.
 */
class ManifestConformanceTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private val manifest: Element by lazy {
        val file = File("src/main/AndroidManifest.xml")
        require(file.isFile) { "manifest not found from ${File(".").absolutePath}" }
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file).documentElement
    }

    private fun Element.attr(name: String): String = getAttributeNS(androidNs, name)

    private fun mainActivity(): Element {
        val activities = manifest.getElementsByTagName("activity")
        for (i in 0 until activities.length) {
            val activity = activities.item(i) as Element
            if (activity.attr("name").endsWith("MainActivity")) return activity
        }
        error("MainActivity not declared in the manifest")
    }

    private fun dp(value: String): Int {
        assertTrue("expected a dp value but was '$value'", value.endsWith("dp"))
        return value.removeSuffix("dp").toInt()
    }

    @Test
    @Requirement("XII.4")
    fun `main activity is resizable and declares a free-form window size`() {
        val activity = mainActivity()
        assertEquals("true", activity.attr("resizeableActivity"))
        val layouts = activity.getElementsByTagName("layout")
        assertEquals("exactly one <layout> expected", 1, layouts.length)
        val layout = layouts.item(0) as Element
        val defaultW = dp(layout.attr("defaultWidth"))
        val defaultH = dp(layout.attr("defaultHeight"))
        val minW = dp(layout.attr("minWidth"))
        val minH = dp(layout.attr("minHeight"))
        assertTrue("minimum must not exceed default width", minW <= defaultW)
        assertTrue("minimum must not exceed default height", minH <= defaultH)
        assertTrue("minimum width must stay usable (>= 320dp)", minW >= 320)
    }

    @Test
    @Requirement("XII.4")
    fun `main activity does not lock orientation`() {
        assertEquals("", mainActivity().attr("screenOrientation"))
    }

    @Test
    @Requirement("XII.4")
    fun `touchscreen is optional so keyboard and mouse only devices can install`() {
        val features = manifest.getElementsByTagName("uses-feature")
        var touchscreenRequired: String? = null
        for (i in 0 until features.length) {
            val feature = features.item(i) as Element
            if (feature.attr("name") == "android.hardware.touchscreen") touchscreenRequired = feature.attr("required")
        }
        assertNotNull("touchscreen feature must be declared as not required", touchscreenRequired)
        assertEquals("false", touchscreenRequired)
    }

    @Test
    @Requirement("XVIII.6")
    fun `main activity is a single window`() {
        assertEquals("singleTask", mainActivity().attr("launchMode"))
    }

    @Test
    @Requirement("XVIII.2")
    fun `only the permitted install-time permissions are requested directly`() {
        val declared = mutableSetOf<String>()
        val nodes = manifest.getElementsByTagName("uses-permission")
        for (i in 0 until nodes.length) declared += (nodes.item(i) as Element).attr("name").substringAfterLast('.')
        // WAKE_LOCK and FOREGROUND_SERVICE are merged in by WorkManager; they must not be declared here.
        val allowed = setOf("INTERNET", "SET_WALLPAPER", "ACCESS_NETWORK_STATE", "RECEIVE_BOOT_COMPLETED")
        assertTrue("INTERNET and SET_WALLPAPER are required", declared.containsAll(setOf("INTERNET", "SET_WALLPAPER")))
        assertTrue("Permissions outside the Constitution allowlist: ${declared - allowed}", allowed.containsAll(declared))
    }

    @Test
    @Requirement("XVIII.2")
    fun `no clear-text traffic is allowed`() {
        val application = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals("false", application.attr("usesCleartextTraffic"))
        assertFalse(application.attr("networkSecurityConfig").isBlank())
    }
}
