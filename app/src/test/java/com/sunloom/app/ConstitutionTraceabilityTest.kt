package com.sunloom.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps CONSTITUTION.md and the test suite honest with each other (Article XVI: automated verification).
 *
 * Sections listed in [tracedSections] must be referenced by at least one `@Requirement("<Article>.<n>")`
 * annotation somewhere under `src/test`. Every referenced id must also resolve to a real numbered item
 * of that article, so renumbering or deleting a requirement cannot leave a stale claim of coverage.
 * Extend [tracedSections] as further articles gain tests; do not weaken the check.
 *
 * A long section can be split into clauses: `**(a)**`, `**(b)**` ... inside the numbered item give the ids `XVI.1.a`,
 * `XVI.1.b` ... (the section id does not change). Every clause must be covered by a test that names it, by evidence in a
 * workflow file ([workflowEvidence]), or be listed in [unverifiable] with the reason. A test naming only the section does
 * not cover its clauses.
 *
 * Limits, on purpose: a clause tag on a test class says "this class proves part of the clause"; whether the classes
 * together prove all of it is a review judgement, not something this test can see. Workflow evidence checks that the
 * named lines are present in the file with comments removed, not that the workflow behaves as the clause says. A section
 * has at most 26 clauses (a to z), and a bold `**(x)**` inside a numbered item is always read as a clause marker.
 */
@Requirement("XVI.1.c")
class ConstitutionTraceabilityTest {

    /** Article sections whose coverage is mandatory. */
    private val tracedSections = listOf(
        "IV.1", "IV.3", "IV.5", "IV.6",
        "VII.3", "VII.4", "VII.11",
        "X.1", "X.3", "X.4",
        "XI.2",
        "XII.1", "XII.2", "XII.3", "XII.4",
        "XVIII.1", "XVIII.2", "XVIII.6"
    )

    private val constitution = File("../CONSTITUTION.md")
    /** Every `src/test*` source set: the shared tests and the per-flavor ones (the Play flavor's `NoUpdateCodeTest` lives under `testPlay`). */
    private val testSources = File("src").listFiles { dir -> dir.isDirectory && dir.name.startsWith("test") }.orEmpty().toList()

    /**
     * Clauses proved by what a workflow file says rather than by a JVM test. Each marker must still appear in the file,
     * so editing the workflow without updating the Constitution (or the other way round) fails here.
     */
    private val workflowEvidence: Map<String, Pair<String, List<String>>> = mapOf(
        "XVI.1.d" to (
            "../.github/workflows/ci.yml" to listOf(
                "push:",
                "pull_request:",
                "testGithubDebugUnitTest testPlayDebugUnitTest lintGithubDebug lintPlayDebug detekt",
                ":app:minifyGithubReleaseWithR8 :app:minifyPlayReleaseWithR8"
            )
            ),
        "XVI.1.f" to ("../.github/workflows/emulator.yml" to listOf("pull_request:", "tags: [\"v*\"]", "workflow_dispatch:", "arch: x86_64", "api-level: [30, 36]")),
        "XVI.1.g" to ("../.github/workflows/ci.yml" to listOf(":app:coverageFloors")),
        "XVI.1.k" to ("../.github/workflows/ci.yml" to listOf("gitleaks/gitleaks-action", "fetch-depth: 0"))
    )

    /** Clauses nothing in the repository can check, with the reason. Keep this list short; a clause leaves it when it can be checked. */
    private val unverifiable: Map<String, String> = mapOf(
        "XVI.1.l" to "main's branch protection is a GitHub repository setting; it cannot be read from a checkout"
    )

    /** What the parser reads from CONSTITUTION.md: the numbered items of each article and the clauses inside them, in order. */
    private class Parsed(val sections: Map<String, Set<Int>>, val clauses: List<String>)

    /** Reads the `## Article XII:` blocks. A clause marker belongs to the numbered item it appears in (or after, on a following line). */
    private fun parseConstitution(lines: List<String>): Parsed {
        val sections = linkedMapOf<String, MutableSet<Int>>()
        val clauses = mutableListOf<String>()
        val articleHeading = Regex("""^## Article ([IVXL]+):""")
        val numberedItem = Regex("""^(\d+)\. """)
        val clauseMarker = Regex("""\*\*\(([a-z])\)\*\*""")
        var article: String? = null
        var item: Int? = null
        for (line in lines) {
            if (line.startsWith("## ")) {
                article = articleHeading.find(line)?.groupValues?.get(1)
                item = null
                if (article != null) sections.getOrPut(article) { mutableSetOf() }
                continue
            }
            val current = article ?: continue
            val start = numberedItem.find(line)
            if (start != null) {
                item = start.groupValues[1].toInt()
                sections.getValue(current) += item
            }
            val number = item ?: continue
            clauseMarker.findAll(line).forEach { clauses += "$current.$number.${it.groupValues[1]}" }
        }
        return Parsed(sections, clauses)
    }

    private fun constitution(): Parsed {
        require(constitution.isFile) { "CONSTITUTION.md not found from ${File(".").absolutePath}" }
        return parseConstitution(constitution.readLines())
    }

    /** Clause letters inside one section must run a, b, c ... with no gap or repeat, so a deleted clause shows up in review. */
    private fun clausesOutOfOrder(clauses: List<String>): List<String> =
        clauses.groupBy { it.substringBeforeLast('.') }.filter { (_, ids) ->
            ids.map { it.substringAfterLast('.') } != ('a'..'z').take(ids.size).map { it.toString() }
        }.keys.toList()

