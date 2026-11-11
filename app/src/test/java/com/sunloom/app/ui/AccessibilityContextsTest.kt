package com.sunloom.app.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The accessibility rules (a name on every control, a 48 dp hit area) held on the main tabs in the light theme at
 * normal size. They hold in the other places people use Sunloom too: the dark theme, first-run setup, dialogs, and a
 * narrow window with the system font at 200 percent (Constitution Article XII §4).
 */
@Requirement("XII.4")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h4000dp")
class AccessibilityContextsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var holder: MutableState<SunloomUiState>

    private fun seeded(setupDone: Boolean = true): SunloomUiState {
        context.getSharedPreferences("sunloom_ui", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val loaded = runBlocking { AndroidSunloomGateway(context).loadInitialSnapshot() }
        return loaded.copy(preferences = loaded.preferences.copy(isSetupCompleted = setupDone))
    }

    private fun show(state: SunloomUiState, dark: Boolean = false, fontScale: Float? = null) {
        holder = mutableStateOf(state)
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
                SunloomApp(
                    uiState = holder.value,
                    onCompleteSetup = {}, onSelectTab = { holder.value = holder.value.copy(activeTab = it) },
                    onNextWallpaper = {}, onPreviousWallpaper = {}, onToggleKeep = {}, onRejectForever = {},
                    onTogglePause = {}, onRestoreOriginal = {}, onApplySpecific = {}, onToggleLegibilityOverlay = {},
                    onSyncOnline = {}, onToggleCategory = {}, onToggleSource = {}, onSelectCity = {},
                    onUpdateScheduleMode = {}, onUpdateLockPolicy = {}, onToggleBlurredFill = {}, onToggleWifiOnly = {},
                    onPickLocalPhoto = {}, darkTheme = dark
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun operable(): List<SemanticsNode> =
        composeTestRule.onAllNodes(hasClickAction() or isToggleable()).fetchSemanticsNodes()

    private fun SemanticsNode.name(): String {
        val text = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
        val description = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
        return "$text $description".trim()
    }

    private fun SemanticsNode.describe() = "${config.getOrNull(SemanticsProperties.TestTag) ?: "untagged"} '${name()}'"

    /** What is wrong on screen right now: controls without a name, and controls with a hit area under 48 dp. */
    private fun problems(where: String): List<String> {
        val minPx = with(composeTestRule.density) { 48.dp.toPx() }
        assertTrue("$where: the scan found only ${operable().size} controls", operable().size >= 2)
        val unnamed = operable().filter { it.name().isBlank() }.map { "$where: no name: ${it.describe()}" }
        val small = composeTestRule.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter {
            !it.config.contains(SemanticsProperties.Disabled) &&
                (it.touchBoundsInRoot.width < minPx - 1f || it.touchBoundsInRoot.height < minPx - 1f)
        }.map { "$where: hit area under 48 dp: ${it.describe()} ${it.touchBoundsInRoot.width}x${it.touchBoundsInRoot.height}" }
        return unnamed + small
    }

    private fun scanEveryTab(prefix: String): List<String> =
        DesktopNavTab.entries.flatMap { tab ->
            holder.value = holder.value.copy(activeTab = tab)
            composeTestRule.waitForIdle()
            problems("$prefix ${tab.name}")
        }

    @Test
    fun `the dark theme keeps names and hit areas on every tab`() {
        show(seeded(), dark = true)
        val failures = scanEveryTab("dark")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `first-run setup keeps names and hit areas on every step`() {
        show(seeded(setupDone = false))
        val failures = mutableListOf<String>()
        failures += problems("setup welcome")
        composeTestRule.onNodeWithTag("setup_customize_button").performClick()
        failures += problems("setup schedule")
        composeTestRule.onNodeWithTag("setup_next_button").performClick()
        failures += problems("setup content")
        composeTestRule.onNodeWithTag("setup_next_button").performClick()
        failures += problems("setup finish")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `the open-source licences dialog keeps names and hit areas`() {
        show(seeded().copy(activeTab = DesktopNavTab.SAFETY_AND_DECISION_LOG))
        composeTestRule.onNodeWithTag("about_licenses").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        val failures = problems("licences dialog")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `the image details dialog keeps names and hit areas`() {
        val state = seeded()
        show(state.copy(activeTab = DesktopNavTab.TODAY_CANVAS, currentWallpaper = state.libraryWallpapers.first()))
        composeTestRule.onNodeWithTag("today_more_button").performClick()
        composeTestRule.onNodeWithTag("more_about").performClick()
        composeTestRule.waitForIdle()
        val failures = problems("image details dialog")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
