package com.sunloom.app.platform.export

import android.content.ContentResolver
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import com.sunloom.app.domain.attribution.AttributionFormatter
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.model.WallpaperStatus
import java.io.File
import java.io.IOException

/**
 * "Save as…" for an already-approved wallpaper (Article VI §6, Article XVIII §2 as amended in v0.8).
 *
 * Only the stored, gate-approved rendition is ever exported; nothing is re-fetched or re-decoded here,
 * so export cannot bypass the safety gate. Attribution travels with the image: JPEGs get the credit
 * embedded as EXIF metadata, and CC BY / CC BY-SA works additionally require a companion
 * attribution file because embedded metadata is easily stripped by other tools.
 */
class WallpaperExporter(
    private val contentResolver: ContentResolver,
    private val cacheDir: File
) {

    /** True when the record may be exported: approved, on disk, and not rejected or disabled. */
    fun isExportable(record: WallpaperRecord): Boolean {
        if (record.isRejected || record.status == WallpaperStatus.DISABLED_BY_USER) return false
        if (!record.isBaselineRestore && !record.evaluation.isEligible) return false
        val file = File(record.localFilePath)
        return file.isFile && file.length() > 0L
    }

    fun mimeTypeFor(record: WallpaperRecord): String = when (File(record.localFilePath).extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    fun suggestedImageName(record: WallpaperRecord): String {
        val base = sanitizeFileName(record.title).ifBlank { "wallpaper" }
        val ext = File(record.localFilePath).extension.lowercase().ifBlank { "jpg" }
        return "$base.$ext"
    }

    fun suggestedAttributionName(record: WallpaperRecord): String =
        "${sanitizeFileName(record.title).ifBlank { "wallpaper" }}-attribution.txt"

    /** CC BY and CC BY-SA require credit with every copy; the user must be given a place for it. */
    fun requiresAttributionFile(record: WallpaperRecord): Boolean =
        record.provenance.licenseType == LicenseType.CC_BY ||
            record.provenance.licenseType == LicenseType.CC_BY_SA

    /** Copies the approved rendition to [target]; returns false (and writes nothing useful) on failure. */
    fun writeImage(record: WallpaperRecord, target: Uri): Boolean {
        if (!isExportable(record)) return false
        return try {
            val source = File(record.localFilePath)
            val payload = if (mimeTypeFor(record) == "image/jpeg") withEmbeddedCredit(record, source) else source
            try {
                contentResolver.openOutputStream(target, "wt")?.use { out ->
                    payload.inputStream().use { it.copyTo(out) }
                } ?: return false
            } finally {
                if (payload !== source) payload.delete()
            }
            true
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    fun writeAttribution(record: WallpaperRecord, target: Uri): Boolean {
        return try {
            contentResolver.openOutputStream(target, "wt")?.use { out ->
                out.write((AttributionFormatter.format(record) + "\n").toByteArray(Charsets.UTF_8))
            } != null
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    /** Returns a temp copy with credit EXIF tags, or [source] itself if embedding is not possible. */
    private fun withEmbeddedCredit(record: WallpaperRecord, source: File): File {
        if (record.provenance.licenseType == LicenseType.USER_OWNED) return source
        val temp = File.createTempFile("export-", ".jpg", cacheDir)
        return try {
            source.copyTo(temp, overwrite = true)
            val exif = ExifInterface(temp.absolutePath)
            val p = record.provenance
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, AttributionFormatter.format(record))
            if (p.creatorName.isNotBlank()) exif.setAttribute(ExifInterface.TAG_ARTIST, p.creatorName)
            exif.setAttribute(ExifInterface.TAG_COPYRIGHT, "${p.licenseType.label} ${p.licenseUrl}".trim())
            exif.saveAttributes()
            temp
        } catch (e: IOException) {
            temp.delete()
            source
        }
    }

    internal companion object {
        fun sanitizeFileName(raw: String): String =
            raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(80)
                .trim('.', ' ')
    }
}
