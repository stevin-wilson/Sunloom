package com.sunloom.app.domain.attribution

import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.WallpaperRecord

/**
 * Builds the human-readable credit line for a wallpaper (Article VI §6): title, creator, source,
 * license with link, and the note that the image was cropped and resized. Used by "Copy attribution"
 * and by Save-as export so the credit always travels with the file.
 */
object AttributionFormatter {

    fun format(record: WallpaperRecord): String {
        val p = record.provenance
        val lines = mutableListOf<String>()
        when (p.licenseType) {
            LicenseType.USER_OWNED -> {
                lines += "\"${record.title}\" — user-provided file."
            }
            else -> {
                val creator = p.creatorName.trim()
                lines += if (creator.isBlank()) "\"${record.title}\"" else "\"${record.title}\" by $creator"
            }
        }
        if (p.sourceId == "seed_reserve") {
            lines += "Generated on-device by Sunloom."
        } else if (p.licenseType != LicenseType.USER_OWNED) {
            val source = if (p.originalPageUrl.isBlank()) p.sourceName else "${p.sourceName} — ${p.originalPageUrl}"
            lines += "Source: $source"
        }
        if (p.licenseType != LicenseType.USER_OWNED) {
            val version = if (p.licenseVersion.isBlank()) "" else " (v${p.licenseVersion})"
            val link = if (p.licenseUrl.isBlank()) "" else " — ${p.licenseUrl}"
            lines += "License: ${p.licenseType.label}$version$link"
        }
        if (p.attributionText.isNotBlank() && p.licenseType != LicenseType.USER_OWNED) {
            lines += p.attributionText.trim()
        }
        lines += "Modified: cropped and resized for display."
        return lines.joinToString("\n")
    }
}
