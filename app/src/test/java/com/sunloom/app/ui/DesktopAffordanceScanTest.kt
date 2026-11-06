package com.sunloom.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Desktop guidelines (Drawable_Focus, Hover_States, Custom_Cursors) need every interactive control to
 * show a keyboard focus ring and a hand cursor. This source scan fails when a new bare Material control
 * ships without `desktopControl()`, so the guarantee does not rely on reviewers remembering it.
 */
class DesktopAffordanceScanTest {

    private val controlCall = Regex(
        """(?<![\w.])(?:androidx\.compose\.material3\.)?(Switch|FilterChip|Button|OutlinedButton|TextButton|IconButton|OutlinedIconButton|FilledTonalButton|Checkbox|RadioButton)\("""
    )

    /** Controls that intentionally rely on their container for affordances. Keep this list empty if possible. */
    private val allowlist = emptySet<String>()

    private fun uiSources(): List<File> {
        val dir = File("src/main/java/com/sunloom/app/ui")
        require(dir.isDirectory) { "ui source dir not found from ${File(".").absolutePath}" }
        return dir.listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }
    }

    private fun closingParen(src: String, open: Int): Int {
        var depth = 0
        var inString = false
        var i = open
        while (i < src.length) {
            val c = src[i]
            when {
                inString -> if (c == '\\') i++ else if (c == '"') inString = false
                c == '"' -> inString = true
                c == '(' -> depth++
                c == ')' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return -1
    }

    @Test
    fun `every interactive material control declares desktop affordances`() {
        val offenders = mutableListOf<String>()
        var scanned = 0
        for (file in uiSources()) {
            val src = file.readText()
            for (m in controlCall.findAll(src)) {
                val open = m.range.last
                val close = closingParen(src, open)
                if (close < 0) continue
                val args = src.substring(open + 1, close)
                scanned++
                if ("desktopControl" in args) continue
                val line = src.substring(0, m.range.first).count { it == '\n' } + 1
                val id = "${file.name}:$line ${m.groupValues[1]}"
                if (id !in allowlist) offenders += id
            }
        }
        assertTrue("scan matched suspiciously few controls ($scanned)", scanned >= 25)
        assertTrue(
            "Controls without desktopControl() (focus ring + hand cursor): $offenders",
            offenders.isEmpty()
        )
    }
}
