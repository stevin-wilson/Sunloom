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
        "Rejected by user — permanently excluded from rotation"
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
                                if (src[i] == '"') { // nested literal inside the template
                                    val inner = literals(src.substring(i, minOf(src.length, i + 200)))
                                    out += inner
                                    i = src.indexOf('"', i + 1) + 1
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

    private fun looksLikeUiText(s: String): Boolean =
        s.length >= 8 && s.contains(' ') && s.any { it.isLetter() } && s !in allowed

    @Test
    fun `the view model and the gateway hold no sentence-like literals`() {
        val dir = File("src/main/java/com/sunloom/app/ui")
        val offenders = listOf("MainViewModel.kt", "AndroidSunloomGateway.kt").flatMap { name ->
            literals(File(dir, name).readText()).filter(::looksLikeUiText).map { "$name: \"$it\"" }
        }
        assertTrue("User-visible text must come from strings.xml:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
