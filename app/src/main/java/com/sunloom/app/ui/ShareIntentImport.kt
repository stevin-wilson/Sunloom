package com.sunloom.app.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/**
 * Extracts image URIs handed to Sunloom through the system Share sheet or "Open with"
 * (`SEND`, `SEND_MULTIPLE`, `VIEW`).
 *
 * The declared MIME type is only a routing hint. Every URI returned here still goes through
 * `UserImportPipeline` (Article VI §10), whose L3 layer verifies magic bytes, size and bounds, so a
 * mislabeled or hostile payload is rejected there rather than trusted here.
 */
internal object ShareIntentImport {

    /** Upper bound on items accepted from one intent, so a hostile share cannot flood the reserve. */
    const val MAX_ITEMS_PER_INTENT = 20

    private val ACCEPTED_ACTIONS = setOf(
        Intent.ACTION_SEND,
        Intent.ACTION_SEND_MULTIPLE,
        Intent.ACTION_VIEW
    )

    fun isImportIntent(intent: Intent?): Boolean = intent?.action in ACCEPTED_ACTIONS

    /**
     * Returns de-duplicated `content://` URIs from [intent], capped at [MAX_ITEMS_PER_INTENT].
     * Non-content schemes (for example `file://`, `http://`) are ignored: they cannot be read with a
     * grant and `http` would be a network fetch outside Schedule A.
     */
    fun extractImageUris(intent: Intent?): List<Uri> {
        if (intent == null || !isImportIntent(intent)) return emptyList()
        val found = LinkedHashSet<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(found::add)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let(found::addAll)
            }
            Intent.ACTION_VIEW -> intent.data?.let(found::add)
        }
        intent.clipData?.let { addClipUris(it, found) }
        return found
            .filter { it.scheme == "content" }
            .take(MAX_ITEMS_PER_INTENT)
    }

    private fun addClipUris(clip: ClipData, into: MutableSet<Uri>) {
        for (i in 0 until clip.itemCount) {
            clip.getItemAt(i).uri?.let(into::add)
        }
    }
}
