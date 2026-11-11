package com.sunloom.app.diagnostics

import com.sunloom.app.ui.SunloomUiState
import java.net.URLEncoder

/**
 * The text behind "Copy diagnostics". It says what a maintainer needs to see what went wrong - the build, the
 * screens, how each source is doing, recent crashes - and nothing about who or where the person is: no location
 * (city, coordinates), no wallpaper titles, no file paths (Article VIII §2).
 */
object DiagnosticsReport {
    private const val CRASH_LINES = 12

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
        appendLine("Status: ${state.statusBannerMessage}")
        appendLine()
        appendLine("Sources:")
        state.sourceDiagnostics.filter { it.isAdmitted }.forEach { s ->
            append("  ${s.sourceId}: enabled=${s.isEnabled}, health=${s.healthState.name}, failures=${s.consecutiveFailures}")
            if (s.lastError.isNotBlank()) append(", error=${s.lastError}")
            if (s.lastSyncSummary.isNotBlank()) append(", last sync: ${s.lastSyncSummary}")
            appendLine()
        }
        appendLine()
        if (crashes.isEmpty()) {
            appendLine("Recent crashes: none")
        } else {
            appendLine("Recent crashes (${crashes.size}):")
            crashes.forEach { c -> appendLine(c.text.lines().take(CRASH_LINES).joinToString("\n").prependIndent("  ")) }
        }
    }
}

/** "Report a problem": a new issue on the project, opened in the browser for the person to read and send. */
object ReportProblem {
    private const val ISSUES = "https://github.com/stevin-wilson/Sunloom/issues/new"

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
