package com.sunloom.app.update

import java.net.URI

/**
 * The only hosts the GitHub-flavor update check may contact (Article XVIII §1). It is deliberately separate from
 * `SourceRegistry.isHostAllowed`, which governs wallpaper sources, and it exists only in `src/github/`, so the
 * Play flavor contains neither this list nor any code that uses it.
 */
object UpdateHosts {
    val ALLOWED: Set<String> = setOf("api.github.com", "github.com")

    /** True only for an `https` URL whose host is exactly one of [ALLOWED]. */
    fun isAllowed(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && uri.host?.lowercase() in ALLOWED
    }
}
