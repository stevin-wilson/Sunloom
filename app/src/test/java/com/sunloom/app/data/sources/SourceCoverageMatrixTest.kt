package com.sunloom.app.data.sources

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.renderer.CropRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Article X §4: the sources polled for a display are those that can satisfy it. This guards the structure of that
 * coverage offline and deterministically (source x display x category); it says nothing about what the live
 * catalogs return today (see the opt-in LiveSourceCoverageProbe).
 *
 * Intended exclusions are listed explicitly so a change in who is left out of a display fails loudly instead of
 * silently narrowing the reserve.
 */
@Requirement("X.4")
class SourceCoverageMatrixTest {

    private val displays = CoverageDisplays.all

    private val defaultOnlineSources: Set<String> =
        UserPreferences().enabledSources.filter { id ->
            SourceRegistry.get(id)?.let { it.isAdmitted && !it.isBuiltInOffline } == true
        }.toSet()

    private val onlineCategories = SafeCategory.entries.filter { it != SafeCategory.USER_LOCAL }

    private fun compatibleSources(display: DisplayProfile, blur: Boolean): Set<String> =
        defaultOnlineSources.filter {
            CropRenderer.canSourceSatisfyDisplay(SourceRegistry.get(it)!!.maxRenditionLongEdgePx, display, blur)
        }.toSet()

    @Test
    fun `the default online sources are the ones this matrix expects`() {
        assertEquals(
            setOf("wikimedia_featured", "nasa_library", "artic_cc0", "met_museum_cc0", "cma_cc0", "smk_open"),
            defaultOnlineSources
        )
    }

    @Test
    fun `only the Art Institute is ever left out, and only on displays larger than its 1686 px renditions allow`() {
        val expectedExcluded = mapOf(
            "1366x768" to emptySet(),
            "1920x1080" to setOf("artic_cc0"),
            "1920x1200" to setOf("artic_cc0"),
            "2560x1440" to setOf("artic_cc0"),
            "2560x1600 hidpi" to emptySet(),
            "3440x1440" to setOf("artic_cc0"),
            "3840x2160 hidpi" to setOf("artic_cc0"),
            "5120x1440" to setOf("artic_cc0")
        )
        for (d in displays) {
            assertEquals(
                "sources left out for ${d.name}",
                expectedExcluded.getValue(d.name),
                defaultOnlineSources - compatibleSources(d, blur = false)
            )
        }
    }

    @Test
    fun `blurred frame fill brings every default source back on every display`() {
        for (d in displays) assertEquals(d.name, defaultOnlineSources, compatibleSources(d, blur = true))
    }

    @Test
    fun `every category keeps at least two usable sources on every display so one source being down is survivable`() {
        for (d in displays) {
            val usable = compatibleSources(d, blur = false)
            for (category in onlineCategories) {
                val serving = usable.filter { category in SourceRegistry.get(it)!!.servedCategories }
                assertTrue("${category.name} on ${d.name}: only $serving", serving.size >= 2)
            }
        }
    }

    @Test
    fun `every category has a source that can supply dark photography`() {
        for (category in onlineCategories) {
            val dark = defaultOnlineSources.filter {
                val reg = SourceRegistry.get(it)!!
                reg.supportsDarkPhotography && category in reg.servedCategories
            }
            assertTrue("no dark-capable source for ${category.name}", dark.isNotEmpty())
        }
    }

    @Test
    fun `every online source declares what it serves, only real categories, and offline sources declare none`() {
        for (id in defaultOnlineSources) {
            assertTrue("$id declares no categories", SourceRegistry.get(id)!!.servedCategories.isNotEmpty())
            assertTrue(SafeCategory.USER_LOCAL !in SourceRegistry.get(id)!!.servedCategories)
        }
        assertTrue(SourceRegistry.get("seed_reserve")!!.servedCategories.isEmpty())
        assertTrue(SourceRegistry.get("user_local")!!.servedCategories.isEmpty())
    }
}
