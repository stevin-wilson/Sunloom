package com.sunloom.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.layout.size
import java.io.File

/**
 * Byte-bounded LRU cache for downsampled UI preview bitmaps so `WallpaperPreviewSurface`
 * never decodes full-resolution bitmaps on the main UI thread. Entries are keyed by path AND
 * target size, so a 120dp Reserve thumbnail never pays for (or evicts) a 960x600 hero preview.
 */
internal object PreviewBitmapCache {
    /** ~24 MB: a handful of hero previews or a couple hundred thumbnails. */
    const val MAX_BYTES = 24 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount.coerceAtLeast(1)
    }

    internal fun key(path: String, maxW: Int, maxH: Int) = "$path@${maxW}x$maxH"

    fun get(path: String, maxW: Int = DEFAULT_MAX_W, maxH: Int = DEFAULT_MAX_H): Bitmap? = synchronized(cache) {
        cache.get(key(path, maxW, maxH))?.takeIf { !it.isRecycled }
    }

    fun put(path: String, maxW: Int, maxH: Int, bitmap: Bitmap) = synchronized(cache) {
        cache.put(key(path, maxW, maxH), bitmap)
    }

    internal fun clear() = synchronized(cache) { cache.evictAll() }

    internal fun sizeBytes(): Int = synchronized(cache) { cache.size() }

    fun decodeDownsampled(path: String, maxW: Int = DEFAULT_MAX_W, maxH: Int = DEFAULT_MAX_H): Bitmap? {
        if (path.isBlank()) return null
        get(path, maxW, maxH)?.let { return it }
        val file = File(path)
        if (!file.exists() || file.length() == 0L) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val opts = BitmapFactory.Options()
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                opts.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxW, maxH)
            }
            BitmapFactory.decodeFile(path, opts)?.also { put(path, maxW, maxH, it) }
        }.getOrNull()
    }

    /** Smallest power-of-two sample size that fits the source inside [maxW] x [maxH]. */
    internal fun sampleSizeFor(srcW: Int, srcH: Int, maxW: Int, maxH: Int): Int {
        var sample = 1
        while ((srcW / sample) > maxW || (srcH / sample) > maxH) sample *= 2
        return sample
    }

    const val DEFAULT_MAX_W = 960
    const val DEFAULT_MAX_H = 600
}
