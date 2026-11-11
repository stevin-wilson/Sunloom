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

/** In a 400 dp window with the system font at 200 percent every control still has a name and a 48 dp hit area. */
@Requirement("XII.4")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h4000dp")
class AccessibilityNarrowLargeTextTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var holder: MutableState<SunloomUiState>

    private fun name(node: SemanticsNode): String {
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
        return "$text $description".trim()
    }

    @Test
    fun `every tab keeps names and hit areas`() {
        context.getSharedPreferences("sunloom_ui", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val loaded = runBlocking { AndroidSunloomGateway(context).loadInitialSnapshot() }
        holder = mutableStateOf(loaded.copy(preferences = loaded.preferences.copy(isSetupCompleted = true)))
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2.0f)) {
                SunloomApp(
                    uiState = holder.value,
                    onCompleteSetup = {}, onSelectTab = { holder.value = holder.value.copy(activeTab = it) },
                    onNextWallpaper = {}, onPreviousWallpaper = {}, onToggleKeep = {}, onRejectForever = {},
                    onTogglePause = {}, onRestoreOriginal = {}, onApplySpecific = {}, onToggleLegibilityOverlay = {},
                    onSyncOnline = {}, onToggleCategory = {}, onToggleSource = {}, onSelectCity = {},
                    onUpdateScheduleMode = {}, onUpdateLockPolicy = {}, onToggleBlurredFill = {}, onToggleWifiOnly = {},
                    onPickLocalPhoto = {}
                )
            }
        }
        val minPx = with(composeTestRule.density) { 48.dp.toPx() }
        val failures = DesktopNavTab.entries.flatMap { tab ->
            holder.value = holder.value.copy(activeTab = tab)
            composeTestRule.waitForIdle()
            val count = composeTestRule.onAllNodes(hasClickAction() or isToggleable()).fetchSemanticsNodes().size
            assertTrue("${tab.name}: the scan found only $count controls", count >= 3)
            val unnamed = composeTestRule.onAllNodes(hasClickAction() or isToggleable()).fetchSemanticsNodes()
                .filter { name(it).isBlank() }.map { "${tab.name}: no name: ${it.config.getOrNull(SemanticsProperties.TestTag)}" }
            val small = composeTestRule.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter {
                !it.config.contains(SemanticsProperties.Disabled) &&
                    (it.touchBoundsInRoot.width < minPx - 1f || it.touchBoundsInRoot.height < minPx - 1f)
            }.map { "${tab.name}: hit area under 48 dp: ${it.config.getOrNull(SemanticsProperties.TestTag)}" }
            unnamed + small
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
