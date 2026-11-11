package com.sunloom.app.ui

import com.sunloom.app.Requirement
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Status messages are shown to the user, so they live in `strings.xml` where they can be translated, not in the
 * ViewModel or the gateway. This scan fails when a sentence-like literal appears in those two files.
 */
@Requirement("XII.1")
class HardcodedMessageScanTest {

    /** Literals that are data, not UI text: a stored default label and a stored audit reason. */
    private val allowed = setOf(
        "Custom Coordinates",
        "Rejected by user — permanently excluded from rotation",
        // Stored, not shown: the default city is matched by name against the city list.
        "San Francisco, United States",
        // Stored, not shown: the name of a city typed as coordinates (the scanner drops the nested format calls).
        "Custom (\${.format(lat)}, \${.format(lon)})"
    )

    /** Reads string literals out of Kotlin source, skipping comments and the inside of `${...}` templates. */
    private fun literals(src: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                src.startsWith("//", i) -> i = src.indexOf('\n', i).let { if (it < 0) src.length else it }
                src.startsWith("/*", i) -> i = src.indexOf("*/", i).let { if (it < 0) src.length else it + 2 }
                c == '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < src.length && src[i] != '"') {
                        if (src[i] == '\\') { sb.append(src[i]).append(src[i + 1]); i += 2; continue }
                        if (src.startsWith("\${", i)) {
                            var depth = 1
                            sb.append("\${")
                            i += 2
                            while (i < src.length && depth > 0) {
                                if (src[i] == '{') depth++ else if (src[i] == '}') depth--
                                if (src[i] == '"') { // a nested literal inside the template: format specifiers, not UI text
                                    var j = i + 1
                                    while (j < src.length && src[j] != '"') j += if (src[j] == '\\') 2 else 1
                                    i = j + 1
                                    continue
                                }
                                sb.append(src[i]); i++
                            }
                            continue
                        }
                        sb.append(src[i]); i++
                    }
                    i++
                    out += sb.toString()
                }
                else -> i++
            }
        }
        return out
    }

    /** What the person would read: the literal with its `${...}` and `$name` parts taken out. */
    private fun visibleText(s: String): String =
        s.replace(Regex("\\$\\{[^}]*}"), "").replace(Regex("\\$[A-Za-z_][A-Za-z0-9_]*"), "")

    private fun looksLikeUiText(s: String): Boolean {
        val text = visibleText(s)
        return text.length >= 8 && text.contains(' ') && text.any { it.isLetter() } && s !in allowed
    }

    private val base = File("src/main/java/com/sunloom/app")

    /**
     * The files whose literals the person reads. Left out on purpose: `UiStrings.kt` (its English object mirrors
     * strings.xml and `UiStringsTest` keeps them equal), and the domain model and source registry, whose English
     * names are identifiers and records. The screens read their labels through `labels/Labels.kt`, which the next
     * test enforces.
     */
    private fun screenFiles(): List<File> =
        File(base, "ui").listFiles { f -> f.extension == "kt" && f.name != "UiStrings.kt" }!!.toList()

    @Test
    fun `no screen holds a sentence-like literal`() {
        val offenders = screenFiles().flatMap { f ->
            literals(f.readText()).filter(::looksLikeUiText).map { "${f.name}: \"$it\"" }
        }
        assertTrue(
            "User-visible text must come from strings.xml (${offenders.size}):\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun `screens read enum and source labels through the labels file, not from the domain model`() {
        val direct = Regex("\\.(displayName|plainDescription|shortLabel)\\b|\\b(reg|registration)\\.sourceName")
        val enumLabel = Regex("\\b(mode|policy|status|tab|cat|healthState|brightnessClass|licenseType|scheduleMode)\\.label\\b")
        val offenders = screenFiles().flatMap { f ->
            f.readLines().withIndex()
                .filter { (_, line) -> !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*") }
                .filter { (_, line) -> direct.containsMatchIn(line) || enumLabel.containsMatchIn(line) }
                .map { "${f.name}:${it.index + 1}: ${it.value.trim()}" }
        }
        assertTrue("read labels through labels/Labels.kt (${offenders.size}):\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
