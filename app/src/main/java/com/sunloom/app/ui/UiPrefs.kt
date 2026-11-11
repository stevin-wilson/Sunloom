package com.sunloom.app.ui

import android.content.Context
import androidx.core.content.edit

/**
 * Small, purely presentational preferences (how the Reserve is laid out). They never affect safety,
 * scheduling or provenance, so they live beside the UI instead of in the wallpaper preference store
 * (Article VIII §1: still local-only).
 */
internal object UiPrefs {
    private const val FILE = "sunloom_ui"
    private const val KEY_RESERVE_GRID = "reserve_grid_view"

    /** Starts loading the file in the background so the first read, while the screen is built, does not wait for the disk. */
    fun warm(context: Context) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    fun reserveGridView(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_RESERVE_GRID, false)

    fun setReserveGridView(context: Context, grid: Boolean) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_RESERVE_GRID, grid) }
    }
}
