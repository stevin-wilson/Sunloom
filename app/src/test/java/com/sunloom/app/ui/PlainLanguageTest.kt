package com.sunloom.app.ui

import com.sunloom.app.Requirement
import com.sunloom.app.data.sources.SourceRegistry
import com.sunloom.app.domain.model.DisplayPolicy
import com.sunloom.app.domain.model.DisplayQualityTier
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.ScheduleMode
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Someone who installs Sunloom is not an engineer. The words on screen are the ones they would use: no "envelope",
 * "fails closed", "L2", "dHash", "Schedule A", "key-free" or "PPI floor". The engineering words stay in the technical
 * details of Diagnostics, where a bug report needs them (Constitution Article XII §1).
 */
@Requirement("XII.1")
class PlainLanguageTest {
    private val jargon = listOf(
        "envelope", "fails closed", "fail-closed", "\\bschedule a\\b", "key-free", "dhash", "perceptual", "blocklist",
        "denylist", "quarantine", "native floor", "retina floor", "mid-ppi", "wallpapermanager", "\\bl[1-5]\\b", "\\bppi\\b"
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    private fun offences(label: String, text: String) =
        jargon.filter { it.containsMatchIn(text) }.map { "$label: \"$text\" uses \"${it.pattern}\"" }

    private val main = File("src/main")

    /** Files whose literals the person reads. Diagnostics keeps the technical words, behind its own heading. */
    private val screenFiles = listOf(
        "ui/DisplaysAndSchedulePane.kt", "ui/SetupScreen.kt", "ui/SourcesAndCategoriesPane.kt", "ui/ReserveAndHistoryPane.kt",
        "ui/TodayCanvasPane.kt", "ui/SunloomScreens.kt", "ui/AboutSection.kt", "platform/GoogleBookPlatformAdapter.kt"
    )

    @Test
    fun `every string resource is in plain words`() {
        val xml = File(main, "res/values/strings.xml").readText()
        val bad = Regex(">([^<]+)<").findAll(xml).flatMap { offences("strings.xml", it.groupValues[1]).asSequence() }.toList()
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `one word for each thing the person can do`() {
        // Keep (not "favorite"), Dislike (not "exclude"), Remove (not "delete"), and "Reset" only for Reset Sunloom.
        val xml = File(main, "res/values/strings.xml").readText()
        val texts = Regex("<string name=\"([^\"]+)\"[^>]*>([^<]+)<").findAll(xml).map { it.groupValues[1] to it.groupValues[2] }.toList()
        val bad = texts.filter { (name, text) ->
            Regex("favorite|delete|exclude|system default", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
                (Regex("\\breset\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) &&
                    !name.startsWith("reset_sunloom") && name != "msg_reset_done" && name != "today_preview_description")
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `source, category, schedule and display labels are in plain words`() {
        val texts = SourceRegistry.admittedSources().flatMap { listOf(it.sourceName, it.plainDescription) } +
            SafeCategory.entries.flatMap { listOf(it.displayName, it.description) } +
            ScheduleMode.entries.flatMap { listOf(it.label, it.plainDescription) } +
            DisplayPolicy.entries.map { it.label } +
            DisplayQualityTier.entries.map { it.shortLabel }
        val bad = texts.flatMap { offences("label", it) }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `the literals on the main screens are in plain words`() {
        val literal = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
        val bad = screenFiles.flatMap { name ->
            File(main, "java/com/sunloom/app/$name").readLines()
                .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("import") }
                .flatMap { line -> literal.findAll(line).flatMap { offences(name, it.groupValues[1]).asSequence() }.toList() }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }
}
