package com.sunloom.app.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import com.sunloom.app.Requirement
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Accessibility conduct from Constitution Article XII section 4 (Googlebook desktop conduct) and
 * the Laws of UX (Fitts: reachable targets; Jakob: assistive tech conventions), asserted on the real
 * semantics tree of every workspace tab so a new control cannot ship without a name, a large enough
 * target, or a section heading.
 */
@RunWith(AndroidJUnit4::class)
// Tall window so scrollable panes are fully on screen and every control has real touch bounds.
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h4000dp")
@Requirement("XII.4")
class AccessibilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val minTarget = 48.dp

    private lateinit var holder: MutableState<SunloomUiState>

    private fun seededState(): SunloomUiState {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("sunloom_ui", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val initial = runBlocking { AndroidSunloomGateway(context).loadInitialSnapshot() }
        return initial.copy(preferences = initial.preferences.copy(isSetupCompleted = true))
    }

    /** Composes the app once (a compose rule allows a single setContent) and then switches tabs via state. */
    private fun showTab(tab: DesktopNavTab) {
        if (::holder.isInitialized) {
            holder.value = holder.value.copy(activeTab = tab)
            composeTestRule.waitForIdle()
            return
        }
        holder = mutableStateOf(seededState().copy(activeTab = tab))
        composeTestRule.setSunloomApp(onSelectTab = { holder.value = holder.value.copy(activeTab = it) }) { holder.value }
    }

    private fun clickableNodes(): List<SemanticsNode> =
        composeTestRule.onAllNodes(hasClickAction()).fetchSemanticsNodes()

    /** Controls a user can operate: anything clickable plus toggles, which may not expose a click action. */
    private fun operableNodes(): List<SemanticsNode> =
        composeTestRule.onAllNodes(hasClickAction() or isToggleable()).fetchSemanticsNodes()

    private fun SemanticsNode.label(): String {
        val text = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
        val description = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
        return "$text $description".trim()
    }

    private fun SemanticsNode.describe(): String =
        "${config.getOrNull(SemanticsProperties.TestTag) ?: "untagged"}[${size.width}x${size.height}px] '${label()}'"

    /**
     * Clickable nodes whose real hit area is under 48dp. Compose already widens every pointer target
     * to 48dp (`ViewConfiguration.minimumTouchTargetSize`), so a control only lands here when an
     * ancestor clips that area, which is exactly how a "48dp" button silently becomes a sliver.
     *
     * Node `size` is deliberately not used: under Robolectric text is measured with placeholder
     * metrics, so the visible width of a text control is meaningless in JVM tests.
     * Disabled controls are skipped; they are not actionable targets.
     */
    private fun smallHitAreas(): List<SemanticsNode> {
        val minPx = with(composeTestRule.density) { minTarget.toPx() }
        // Allow a 1px rounding tolerance from dp to px conversion.
        return clickableNodes().filter {
            !it.config.contains(SemanticsProperties.Disabled) &&
                (it.touchBoundsInRoot.width < minPx - 1f || it.touchBoundsInRoot.height < minPx - 1f)
        }
    }

    @Test
    fun `every actionable control on every tab keeps a 48dp hit area`() {
        val failures = mutableListOf<String>()
        var checked = 0
        for (tab in DesktopNavTab.entries) {
            showTab(tab)
            checked += clickableNodes().size
            failures += smallHitAreas().map { "${tab.name}: ${it.describe()} touch=${it.touchBoundsInRoot.width}x${it.touchBoundsInRoot.height}" }
        }
        assertTrue("scan matched suspiciously few controls ($checked)", checked >= 15)
        assertTrue("Hit areas under 48dp (Fitts / Article XII sec 4): $failures", failures.isEmpty())
    }

    @Test
    fun `the hit area check flags a control clipped by a scroll viewport`() {
        composeTestRule.setContent {
            // A 48dp button in a 20dp-tall scroll viewport: only a 20dp sliver is visible and clickable.
            Column(Modifier.size(width = 48.dp, height = 20.dp).verticalScroll(rememberScrollState())) {
                Box(Modifier.requiredSize(48.dp).clickable { })
            }
        }
        composeTestRule.waitForIdle()
        assertTrue("the clipped control must be reported", smallHitAreas().size == 1)
    }

    @Test
    fun `every clickable control on every tab has an accessible name`() {
        val failures = mutableListOf<String>()
        for (tab in DesktopNavTab.entries) {
            showTab(tab)
            for (node in operableNodes()) {
                if (node.label().isBlank()) failures += "${tab.name}: ${node.describe()}"
            }
        }
        assertTrue("Unnamed controls (icon-only without contentDescription): $failures", failures.isEmpty())
    }

    @Test
    fun `every tab marks each of its section titles as a heading`() {
        // Minimum headings per tab = the section titles that tab renders today (Sources: sources + categories;
        // Schedule: schedule + display preferences). Raise a count when a section is added.
        val expected = mapOf(
            DesktopNavTab.TODAY_CANVAS to 1,
            DesktopNavTab.RESERVE_AND_HISTORY to 1,
            DesktopNavTab.SOURCES_AND_CATEGORIES to 2,
            DesktopNavTab.DISPLAYS_AND_SCHEDULE to 2,
            DesktopNavTab.SAFETY_AND_DECISION_LOG to 1
        )
        val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        val short = DesktopNavTab.entries.mapNotNull { tab ->
            showTab(tab)
            val found = composeTestRule.onAllNodes(isHeading).fetchSemanticsNodes().size
            if (found < expected.getValue(tab)) "${tab.name}: $found < ${expected.getValue(tab)}" else null
        }
        assertTrue("Tabs with too few heading() nodes: $short", short.isEmpty())
    }

    @Test
    fun `status changes are announced through a live region`() {
        showTab(DesktopNavTab.TODAY_CANVAS)
        val live = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)
        assertTrue(
            "Home must expose a live region so status updates are announced",
            composeTestRule.onAllNodes(live).fetchSemanticsNodes().isNotEmpty()
        )
    }
}
