package com.sunloom.app.data.repository

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.core.graphics.scale
import com.sunloom.app.domain.evaluator.GridAverager
import java.io.File

/**
 * Stateless image decode / EXIF orientation / RGB-grid sampling helpers shared by
 * [LocalLibraryRepository] seed, catalog, and user-import pipelines.
 */

@SuppressLint("ExifInterface")
internal fun readExifOrientation(file: File): Int {
    return runCatching {
        val exif = android.media.ExifInterface(file.absolutePath)
        exif.getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION,
            android.media.ExifInterface.ORIENTATION_NORMAL
        )
    }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)
}

@SuppressLint("ExifInterface")
internal fun orientedDimensions(width: Int, height: Int, exifOrientation: Int): Pair<Int, Int> {
    return when (exifOrientation) {
        android.media.ExifInterface.ORIENTATION_ROTATE_90,
        android.media.ExifInterface.ORIENTATION_ROTATE_270,
        android.media.ExifInterface.ORIENTATION_TRANSPOSE,
        android.media.ExifInterface.ORIENTATION_TRANSVERSE -> height to width
        else -> width to height
    }
}

@SuppressLint("ExifInterface")
internal fun applyExifOrientationIfNeeded(file: File, bitmap: Bitmap): Bitmap {
    return runCatching {
        val orientation = readExifOrientation(file)
        val matrix = Matrix()
        when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            android.media.ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            android.media.ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrDefault(bitmap)
}

internal fun decodeSampledBitmapSafely(
    bytes: ByteArray,
    headerW: Int,
    headerH: Int,
    maxEdgePx: Int = 2048
): Bitmap? {
    return try {
        val opts = BitmapFactory.Options()
        val safeMaxEdge = maxEdgePx.coerceAtLeast(64)
        var w = headerW
        var h = headerH
        if (w <= 0 || h <= 0) {
            // The header parser could not size this file. Measure it with the decoder (no pixels are allocated) rather
            // than decode at full size: a tiny file can describe a picture of hundreds of megapixels.
            val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, probe)
            w = probe.outWidth
            h = probe.outHeight
        }
        if (w > 0 && h > 0) {
            var sampleSize = 1
            while ((w / sampleSize) > safeMaxEdge || (h / sampleSize) > safeMaxEdge) {
                sampleSize *= 2
            }
            opts.inSampleSize = sampleSize
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    } catch (_: OutOfMemoryError) {
        null
    } catch (_: Exception) {
        null
    }
}

/**
 * The grid the evaluator judges: every cell is the average of all the pixels it covers (see [GridAverager]).
 * Use [sampleBitmapToRgbGrid] only for the perceptual hash, whose stored values (including the permanent
 * blocklist) were computed from that sampling and must keep matching.
 */
internal fun sampleBitmapToEvaluationGrid(bitmap: Bitmap, gridW: Int, gridH: Int): IntArray {
    val width = bitmap.width
    return GridAverager.average(width, bitmap.height, gridW, gridH) { y, into ->
        bitmap.getPixels(into, 0, width, 0, y, width, 1)
    }
}

internal fun sampleBitmapToRgbGrid(bitmap: Bitmap, gridW: Int, gridH: Int): IntArray {
    val scaled = bitmap.scale(gridW, gridH)
    val pixels = IntArray(gridW * gridH)
    scaled.getPixels(pixels, 0, gridW, 0, 0, gridW, gridH)
    if (scaled != bitmap) scaled.recycle()
    for (i in pixels.indices) {
        pixels[i] = pixels[i] and 0xFFFFFF
    }
    return pixels
}
