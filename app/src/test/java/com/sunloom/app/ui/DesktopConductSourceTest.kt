package com.sunloom.app.ui

import com.sunloom.app.Requirement
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parts of Article XII §4 that Robolectric cannot show: what the window asks of the system (edge-to-edge, true
 * fullscreen from API 35), the pointer icon, the hover tint and the drop target. Each reads the source, with comments
 * removed, so each fails the day someone takes the thing out.
 *
 * These are static checks by name: they catch the ordinary ways to break a rule, not every possible route, so review
 * still matters. The behaviour behind each (what a drop imports, what the keys do) has its own tests.
 */
class DesktopConductSourceTest {

    private fun code(name: String): String {
        val file = File("src/main/java/com/sunloom/app/ui/$name")
        assertTrue("$name not found from ${File(".").absolutePath}", file.isFile)
        // Strings are matched first, so a `//` or `/*` inside a literal ("https://", "image/*") is not taken for a comment.
        val tokens = Regex("""/\*[\s\S]*?\*/|//[^\n]*|"(?:[^"\\\n]|\\.)*"""")
        return tokens.replace(file.readText()) { if (it.value.startsWith("\"")) it.value else "" }
    }

    /** The text of [text] from [start] up to (not including) [end]. */
    private fun between(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        assertTrue("`$start` not found", from >= 0)
        val to = text.indexOf(end, from)
        assertTrue("`$end` not found after `$start`", to > from)
        return text.substring(from, to)
    }

    @Requirement("XII.4.a")
    @Test
    fun `the window is drawn edge to edge and its content stays inside the safe drawing area`() {
        val activity = code("MainActivity.kt")
        assertTrue("the activity must call enableEdgeToEdge()", activity.contains("enableEdgeToEdge()"))
        assertTrue(
            "edge-to-edge has to be on before the content is set",
            activity.indexOf("enableEdgeToEdge()") < activity.indexOf("setContent {")
        )
        assertTrue(
            "the workspace must pad its content by the safe drawing insets",
            code("SunloomScreens.kt").contains("windowInsetsPadding(WindowInsets.safeDrawing)")
        )
    }

    @Requirement("XII.4.e")
    @Test
    fun `the workspace accepts an image dropped on it and hands the drop to the importer`() {
        val screens = code("SunloomScreens.kt")
        val target = between(screens, ".dragAndDropTarget(", "target = dropTarget")
        assertTrue("only an image may start a drop: $target", target.contains("startsWith(\"image/\")"))
        assertTrue("the drop must reach the actions", screens.contains("onDropImages(event.toAndroidDragEvent())"))
        val activity = code("MainActivity.kt")
        assertTrue("the activity must wire the drop", activity.contains("onDropImages = { dragEvent -> importDroppedImages(dragEvent) }"))
        assertTrue("the drop must be read through the tested helper", activity.contains("ShareIntentImport.droppedImageUris("))
    }

    @Requirement("XII.4.n")
    @Test
    fun `fullscreen asks for a true window only from API 35 and hides the system bars otherwise`() {
        val body = between(code("MainActivity.kt"), "private fun applyFullscreen", "override fun onResume")
        val gate = body.indexOf("Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM")
        assertTrue("the system fullscreen request must be gated on API 35", gate >= 0)
        assertTrue("one request, after the gate", body.indexOf("requestFullscreenMode(") > gate && body.lastIndexOf("requestFullscreenMode(") == body.indexOf("requestFullscreenMode("))
        assertTrue("it must ask to enter and to leave", body.contains("FULLSCREEN_MODE_REQUEST_ENTER") && body.contains("FULLSCREEN_MODE_REQUEST_EXIT"))
        assertTrue("the bars are hidden whatever the release", body.indexOf("controller.hide(WindowInsetsCompat.Type.systemBars())") > gate)
        assertTrue("and shown again on the way out", body.contains("controller.show(WindowInsetsCompat.Type.systemBars())"))
        assertTrue("the choice is re-asserted when the window comes back", code("MainActivity.kt").contains("if (isFullscreen) applyFullscreen(true)"))
    }

    @Requirement("XII.4.o")
    @Test
    fun `a busy pointer shows over the workspace while a wallpaper is applied or a sync runs`() {
        val screens = code("SunloomScreens.kt")
        val busy = Regex("""\.busyCursor\(([^)]*)\)""").find(screens)?.groupValues?.get(1).orEmpty()
        assertTrue("both long operations must raise it: `$busy`", busy.contains("isApplyingWallpaper") && busy.contains("isSyncingOnline"))
        val cursor = between(code("DesktopInput.kt"), "fun Modifier.busyCursor", "\n}\n")
        assertTrue("it shows the system wait pointer", cursor.contains("PointerIcon.TYPE_WAIT"))
        assertTrue("it does nothing when idle", cursor.contains("if (!busy) return this"))
        assertFalse(
            "it must not override the hand cursor of the controls inside (they keep their own)",
            cursor.contains("overrideDescendants")
        )
    }

    @Requirement("XII.4.c")
    @Test
    fun `a card and a link tint on hover`() {
        assertTrue("the context-menu card wrapper shows hover", code("DesktopContextMenu.kt").contains(".desktopHover("))
        assertTrue("the source link shows hover", code("TodayCanvasPane.kt").contains("Modifier.desktopHover("))
    }

    @Requirement("XII.4.d")
    @Test
    fun `the focus ring and hover tint wrap the whole 48dp target of the source link`() {
        val link = between(code("TodayCanvasPane.kt"), "testTag(\"source_page_link\")", ".clickable(")
        val ring = link.indexOf("desktopFocusRing(")
        val hover = link.indexOf("desktopHover(")
        val target = link.indexOf("minimumInteractiveComponentSize()")
        assertTrue("the link must keep a minimum target", target >= 0)
        assertTrue("the ring is drawn outside the target, so the pointer target is what the person sees", ring in 0 until target)
        assertTrue("so is the hover tint", hover in 0 until target)
    }
}
