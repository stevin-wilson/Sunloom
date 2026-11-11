package com.sunloom.app.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.appContainer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The real activity starts, and a picture shared to Sunloom ends up in the person's library through the same
 * checks as any other import (Constitution Article VI §10, XVIII §2).
 */
@Requirement("XVIII.2")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class MainActivityLaunchTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun waitUntil(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (check()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun `the activity starts and stays resumed`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun `a picture shared to Sunloom is added to the library`() {
        val bmp = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF1E293B.toInt())
        canvas.drawCircle(60f, 40f, 30f, Paint().apply { color = 0xFFF59E0B.toInt() })
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val uri = Uri.parse("content://sunloom.test/shared.png")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))

        val send = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ActivityScenario.launch<MainActivity>(send).use {
            waitUntil("the shared picture to be imported") {
                context.appContainer().newRepository().getAllWallpapers().any { it.provenance.sourceId == "user_local" }
            }
        }
    }
}
