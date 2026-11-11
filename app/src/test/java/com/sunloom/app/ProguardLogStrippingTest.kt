package com.sunloom.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Release builds drop Log.v/d/i calls (Article VIII §2): debug chatter must never reach a person's logcat. Warnings
 * and errors stay, because they are what a bug report needs.
 */
@Requirement("VIII.2")
class ProguardLogStrippingTest {
    private val rules = File("proguard-rules.pro").readText()

    @Test
    fun `verbose, debug and info logging is stripped from release builds`() {
        assertTrue(rules.contains("-assumenosideeffects class android.util.Log"))
        for (level in listOf("v", "d", "i")) {
            assertTrue("Log.$level must be stripped", Regex("""public static \S+ $level\(""").containsMatchIn(rules))
        }
    }

    @Test
    fun `warnings and errors are kept`() {
        val block = rules.substringAfter("-assumenosideeffects class android.util.Log").substringBefore("}")
        assertTrue(!Regex("""public static \S+ [we]\(""").containsMatchIn(block))
    }
}
