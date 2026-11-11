package com.sunloom.app

import android.app.Application
import android.content.Context
import kotlinx.coroutines.launch
import com.sunloom.app.data.repository.LocalLibraryRepository

/**
 * Process entry point. It does no work at start-up (no database, no network, no scheduling): everything is built
 * lazily on first use, so a broadcast or a worker that wakes the process pays only for what it touches.
 */
class SunloomApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    /** The system theme as last seen, to tell a real change from any other configuration change. */
    @Volatile
    private var lastNight: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        lastNight = isNight(resources.configuration)
        com.sunloom.app.ui.UiPrefs.warm(this) // only starts the background load of a small file
        // Only installs a handler (no I/O): an uncaught crash is written to a small local file the person can share.
        com.sunloom.app.diagnostics.CrashLog.install(this)
    }

    /**
     * In Follow-OS mode the wallpaper follows the system theme. While the app is running the change is acted on at
     * once, not at the next 15-minute poll.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val now = isNight(newConfig)
        val previous = lastNight
        lastNight = now
        if (previous == null || previous == now) return
        reactionScope.launch {
            runCatching {
                val prefs = container.newRepository().loadPreferences()
                val reacts = prefs.autoChangeEnabled && !prefs.isPaused &&
                    com.sunloom.app.platform.scheduler.RefreshSchedulePolicy.shouldReactToThemeChange(prefs.scheduleMode, previous, now)
                if (reacts) runChangeNow(this@SunloomApplication)
            }
        }
    }

    /** Where a theme change is reacted to (off the main thread); tests run it inline. */
    internal var reactionScope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)

    /** What "react" means; tests replace it to see that it happened. */
    internal var runChangeNow: (Context) -> Unit = { com.sunloom.app.platform.scheduler.SunloomWallpaperScheduler(it).runChangeNow() }

    private fun isNight(config: android.content.res.Configuration): Boolean =
        (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** The system wants memory back: empty the caches it can spare (they rebuild on demand). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        MemoryTrim.trim(level)
    }
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
