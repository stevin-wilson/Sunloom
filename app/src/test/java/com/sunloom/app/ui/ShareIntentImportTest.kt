package com.sunloom.app.ui

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ShareIntentImportTest {

    private fun content(path: String): Uri = Uri.parse("content://com.example.files/$path")

    @Test
    fun `send extracts the single stream uri`() {
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, content("a.jpg"))
        assertEquals(listOf(content("a.jpg")), ShareIntentImport.extractImageUris(intent))
    }

    @Test
    fun `send multiple extracts every stream uri in order`() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(
            Intent.EXTRA_STREAM,
            arrayListOf(content("a.jpg"), content("b.png"))
        )
        assertEquals(
            listOf(content("a.jpg"), content("b.png")),
            ShareIntentImport.extractImageUris(intent)
        )
    }

    @Test
    fun `view extracts the data uri`() {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(content("v.webp"), "image/webp")
        assertEquals(listOf(content("v.webp")), ShareIntentImport.extractImageUris(intent))
    }

    @Test
    fun `duplicates from stream and clip data collapse to one item`() {
        val uri = content("a.jpg")
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri)
        intent.clipData = ClipData.newRawUri("image", uri)
        assertEquals(listOf(uri), ShareIntentImport.extractImageUris(intent))
    }

    @Test
    fun `non content schemes are ignored`() {
        val intent = Intent(Intent.ACTION_VIEW).setData(Uri.parse("https://example.com/x.jpg"))
        assertTrue(ShareIntentImport.extractImageUris(intent).isEmpty())
        val file = Intent(Intent.ACTION_VIEW).setData(Uri.parse("file:///sdcard/x.jpg"))
        assertTrue(ShareIntentImport.extractImageUris(file).isEmpty())
    }

    @Test
    fun `unrelated actions and null intents yield nothing`() {
        assertFalse(ShareIntentImport.isImportIntent(Intent(Intent.ACTION_MAIN)))
        assertTrue(ShareIntentImport.extractImageUris(Intent(Intent.ACTION_MAIN)).isEmpty())
        assertTrue(ShareIntentImport.extractImageUris(null).isEmpty())
    }

    @Test
    fun `one intent cannot import more than the cap`() {
        val many = ArrayList((0 until ShareIntentImport.MAX_ITEMS_PER_INTENT + 15).map { content("$it.jpg") })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, many)
        assertEquals(
            ShareIntentImport.MAX_ITEMS_PER_INTENT,
            ShareIntentImport.extractImageUris(intent).size
        )
    }
}
