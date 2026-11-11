package com.sunloom.app.update

/** What "Check for updates" found. */
sealed interface UpdateCheckResult {
    /** No newer release; [version] is the one installed. */
    data class UpToDate(val version: String) : UpdateCheckResult

    /** A newer release exists; [pageUrl] is its page, to be opened in the browser (Sunloom never downloads or installs it). */
    data class Available(val version: String, val pageUrl: String) : UpdateCheckResult

    /** The answer could not be read or the request failed; this says nothing about whether an update exists. */
    data object Failed : UpdateCheckResult
}

/**
 * The update check. Only the GitHub build has one (Article XVIII §1); the Play build has none, and its store updates the app.
 * Nothing here runs unless the person asks.
 */
fun interface UpdateCheckFeature {
    suspend fun check(currentVersion: String): UpdateCheckResult
}
