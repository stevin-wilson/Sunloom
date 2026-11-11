package com.sunloom.app.update

import org.junit.Assert.assertTrue
import org.junit.Test

/** The play flavor must not contain any update-checker code or GitHub host list (Article XVIII §1). */
class NoUpdateCodeTest {
    @Test
    fun updateHostsClassIsAbsent() {
        val present = runCatching { Class.forName("com.sunloom.app.update.UpdateHosts") }.isSuccess
        assertTrue("play flavor must not ship com.sunloom.app.update.UpdateHosts", !present)
    }

    @Test
    fun noGithubHostLiteralInMainSources() {
        val root = java.io.File("src/main/java")
        val offenders = root.walkTopDown().filter { it.extension == "kt" && it.readText().contains("api.github.com") }.toList()
        assertTrue("main sources must not mention api.github.com: $offenders", offenders.isEmpty())
    }
}
