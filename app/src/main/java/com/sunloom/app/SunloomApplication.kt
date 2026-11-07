package com.sunloom.app

import android.app.Application
import android.content.Context
import com.sunloom.app.data.repository.LocalLibraryRepository

/**
 * Process entry point. It does no work at start-up (no database, no network, no scheduling): everything is built
 * lazily on first use, so a broadcast or a worker that wakes the process pays only for what it touches.
 */
class SunloomApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

/**
 * The one place the app's long-lived collaborators are built. Callers ask for them here instead of constructing
 * them, which keeps wiring in one file and lets tests swap a piece.
 */
class AppContainer(context: Context) {
    private val appContext: Context = context.applicationContext

    /**
     * A repository over the shared database. It is a new object each call on purpose: it keeps per-sync counters
     * (sources attempted and failed), which must not be shared between the UI and a background worker. What is
     * expensive to share, the database and the HTTP client, already is, process-wide.
     */
    fun newRepository(): LocalLibraryRepository = LocalLibraryRepository(appContext)
}

/** The container of this process; builds a fresh one when the application is not [SunloomApplication] (some tests). */
fun Context.appContainer(): AppContainer =
    (applicationContext as? SunloomApplication)?.container ?: AppContainer(applicationContext)
