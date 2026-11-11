package com.sunloom.app.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.sunloom.app.Requirement
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One design language: every Material colour role is chosen by Sunloom (so no default lavender leaks in), text on each
 * container is readable, and corners and gaps come from a few named sizes instead of numbers repeated by hand
 * (Constitution Article XII §1).
 */
@Requirement("XII.1")
class DesignSystemTest {
    /** Every colour role of a scheme, by name, read from its getters. */
    private fun roles(scheme: ColorScheme): Map<String, Color> =
        ColorScheme::class.java.methods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == java.lang.Long.TYPE }
            .associate { m ->
                m.name.removePrefix("get").substringBefore('-').replaceFirstChar { it.lowercase() } to
                    Color((m.invoke(scheme) as Long).toULong())
            }

    private val schemes = mapOf(
        "light" to Triple(SunloomLightPalette, lightColorScheme(), true),
        "dark" to Triple(SunloomDarkPalette, darkColorScheme(), false)
    )

    @Test
    fun `there are roles to check`() {
        assertTrue(roles(SunloomLightPalette).size >= 30)
    }

    @Test
    fun `every colour role is Sunloom's own, not a Material default`() {
        val unset = schemes.flatMap { (name, triple) ->
            val (ours, material, _) = triple
            val defaults = roles(material)
            roles(ours).filter { (role, color) -> defaults[role] == color && color != Color.White && color != Color.Black }.keys.map { role -> "$name.$role" }
        }
        assertTrue("still the Material default: $unset", unset.isEmpty())
    }

    private val pairs = listOf(
        "onPrimary" to "primary", "onSecondary" to "secondary", "onTertiary" to "tertiary", "onError" to "error",
        "onPrimaryContainer" to "primaryContainer", "onSecondaryContainer" to "secondaryContainer",
        "onTertiaryContainer" to "tertiaryContainer", "onErrorContainer" to "errorContainer",
        "onBackground" to "background", "onSurface" to "surface", "onSurfaceVariant" to "surfaceVariant",
        "inverseOnSurface" to "inverseSurface",
        "onSurface" to "surfaceContainerLowest", "onSurface" to "surfaceContainerLow", "onSurface" to "surfaceContainer",
        "onSurface" to "surfaceContainerHigh", "onSurface" to "surfaceContainerHighest",
        "onSurface" to "surfaceDim", "onSurface" to "surfaceBright",
        "onSurfaceVariant" to "surfaceContainer", "onSurfaceVariant" to "surfaceContainerHigh"
    )

    @Test
    fun `text on every container is readable`() {
        val weak = schemes.flatMap { (name, triple) ->
            val r = roles(triple.first)
            pairs.mapNotNull { (fg, bg) ->
                val ratio = contrastRatio(r.getValue(fg), r.getValue(bg))
                "$name $fg on $bg = ${"%.2f".format(ratio)}".takeIf { ratio < 4.5f }
            }
        }
        assertTrue(weak.joinToString("\n"), weak.isEmpty())
    }

    @Test
    fun `coloured text is readable on every surface it is drawn on`() {
        val texts = listOf("primary", "secondary", "tertiary", "error", "onSurfaceVariant")
        val grounds = listOf("background", "surface", "surfaceVariant", "surfaceContainer", "surfaceContainerHigh")
        val weak = schemes.flatMap { (name, triple) ->
            val r = roles(triple.first)
            texts.flatMap { fg ->
                grounds.mapNotNull { bg ->
                    val ratio = contrastRatio(r.getValue(fg), r.getValue(bg))
                    "$name $fg on $bg = ${"%.2f".format(ratio)}".takeIf { ratio < 4.5f }
                }
            }
        }
        assertTrue(weak.joinToString("\n"), weak.isEmpty())
    }

    @Test
    fun `borders and outlines can be seen against the surface`() {
        val weak = schemes.flatMap { (name, triple) ->
            val r = roles(triple.first)
            listOf("background", "surface", "surfaceContainer").mapNotNull { bg ->
                val ratio = contrastRatio(r.getValue("outline"), r.getValue(bg))
                "$name outline on $bg = ${"%.2f".format(ratio)}".takeIf { ratio < 3f }
            }
        }
        assertTrue(weak.joinToString("\n"), weak.isEmpty())
    }

    @Test
    fun `the surface containers step in the right direction`() {
        // Light themes get darker as they rise; dark themes get lighter.
        for ((name, triple) in schemes) {
            val r = roles(triple.first)
            val order = listOf("surfaceContainerLowest", "surfaceContainerLow", "surfaceContainer", "surfaceContainerHigh", "surfaceContainerHighest")
            val lum = order.map { r.getValue(it).luminance() }
            val sorted = if (triple.third) lum.sortedDescending() else lum.sorted()
            assertEquals("$name surface containers", sorted, lum)
        }
    }

    @Test
    fun `corner sizes come from the shape scale`() {
        val s = SunloomShapeScale
        assertTrue(s.small < s.medium && s.medium < s.large)
        val offenders = uiSources().filter { it.name != "SunloomTheme.kt" }
            .filter { Regex("RoundedCornerShape\\(").containsMatchIn(it.readText()) }.map { it.name }
        assertTrue("literal corner shapes in: $offenders", offenders.isEmpty())
    }

    @Test
    fun `gaps come from the spacing scale`() {
        val literal = Regex("(spacedBy|padding)\\([1-9]\\d*\\.dp\\)")
        val offenders = uiSources().flatMap { f ->
            f.readLines().withIndex().filter { literal.containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}" }
        }
        assertTrue("literal gaps (${offenders.size}): ${offenders.take(15)}", offenders.isEmpty())
    }

    private fun uiSources() = File("src/main/java/com/sunloom/app/ui").listFiles { f -> f.extension == "kt" }!!.toList()
}
