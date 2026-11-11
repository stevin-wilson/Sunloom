package com.sunloom.app

import android.content.ComponentCallbacks2
import com.sunloom.app.data.repository.SeedReserve
import com.sunloom.app.data.sources.DefaultHttpsClient
import com.sunloom.app.ui.PreviewBitmapCache

/**
 * Gives memory back when the system asks (Article XII §2). The app keeps three in-memory caches - decoded previews
 * (up to 24 MB), rendered offline seeds (16 MB) and saved HTTP responses (a few MB) - and all of them rebuild on demand,
 * so dropping them only costs a little time later.
 */
object MemoryTrim {
    /** Which caches a trim level empties. */
    data class Plan(val preview: Boolean, val seedRenders: Boolean, val httpResponses: Boolean) {
        val isEmpty: Boolean get() = !preview && !seedRenders && !httpResponses
    }

    private val NOTHING = Plan(preview = false, seedRenders = false, httpResponses = false)
    private val SCREEN_ONLY = Plan(preview = true, seedRenders = false, httpResponses = false)
    private val EVERYTHING = Plan(preview = true, seedRenders = true, httpResponses = true)

    /**
     * Hiding the window frees what only the screen needs; a sync may still be running in the background and using the
     * rest. Real pressure while running, or being cached in the background, frees everything. A mild signal frees nothing.
     */
    fun planFor(level: Int): Plan = when {
        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> EVERYTHING
        level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> SCREEN_ONLY
        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> EVERYTHING
        else -> NOTHING
    }

    fun trim(level: Int) {
        val plan = planFor(level)
        if (plan.preview) PreviewBitmapCache.clear()
        if (plan.seedRenders) SeedReserve.clearSeedRenderCache()
        if (plan.httpResponses) DefaultHttpsClient.shared.clearCaches()
    }
}
