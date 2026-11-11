package com.sunloom.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Article VI / XVIII: what Sunloom is built from is credited where people can read it. The build strips the licence files
 * out of the dependencies it packages, so the credit is a curated file, and this test fails when a dependency is added
 * without one.
 */
@Requirement("XVIII.2")
class OpenSourceLicensesTest {
    private val credits = File("src/main/res/raw/open_source_licenses.txt")
    private val catalog = File("../gradle/libs.versions.toml")
    private val buildScript = File("build.gradle.kts")

    /** `androidx.core:core-ktx` for every `implementation(libs.x)` (not debug, test or androidTest). */
    private fun runtimeCoordinates(): List<String> {
        val modules = mutableMapOf<String, String>()
        for (line in catalog.readLines()) {
            val alias = line.substringBefore('=').trim()
            if (alias.isEmpty() || alias.startsWith("[") || alias.startsWith("#")) continue
            val module = Regex("""module\s*=\s*"([^"]+)"""").find(line)?.groupValues?.get(1)
            val group = Regex("""group\s*=\s*"([^"]+)"""").find(line)?.groupValues?.get(1)
            val name = Regex("""name\s*=\s*"([^"]+)"""").find(line)?.groupValues?.get(1)
            modules[alias.replace('-', '.')] = module ?: if (group != null && name != null) "$group:$name" else continue
        }
        return Regex("""^\s*implementation\(libs\.([\w.]+)\)""", RegexOption.MULTILINE)
            .findAll(buildScript.readText())
            .map { it.groupValues[1] }
            .map { alias -> modules[alias] ?: error("dependency libs.$alias is not in the version catalog") }
            .toList()
    }

    @Test
    fun `the build really lists runtime dependencies to check`() {
        assertTrue("found ${runtimeCoordinates().size}", runtimeCoordinates().size >= 10)
    }

    @Test
    fun `every runtime dependency is credited`() {
        val text = credits.readText()
        val missing = runtimeCoordinates().filter { it !in text }

        assertEquals("credit these in open_source_licenses.txt: $missing", emptyList<String>(), missing)
    }

    @Test
    fun `the Kotlin runtime and the icon artwork are credited too`() {
        val text = credits.readText()

        assertTrue("Kotlin standard library", "org.jetbrains.kotlin:kotlin-stdlib" in text)
        assertTrue("SunloomIcons paths are copied from Material Icons", "Material Icons" in text)
    }

    @Test
    fun `Sunloom states its own licence and the Apache licence text is included`() {
        val text = credits.readText()

        assertTrue("GPL-3.0-or-later" in text)
        assertTrue("Apache License" in text && "Version 2.0, January 2004" in text)
    }

    @Test
    fun `the project carries its licence and the notice for borrowed artwork`() {
        val licence = File("../LICENSE")
        val notice = File("../NOTICE")

        assertTrue("LICENSE exists", licence.isFile)
        assertTrue(licence.readText().contains("GNU GENERAL PUBLIC LICENSE") && licence.readText().contains("Version 3, 29 June 2007"))
        assertTrue("NOTICE exists", notice.isFile)
        assertTrue(notice.readText().contains("Material Icons") && notice.readText().contains("Apache"))
    }
}
