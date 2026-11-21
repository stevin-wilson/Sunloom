package com.sunloom.app.data.sources

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.SafeCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Article IX §1: the bundled seeds are what a person sees with no network, so there are enough of each look, and every
 * category adopted since v0.8 has its own two Light and two Dark. The four categories that predate that rule are an open
 * gap named in the Constitution: they may gain seeds but never lose them, and a new category may not join them (a category
 * that is not a place pictures come from, such as imported files, has no seeds).
 */
class SeedCoverageTest {

    private val seeds = BundledSeedSourceAdapter.SEED_SPECS

    private fun count(category: SafeCategory, look: BrightnessClass) = seeds.count { it.category == category && it.targetBrightness == look }

    /** The categories that predate the two-and-two rule (Article IX §1 names them as an open gap), with what they have now. */
    private val openGap = mapOf(
        SafeCategory.ARCHITECTURE_CITYSCAPES to (1 to 0),
        SafeCategory.SPACE_ASTRONOMY to (0 to 1),
        SafeCategory.SCENIC_FINE_ART to (0 to 0),
        SafeCategory.ABSTRACT_PATTERNS to (0 to 0)
    )

    @Requirement("IX.1.a")
    @Test
    fun `at least three Light and three Dark seeds are bundled, each with its own id`() {
        assertTrue("Light seeds", seeds.count { it.targetBrightness == BrightnessClass.LIGHT } >= 3)
        assertTrue("Dark seeds", seeds.count { it.targetBrightness == BrightnessClass.DARK } >= 3)
        assertEquals("seed ids are unique", seeds.size, seeds.map { it.id }.toSet().size)
    }

    @Requirement("IX.1.b")
    @Test
    fun `every category outside the open gap has two Light and two Dark seeds`() {
        SafeCategory.entries.filter { it !in openGap && it != SafeCategory.USER_LOCAL }.forEach { category ->
            assertTrue("$category Light", count(category, BrightnessClass.LIGHT) >= 2)
            assertTrue("$category Dark", count(category, BrightnessClass.DARK) >= 2)
        }
    }

    @Requirement("IX.1.b")
    @Test
    fun `the categories in the open gap never lose seeds`() {
        openGap.forEach { (category, have) ->
            assertTrue("$category Light fell below ${have.first}", count(category, BrightnessClass.LIGHT) >= have.first)
            assertTrue("$category Dark fell below ${have.second}", count(category, BrightnessClass.DARK) >= have.second)
        }
    }
}
