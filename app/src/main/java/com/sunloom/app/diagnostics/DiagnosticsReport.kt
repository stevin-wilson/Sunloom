package com.sunloom.app.diagnostics

import com.sunloom.app.domain.model.SourceIds
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.ui.ProjectLinks
import com.sunloom.app.ui.SunloomUiState
import java.net.URLEncoder

/**
 * The text behind "Copy diagnostics". It says what a maintainer needs to see what went wrong - the build, the
 * screens, how each source is doing, recent crashes - and nothing about who or where the person is: no location
 * (city, coordinates), no wallpaper titles, no file paths (Article VIII §2).
 */
object DiagnosticsReport {
    private const val CRASH_LINES = 12

    // A web address: keep its scheme and host (they say which source failed), drop the path and query.
    private val WEB_ADDRESS = Regex("""(https?://[^/\s?#)'",]+)[^\s)'",]*""", RegexOption.IGNORE_CASE)

    // Any other address (content://, file://, ...) can name a person's file; it is replaced whole.
    private val OTHER_ADDRESS = Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]*://[^\s)'",]*""")

    // A path on the device: starts a word with "/" and has at least a folder and a name. Not "java.base/java.lang" frames.
    private val FILE_PATH = Regex("""(?<![\w:./-])/(?:[\w.@+~%-]+/)+[\w.@+~%-]*""")

    /**
     * Text that comes from the device - an exception message, a failed request - can name a file, a content address or a
     * folder under the person's name. A bug report is public, so those are replaced; what failed and where in the code
     * stays.
     */
    fun scrub(text: String): String {
        val webSafe = WEB_ADDRESS.replace(text) { it.groupValues[1] }
        val noOtherAddresses = OTHER_ADDRESS.replace(webSafe) { match ->
            if (match.value.startsWith("http", ignoreCase = true)) match.value else "<address>"
        }
        return FILE_PATH.replace(noOtherAddresses, "<path>")
    }

    fun build(info: AppInfo, state: SunloomUiState, crashes: List<CrashEntry>): String = buildString {
        val p = state.preferences
        appendLine("Sunloom diagnostics")
        appendLine("Version: ${info.buildLabel}")
        appendLine("Android: API ${info.sdkInt}, ${info.deviceModel}")
        appendLine()
        appendLine("Displays:")
        state.capabilities.connectedDisplays.forEach { appendLine("  ${it.name}: ${it.widthPx}x${it.heightPx} @ ${it.densityDpi} dpi") }
        appendLine()
        appendLine("Settings: schedule=${p.scheduleMode.name}, automatic=${p.autoChangeEnabled}, paused=${p.isPaused}, " +
            "wifiOnly=${p.wifiOnlyDownloads}, deferOnBatterySaver=${p.deferOnBatterySaver}, setupDone=${p.isSetupCompleted}")
        appendLine("Reserve: ${state.lightReserveCount} light, ${state.darkReserveCount} dark, ${state.libraryWallpapers.size} total, " +
            "${state.totalCacheBytesUsed / (1024 * 1024)} MB of ${p.maxCacheBytes / (1024 * 1024)} MB")
        appendLine("Status: ${scrub(state.statusBannerMessage)}")
        appendLine()
        appendLine("Sources:")
        state.sourceDiagnostics.filter { it.isAdmitted }.forEach { s ->
            append("  ${s.sourceId}: enabled=${s.isEnabled}, health=${s.healthState.name}, failures=${s.consecutiveFailures}")
            if (s.lastError.isNotBlank()) append(", error=${scrub(s.lastError)}")
            if (s.lastSyncSummary.isNotBlank()) append(", last sync: ${scrub(s.lastSyncSummary)}")
            appendLine()
        }
        appendLine()
        if (crashes.isEmpty()) {
            appendLine("Recent crashes: none")
        } else {
            appendLine("Recent crashes (${crashes.size}):")
            crashes.forEach { c -> appendLine(scrub(c.text.lines().take(CRASH_LINES).joinToString("\n")).prependIndent("  ")) }
        }
    }
}

/** "Report a problem": a new issue on the project, opened in the browser for the person to read and send. */
object ReportProblem {
    private const val ISSUES = com.sunloom.app.ui.ProjectLinks.ISSUES_NEW

    /**
     * The issue form with the build filled in. The diagnostics themselves are not in the link (they can be long and
     * the person should see what they share): the body asks them to paste them after "Copy diagnostics".
     */
    fun issueUrl(info: AppInfo): String {
        val body = "**What happened?**\n\n\n**What did you expect?**\n\n\n" +
            "**Build:** ${info.buildLabel}, Android API ${info.sdkInt}\n\n" +
            "**Diagnostics:** (in Storage & About, press \"Copy diagnostics\" and paste it here)\n"
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return "$ISSUES?title=${enc("Problem in Sunloom ${info.versionName}")}&body=${enc(body)}"
    }
}

/**
 * "Report this image": a new issue on the project that names one picture by its public source details, so the maintainer
 * can review it and, if confirmed, add it to the safety denylist in a later release (Article VII §11). The picture itself,
 * file paths and device details are never in the link, and the user's own photos cannot be reported at all.
 */
object ReportImage {
    private const val TEMPLATE = "image_report.yml"

    /** The longest the link may be; browsers and GitHub accept more, but a short link survives being copied around. */
    private const val MAX_URL_LENGTH = 1_900

    /**
     * How many characters of each free-text field to keep, tried in order until the link is short enough. Encoding can
     * make a character up to nine times longer (non-Latin text), so the last step is small enough to fit whatever the text.
     */
    private val FIELD_LIMITS = intArrayOf(200, 80, 30, 10)

    /** True for pictures that came from a source; the person's own imported photos and saved original are theirs alone. */
    fun canReport(record: WallpaperRecord): Boolean =
        record.provenance.sourceId != SourceIds.USER_LOCAL && !record.isBaselineRestore

    fun issueUrl(info: AppInfo, record: WallpaperRecord): String? {
        if (!canReport(record)) return null
        var url = ""
        for (limit in FIELD_LIMITS) {
            url = build(info, record, limit)
            if (url.length <= MAX_URL_LENGTH) break
        }
        return url
    }

    /** The page address whole when it fits; otherwise without its query and fragment, which still opens the page. */
    private fun sourcePage(url: String, max: Int): String {
        val whole = url.trim().substringBefore('#')
        return if (whole.length <= max) whole else whole.substringBefore('?').take(max)
    }

    private fun build(info: AppInfo, record: WallpaperRecord, limit: Int): String {
        val p = record.provenance
        fun text(value: String) = DiagnosticsReport.scrub(value).replace(Regex("\\s+"), " ").trim().take(limit)
        val details = buildString {
            appendLine("Image: ${text(record.title)}")
            appendLine("Source: ${text(p.sourceName)} (${text(p.sourceId)})")
            appendLine("Asset id: ${text(p.assetId)}")
            appendLine("Source page: ${sourcePage(p.originalPageUrl, limit * 2)}")
            appendLine("Creator: ${text(p.creatorName)}")
            appendLine("Safety classification: ${p.safetyClassification} (${text(p.safetyClassificationSource)})")
            appendLine("Gate version: ${text(p.safetyGateVersion)}")
            appendLine("Sunloom: ${info.buildLabel}")
        }
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val title = "Image report: ${text(record.title).take(60)}"
        return "${ProjectLinks.ISSUES_NEW}?template=$TEMPLATE&title=${enc(title)}&details=${enc(details)}"
    }
}
