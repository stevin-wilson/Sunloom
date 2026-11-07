package com.sunloom.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps CONSTITUTION.md and the test suite honest with each other (Article XVI: automated verification).
 *
 * Sections listed in [tracedSections] must be referenced by at least one `@Requirement("<Article>.<n>")`
 * annotation somewhere under `src/test`. Every referenced id must also resolve to a real numbered item
 * of that article, so renumbering or deleting a requirement cannot leave a stale claim of coverage.
 * Extend [tracedSections] as further articles gain tests; do not weaken the check.
 */
class ConstitutionTraceabilityTest {

    /** Article sections whose coverage is mandatory. */
    private val tracedSections = listOf(
        "IV.1", "IV.3", "IV.5", "IV.6",
        "VII.3", "VII.4",
        "X.1", "X.3", "X.4",
        "XI.2",
        "XII.1", "XII.2", "XII.3", "XII.4",
        "XVIII.1", "XVIII.2", "XVIII.6"
    )

    private val constitution = File("../CONSTITUTION.md")
    private val testSources = File("src/test/java")

    /** Maps "XII" -> numbers of its numbered items, read from the `## Article XII:` block. */
    private fun constitutionSections(): Map<String, Set<Int>> {
        require(constitution.isFile) { "CONSTITUTION.md not found from ${File(".").absolutePath}" }
        val sections = linkedMapOf<String, MutableSet<Int>>()
        var current: String? = null
        val articleHeading = Regex("""^## Article ([IVXL]+):""")
        val numberedItem = Regex("""^(\d+)\. """)
        for (line in constitution.readLines()) {
            if (line.startsWith("## ")) {
                current = articleHeading.find(line)?.groupValues?.get(1)
                if (current != null) sections.getOrPut(current) { mutableSetOf() }
                continue
            }
            val article = current ?: continue
            numberedItem.find(line)?.let { sections.getValue(article) += it.groupValues[1].toInt() }
        }
        return sections
    }

    /** Maps each referenced id to the files that reference it. */
    private fun referencedIds(): Map<String, Set<String>> {
        require(testSources.isDirectory) { "test sources not found from ${File(".").absolutePath}" }
        val annotation = Regex("""@Requirement\(([^)]*)\)""")
        val quoted = Regex(""""([^"]+)"""")
        val refs = linkedMapOf<String, MutableSet<String>>()
        testSources.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "Requirement.kt" }.forEach { file ->
            // Comments and KDoc are not coverage: a commented-out test must not keep a section "traced".
            val text = file.readText().replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""//[^\n]*"""), "")
            for (match in annotation.findAll(text)) {
                for (id in quoted.findAll(match.groupValues[1])) {
                    refs.getOrPut(id.groupValues[1]) { mutableSetOf() } += file.name
                }
            }
        }
        return refs
    }

    @Test
    fun `constitution parser finds the articles it is expected to trace`() {
        val sections = constitutionSections()
        assertTrue("Article XII not parsed: ${sections.keys}", (sections["XII"]?.size ?: 0) >= 4)
        assertTrue("Article XVIII not parsed: ${sections.keys}", (sections["XVIII"]?.size ?: 0) >= 6)
    }

    @Test
    fun `every traced constitution section has at least one test`() {
        val refs = referencedIds()
        val untested = tracedSections.filter { refs[it].isNullOrEmpty() }
        assertTrue("Constitution sections without a @Requirement test: $untested", untested.isEmpty())
    }

    @Test
    fun `every referenced requirement id exists in the constitution`() {
        val sections = constitutionSections()
        val stale = referencedIds().filterKeys { id ->
            val (article, number) = id.substringBefore('.') to id.substringAfter('.', "").toIntOrNull()
            number == null || number !in (sections[article] ?: emptySet())
        }
        assertTrue("@Requirement ids that do not exist in CONSTITUTION.md: ${stale.mapValues { it.value }}", stale.isEmpty())
    }
}