    /** A section id (`XII.4`) or a clause id (`XVI.1.j`) that exists in [parsed]. */
    private fun exists(id: String, parsed: Parsed): Boolean {
        val parts = id.split('.')
        return when (parts.size) {
            2 -> parts[1].toIntOrNull()?.let { it in parsed.sections[parts[0]].orEmpty() } == true
            3 -> id in parsed.clauses
            else -> false
        }
    }

    /** Maps each referenced id to the files that reference it. */
    private fun referencedIds(): Map<String, Set<String>> {
        require(testSources.isNotEmpty()) { "test sources not found from ${File(".").absolutePath}" }
        val annotation = Regex("""@Requirement\(([^)]*)\)""")
        val quoted = Regex(""""([^"]+)"""")
        val refs = linkedMapOf<String, MutableSet<String>>()
        testSources.asSequence().flatMap { it.walkTopDown() }.filter { it.isFile && it.extension == "kt" && it.name != "Requirement.kt" }.forEach { file ->
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
        val sections = constitution().sections
        assertTrue("Article XII not parsed: ${sections.keys}", (sections["XII"]?.size ?: 0) >= 4)
        assertTrue("Article XVIII not parsed: ${sections.keys}", (sections["XVIII"]?.size ?: 0) >= 6)
    }

    @Test
    fun `clause markers inside a numbered item become clause ids and version tags do not`() {
        val parsed = parseConstitution(
            listOf(
                "## Article IX: Example **[v0.4]**",
                "",
                "1. **Rule:** **(a)** first part **[v0.28]**. **(b)** second part.",
                "2. **Other:** no clause on this line **[v0.5]**",
                "   a continued line **(a)** still belongs to item two",
                "## Schedule A: Not an article",
                "3. **(a)** outside every article"
            )
        )
        assertEquals(listOf("IX.1.a", "IX.1.b", "IX.2.a"), parsed.clauses)
        assertEquals(setOf(1, 2), parsed.sections["IX"])
    }

    @Test
    fun `a gap or a repeat in the clause letters is found`() {
        assertTrue(clausesOutOfOrder(listOf("IX.1.a", "IX.1.b", "IX.2.a")).isEmpty())
        assertEquals(listOf("IX.1"), clausesOutOfOrder(listOf("IX.1.a", "IX.1.c")))
        assertEquals(listOf("IX.1"), clausesOutOfOrder(listOf("IX.1.a", "IX.1.a")))
    }

    @Test
    fun `an id is known only if the section or clause is really there`() {
        val parsed = parseConstitution(listOf("## Article IX: Example", "1. **(a)** one **(b)** two", "2. plain"))
        assertTrue(exists("IX.1", parsed) && exists("IX.2", parsed) && exists("IX.1.b", parsed))
        assertFalse(exists("IX.3", parsed))
        assertFalse(exists("IX.2.a", parsed))
        assertFalse(exists("IX.1.c", parsed))
        assertFalse(exists("IX", parsed))
    }

    @Test
    fun `the testing section is split into clauses so one test cannot stand for all of it`() {
        val clauses = constitution().clauses.filter { it.startsWith("XVI.1.") }
        assertTrue("XVI.1 has no clauses: ${constitution().clauses}", clauses.size >= 10)
    }

    @Test
    fun `clause letters run in order`() {
        val broken = clausesOutOfOrder(constitution().clauses)
        assertTrue("sections whose clause letters skip or repeat: $broken", broken.isEmpty())
    }

    @Test
    fun `every clause is covered by a test or by named evidence`() {
        val refs = referencedIds()
        val uncovered = constitution().clauses.filter { refs[it].isNullOrEmpty() && it !in workflowEvidence && it !in unverifiable }
        assertTrue("Constitution clauses with no @Requirement test, workflow evidence or stated reason: $uncovered", uncovered.isEmpty())
    }

    @Test
    fun `the evidence named for clauses is still there`() {
        val clauses = constitution().clauses.toSet()
        val stale = (workflowEvidence.keys + unverifiable.keys).filterNot { it in clauses }
        assertTrue("evidence listed for clauses that no longer exist: $stale", stale.isEmpty())
        assertTrue("a clause is both evidenced and unverifiable", workflowEvidence.keys.intersect(unverifiable.keys).isEmpty())
        val missing = workflowEvidence.flatMap { (clause, evidence) ->
            val (path, markers) = evidence
            val file = File(path)
            // Comments are not evidence: a commented-out trigger or step must not keep a clause "proved".
            val text = if (file.isFile) file.readLines().map { it.substringBefore(" #") }.filterNot { it.trimStart().startsWith("#") }.joinToString("\n") else ""
            markers.filterNot { text.contains(it) }.map { "$clause: '$it' not in $path" }
        }
        assertTrue("workflow evidence that is no longer in the file: $missing", missing.isEmpty())
    }

    @Test
    fun `every traced constitution section has at least one test`() {
        val refs = referencedIds()
        val untested = tracedSections.filter { refs[it].isNullOrEmpty() }
        assertTrue("Constitution sections without a @Requirement test: $untested", untested.isEmpty())
    }

    @Test
    fun `every referenced requirement id exists in the constitution`() {
        val parsed = constitution()
        val stale = referencedIds().filterKeys { !exists(it, parsed) }
        assertTrue("@Requirement ids that do not exist in CONSTITUTION.md: ${stale.mapValues { it.value }}", stale.isEmpty())
    }
}
