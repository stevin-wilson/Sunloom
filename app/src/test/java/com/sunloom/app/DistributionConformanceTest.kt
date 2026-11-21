package com.sunloom.app

import com.sunloom.app.domain.model.DisplayPolicy
import com.sunloom.app.domain.model.UserPreferences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules about what ships and what the code may touch (Article XVIII §1 and Article XII §1) that no behaviour test can see,
 * because they are about the build and the source: the two architectures and the one application id and signing key,
 * an update check that never downloads or installs, only public Android APIs, and a dry-run mode the person never meets.
 * Each reads the files, so each fails the day someone adds the thing the rule forbids.
 *
 * These are static checks by name: they catch the ordinary ways to break a rule, not every possible route (an app could be
 * fetched with plain HTTP and opened with a view intent without naming any banned class), so review still matters.
 * Comments are ignored, so a note saying "we never use PackageInstaller" does not trip them.
 */
class DistributionConformanceTest {

    private val module = File(".").canonicalFile // the Gradle module directory (app/) when run by Gradle

    private fun file(path: String) = File(module, path).also { assertTrue("$path is missing (run from the app module)", it.exists()) }

    // A string literal or a comment: strings are matched first so a `//` inside "https://..." is not mistaken for a comment.
    private val tokens = Regex("""/\*[\s\S]*?\*/|//[^\n]*|"(?:[^"\\\n]|\\.)*"""")

    private fun spaces(text: String) = text.map { if (it == '\n') '\n' else ' ' }.joinToString("")

    /** [text] with every comment turned into spaces (same length, same line breaks), strings left as they are. */
    private fun withoutComments(text: String) = tokens.replace(text) { if (it.value.startsWith("\"")) it.value else spaces(it.value) }

    /** [text] with comments and the inside of every string turned into spaces, so only the code's own braces are left. */
    private fun skeleton(text: String) = tokens.replace(text) { spaces(it.value) }

    /** The comment-free text between the braces that follow [header] (found in code, not in a comment or a string). */
    private fun block(text: String, header: String): String {
        val code = withoutComments(text)
        val bones = skeleton(text)
        val start = bones.indexOf(header)
        assertTrue("`$header` not found", start >= 0)
        val open = bones.indexOf('{', start)
        var depth = 0
        for (i in open until bones.length) {
            if (bones[i] == '{') depth++
            if (bones[i] == '}') depth--
            if (depth == 0) return code.substring(open + 1, i)
        }
        error("unbalanced braces after `$header`")
    }

    /** Every Kotlin file under `src/<root>/java` or `src/<root>/kotlin`, for each of [roots] (e.g. "main", "github"). */
    private fun kotlinFiles(vararg roots: String, below: String = ""): List<File> =
        roots.flatMap { root -> listOf("java", "kotlin").map { File(module, "src/$root/$it/$below") } }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    private fun codeOf(f: File) = withoutComments(f.readText())

    private val gradle get() = file("build.gradle.kts").readText()

    // --- Article XVIII §1(a): the target and the two flavors ---

    @Requirement("XVIII.1.a")
    @Test
    fun `the package holds exactly the two Googlebook architectures`() {
        val abis = Regex("""abiFilters\s*\+=\s*listOf\(([^)]*)\)""").find(withoutComments(gradle))?.groupValues?.get(1).orEmpty()
        assertEquals(setOf("arm64-v8a", "x86_64"), Regex("\"([^\"]+)\"").findAll(abis).map { it.groupValues[1] }.toSet())
    }

    @Requirement("XVIII.1.a")
    @Test
    fun `the github and play flavors share one application id`() {
        val code = withoutComments(gradle)
        val flavors = block(gradle, "productFlavors")
        assertEquals(setOf("github", "play"), Regex("""create\("(\w+)"\)""").findAll(flavors).map { it.groupValues[1] }.toSet())
        listOf("applicationId", "applicationIdSuffix", "versionNameSuffix").forEach {
            assertFalse("a flavor sets $it, so the two builds would differ", flavors.contains(it))
        }
        assertEquals("one application id, in defaultConfig", 1, Regex("""\bapplicationId\s*=""").findAll(code).count())
        assertTrue(block(gradle, "defaultConfig").contains("""applicationId = "com.sunloom.app""""))
    }

    @Requirement("XVIII.1.a")
    @Test
    fun `everything that is signed is signed with the one release key`() {
        val code = withoutComments(gradle)
        assertFalse("a flavor chooses its own signing key", block(gradle, "productFlavors").contains("signingConfig"))
        val defined = Regex("""create\("(\w+)"\)""").findAll(block(gradle, "signingConfigs")).map { it.groupValues[1] }.toSet()
        assertEquals("one signing configuration is defined", setOf("release"), defined)
        val used = Regex("""signingConfig\s*=\s*signingConfigs(?:\.getByName\("(\w+)"\)|\["(\w+)"\])""").findAll(code)
            .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toSet()
        assertEquals("every build that is signed uses it", setOf("release"), used)
    }

    // --- Article XVIII §1(d): the update check never downloads or installs ---

    @Requirement("XVIII.1.d")
    @Test
    fun `nothing in either flavor can download or install an app`() {
        val sources = kotlinFiles("main", "github", "play")
        assertTrue("the update checker should be among the sources scanned", sources.any { it.name == "GithubUpdateChecker.kt" })
        val banned = listOf(
            "PackageInstaller", "DownloadManager", "REQUEST_INSTALL_PACKAGES", "vnd.android.package-archive",
            "ACTION_INSTALL_PACKAGE", "INSTALL_PACKAGE", "ACTION_VIEW_DOWNLOADS"
        )
        val found = sources.flatMap { f -> codeOf(f).let { code -> banned.filter { code.contains(it) }.map { "${f.name}: $it" } } }
        assertTrue("code that can download or install an app: $found", found.isEmpty())
        val manifests = listOf("main", "github", "play").map { File(module, "src/$it/AndroidManifest.xml") }.filter { it.isFile }
        assertTrue(manifests.isNotEmpty())
        manifests.forEach { assertFalse("${it.parentFile.name} manifest asks to install packages", it.readText().contains("REQUEST_INSTALL_PACKAGES")) }
    }

    // --- Article XVIII §1(g): public, documented platform APIs only ---

    @Requirement("XVIII.1.g")
    @Test
    fun `the platform adapter uses no reflection, hidden API or system service lookup`() {
        val platform = kotlinFiles("main", below = "com/sunloom/app/platform")
        assertTrue("the adapter should be among the sources scanned", platform.any { it.name == "GoogleBookPlatformAdapter.kt" })
        assertTrue("the adapter should use WallpaperManager", platform.any { codeOf(it).contains("WallpaperManager") })
        val banned = listOf(
            "Class.forName", "getDeclaredMethod", "getDeclaredField", "setAccessible", "java.lang.reflect", "kotlin.reflect",
            "HiddenApi", "dalvik.system", "ServiceManager", "IWallpaperManager"
        )
        val found = platform.flatMap { f -> codeOf(f).let { code -> banned.filter { code.contains(it) }.map { "${f.name}: $it" } } }
        assertTrue("non-public platform access: $found", found.isEmpty())
    }

    // --- Article XVIII §1(h): one shared surface, one default policy, and the unsupported choice says so ---

    @Requirement("XVIII.1.h")
    @Test
    fun `the default policy is one wallpaper for all displays, with the sharpest-display fallback offered`() {
        assertEquals(DisplayPolicy.SAME_ON_ALL_DISPLAYS, UserPreferences().displayPolicy)
        assertTrue(DisplayPolicy.OPTIMIZE_PRIMARY_SHARPEST in DisplayPolicy.values())
    }

    @Requirement("XVIII.1.h")
    @Test
    fun `a separate wallpaper per display is shown to the person as unavailable and why`() {
        assertTrue(DisplayPolicy.INDEPENDENT_PER_DISPLAY.label.contains("Unavailable"))
        val strings = file("src/main/res/values/strings.xml").readText()
        val shown = Regex("""<string name="policy_name_independent_per_display">([^<]*)</string>""").find(strings)?.groupValues?.get(1).orEmpty()
        assertTrue("the screen text is: $shown", shown.contains("Unavailable") && shown.contains("one system wallpaper surface"))
    }

    // --- Article XII §1(c): dry-run is internal ---

    @Requirement("XII.1.c")
    @Test
    fun `the person is never shown, or able to set, dry-run mode`() {
        val strings = file("src/main/res/values/strings.xml").readText()
        assertFalse("a string mentions dry-run", Regex("dry[ -]?run", RegexOption.IGNORE_CASE).containsMatchIn(strings))
        val ui = kotlinFiles("main", below = "com/sunloom/app/ui")
        assertTrue("the main activity should be among the sources scanned", ui.any { it.name == "MainActivity.kt" })
        // The screens may only switch it off, or pass the saved value along unchanged; any other mention could be a way to turn it on.
        val allowed = Regex("""dryRunPreviewOnly\s*=\s*false|dryRunPreviewOnly\s*=\s*prefs\.dryRunPreviewOnly|\(prefs\.dryRunPreviewOnly\)""")
        val other = ui.flatMap { f ->
            codeOf(f).lines().filter { it.contains("dryRun", ignoreCase = true) && !allowed.containsMatchIn(it) }.map { "${f.name}: ${it.trim()}" }
        }
        assertTrue("a screen does something other than switch dry-run off or pass it along: $other", other.isEmpty())
    }
}
