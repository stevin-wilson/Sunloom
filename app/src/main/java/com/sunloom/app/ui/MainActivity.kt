package com.sunloom.app.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.view.DragEvent
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.core.net.toUri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.sunloom.app.BuildConfig
import com.sunloom.app.R
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.platform.export.WallpaperExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val DISPLAY_EVENT_DEBOUNCE_MS = 1_500L
        private const val STATE_FULLSCREEN = "sunloom.fullscreen"
    }

    private lateinit var gateway: SunloomGateway
    private lateinit var viewModel: MainViewModel
    private val mainHandler = Handler(Looper.getMainLooper())

    /** True while the system bars are hidden for unobstructed wallpaper viewing (F11). */
    private var isFullscreen by mutableStateOf(false)
    private var lastNightMask = Int.MIN_VALUE
    private var hasStartedBefore = false

    // Save as…: the approved rendition is written through the system file picker (Article VI §6).
    private val exporter by lazy { WallpaperExporter(contentResolver, cacheDir) }
    private var pendingExport: WallpaperRecord? = null
    private var exportMimeType = "image/jpeg"
    private val exportImageLauncher = registerForActivityResult(
        object : ActivityResultContracts.CreateDocument("*/*") {
            override fun createIntent(context: Context, input: String): Intent =
                super.createIntent(context, input).setType(exportMimeType)
        }
    ) { uri -> onExportImageTargetChosen(uri) }
    private val exportAttributionLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> onExportAttributionTargetChosen(uri) }
    private val debouncedDisplayRunnable = Runnable {
        notifyDisplaysChangedNow()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = scheduleDebouncedDisplayCheck()
        override fun onDisplayRemoved(displayId: Int) = scheduleDebouncedDisplayCheck()
        override fun onDisplayChanged(displayId: Int) = scheduleDebouncedDisplayCheck()
    }

    private fun scheduleDebouncedDisplayCheck() {
        mainHandler.removeCallbacks(debouncedDisplayRunnable)
        mainHandler.postDelayed(debouncedDisplayRunnable, DISPLAY_EVENT_DEBOUNCE_MS)
    }

    private fun notifyDisplaysChangedNow() {
        if (::viewModel.isInitialized) {
            viewModel.onDisplaysChanged()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .detectLeakedSqlLiteObjects()
                    .penaltyLog()
                    .build()
            )
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lastNightMask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        isFullscreen = savedInstanceState?.getBoolean(STATE_FULLSCREEN, false) ?: false

        gateway = AndroidSunloomGateway(applicationContext)
        viewModel = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MainViewModel(gateway, strings = AndroidUiStrings(applicationContext)) as T
                }
            }
        )[MainViewModel::class.java]

        // A cold start from "Open with" / Share carries the images; a recreate must not re-import them.
        // Relaunching from Recents re-delivers the original intent with FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY;
        // that must not import (and apply) the same images a second time.
        if (savedInstanceState == null && !intent.isFromHistory()) importFromIntent(intent)

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            var pickingAsBaselineRestore by remember { mutableStateOf(false) }

            val photoPickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.PickVisualMedia()
            ) { uri ->
                if (uri != null) {
                    viewModel.importLocalUri(uri.toString(), pickingAsBaselineRestore)
                }
            }

            SunloomApp(
                uiState = uiState,
                isFullscreen = isFullscreen,
                actions = SunloomActions(
                    onCompleteSetup = { viewModel.completeFirstRunSetup(it) },
                    onSelectTab = { viewModel.selectTab(it) },
                    onNextWallpaper = { viewModel.triggerNextWallpaperSmart() },
                    onPreviousWallpaper = { viewModel.returnToPreviousWallpaper() },
                    onToggleKeep = { viewModel.toggleKeepCurrentWallpaper() },
                    onRejectForever = { viewModel.dislikeCurrentWithUndo() },
                    onUndoDiscard = { viewModel.undoPendingDiscard() },
                    onCommitDiscard = { viewModel.commitPendingDiscard() },
                    onToggleAutoSwitch = { viewModel.setAutoChangeEnabled(it) },
                    onReopenSetup = { viewModel.reopenSetup() },
                    onResetSunloom = { viewModel.resetSunloom() },
                    onCopyDiagnostics = {
                        viewModel.copyDiagnostics { text ->
                            runOnUiThread {
                                val clipboard = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                val label = getString(R.string.diagnostics_clip_label)
                                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
                            }
                        }
                    },
                    onCheckForUpdates = { viewModel.checkForUpdates() },
                    onReportProblem = {
                        val url = com.sunloom.app.diagnostics.ReportProblem.issueUrl(com.sunloom.app.diagnostics.AppInfo.current())
                        runCatching {
                            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri()))
                        }
                    },
                    onTogglePause = { viewModel.togglePauseAutomation() },
                    onRestoreOriginal = { viewModel.restoreOriginalBaselineWallpaper() },
                    onRestoreSystemDefault = { viewModel.restoreSystemDefaultWallpaper() },
                    onApplySpecific = { viewModel.applySpecificWallpaper(it) },
                    onDeleteFromReserve = { viewModel.removeFromReserveWithUndo(it) },
                    onToggleKeepSpecific = { viewModel.toggleKeepSpecificWallpaper(it) },
                    onToggleLegibilityOverlay = { viewModel.toggleLegibilityOverlay() },
                    onSyncOnline = { viewModel.triggerOnlineSync() },
                    onToggleCategory = { viewModel.toggleCategory(it) },
                    onToggleSource = { viewModel.toggleSource(it) },
                    onSelectCity = { viewModel.selectCity(it) },
                    onUpdateScheduleMode = { mode -> viewModel.updatePreferences { it.copy(scheduleMode = mode) } },
                    onUpdateLockPolicy = { pol -> viewModel.updatePreferences { it.copy(lockWorkspacePolicy = pol) } },
                    onToggleBlurredFill = { fill -> viewModel.updatePreferences { it.copy(allowBlurredFrameFill = fill) } },
                    onToggleWifiOnly = { wifi -> viewModel.updatePreferences { it.copy(wifiOnlyDownloads = wifi) } },
                    onUpdateFixedHours = { lightH, darkH -> viewModel.updateFixedHours(lightH, darkH) },
                    onUpdateCustomCoordinates = { lat, lon, label -> viewModel.updateCustomCoordinates(lat, lon, label) },
                    onUpdateMaxCacheBytes = { bytes -> viewModel.updateMaxCacheBytes(bytes) },
                    onToggleBatterySaverDeferral = { enabled -> viewModel.toggleBatterySaverDeferral(enabled) },
                    onUpdateDisplayPolicy = { pol -> viewModel.updateDisplayPolicy(pol) },
                    onForgetRememberedDisplay = { key -> viewModel.forgetRememberedDisplay(key) },
                    onToggleFullscreen = { applyFullscreen(!isFullscreen) },
                    onDropImages = { dragEvent -> importDroppedImages(dragEvent) },
                    onSaveAs = { saveWallpaperAs(it) },
                    onDeleteManyFromReserve = { viewModel.removeManyFromReserveWithUndo(it) },
                    onPickLocalPhoto = { asBaseline ->
                        pickingAsBaselineRestore = asBaseline
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                )
            )
        }
    }

    /** Imports images dropped from the Files app or another window (each becomes a reserve item). */
    private fun importDroppedImages(dragEvent: DragEvent): Boolean {
        val clip = dragEvent.clipData ?: return false
        // The clip's own description says what it carries without asking another process; each item is checked off the
        // main thread by the view model before it is imported.
        val carriesImages = (0 until clip.description.mimeTypeCount).any { clip.description.getMimeType(it).startsWith("image/") }
        val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        if (!carriesImages || uris.isEmpty()) return false
        // Cross-app content URIs are only readable while this grant is held; release it as soon as the
        // batch finishes so grants never accumulate for the life of the window.
        val grant = requestDragAndDropPermissions(dragEvent)
        viewModel.importLocalUris(uris.map { it.toString() }) { grant?.release() }
        return true
    }

    /** Imports images delivered by the Share sheet or "Open with" (SEND, SEND_MULTIPLE, VIEW). */
    private fun importFromIntent(source: Intent?) {
        val uris = ShareIntentImport.extractImageUris(source)
        if (uris.isEmpty()) return
        viewModel.importLocalUris(uris.map { it.toString() })
    }

    private fun saveWallpaperAs(record: WallpaperRecord) {
        lifecycleScope.launch {
            // Looks at the file on disk, so not on the main thread.
            if (!withContext(Dispatchers.IO) { exporter.isExportable(record) }) {
                viewModel.announce(getString(R.string.export_failed))
                return@launch
            }
            pendingExport = record
            exportMimeType = exporter.mimeTypeFor(record)
            exportImageLauncher.launch(exporter.suggestedImageName(record))
        }
    }

    private fun onExportImageTargetChosen(uri: Uri?) {
        val record = pendingExport
        if (uri == null || record == null) {
            pendingExport = null
            return
        }
        lifecycleScope.launch {
            val written = withContext(Dispatchers.IO) { exporter.writeImage(record, uri) }
            when {
                !written -> {
                    pendingExport = null
                    viewModel.announce(getString(R.string.export_failed))
                }
                exporter.requiresAttributionFile(record) -> {
                    viewModel.announce(getString(R.string.export_attribution_prompt))
                    exportAttributionLauncher.launch(exporter.suggestedAttributionName(record))
                }
                else -> {
                    pendingExport = null
                    viewModel.announce(getString(R.string.export_saved))
                }
            }
        }
    }

    private fun onExportAttributionTargetChosen(uri: Uri?) {
        val record = pendingExport
        pendingExport = null
        if (record == null) return
        if (uri == null) {
            // Image already saved; make clear the credit file is still owed for CC BY / CC BY-SA.
            viewModel.announce(getString(R.string.export_attribution_prompt))
            return
        }
        lifecycleScope.launch {
            val written = withContext(Dispatchers.IO) { exporter.writeAttribution(record, uri) }
            viewModel.announce(getString(if (written) R.string.export_saved else R.string.export_failed))
        }
    }

    private fun Intent.isFromHistory(): Boolean =
        flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTask: a second launch focuses this window instead of opening another one.
        importFromIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_FULLSCREEN, isFullscreen)
    }

    private fun applyFullscreen(enabled: Boolean) {
        isFullscreen = enabled
        // Desktop windowing: ask the system to move this window to/from true fullscreen (API 35+).
        // The immersive bar hiding below remains the behaviour on older releases and on phones.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            runCatching {
                requestFullscreenMode(
                    if (enabled) FULLSCREEN_MODE_REQUEST_ENTER else FULLSCREEN_MODE_REQUEST_EXIT,
                    null
                )
            }
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onResume() {
        super.onResume()
        // The system may restore bars while we were away; re-assert the user's choice.
        if (isFullscreen) applyFullscreen(true)
    }

    override fun onStart() {
        super.onStart()
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        displayManager?.registerDisplayListener(displayListener, mainHandler)
        // The ViewModel's initial load already probes displays on a cold start; only re-check
        // when returning from the background, where a dock/undock may have been missed.
        if (hasStartedBefore) notifyDisplaysChangedNow()
        hasStartedBefore = true
    }

    override fun onStop() {
        // Don't leave an undoable action dangling if the window goes away.
        if (::viewModel.isInitialized) viewModel.commitPendingDiscard()
        mainHandler.removeCallbacks(debouncedDisplayRunnable)
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        displayManager?.unregisterDisplayListener(displayListener)
        if (::gateway.isInitialized) {
            lifecycleScope.launch {
                gateway.checkpointDatabaseForBackup()
            }
        }
        super.onStop()
    }

    /** Feeds the system Keyboard Shortcuts Helper (Meta+/) with Sunloom's accelerators. */
    override fun onProvideKeyboardShortcuts(
        data: MutableList<KeyboardShortcutGroup>,
        menu: Menu?,
        deviceId: Int
    ) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        data += KeyboardShortcutGroup(
            getString(R.string.shortcut_group_wallpaper),
            listOf(
                KeyboardShortcutInfo(getString(R.string.shortcut_next), KeyEvent.KEYCODE_N, 0),
                KeyboardShortcutInfo(getString(R.string.shortcut_previous), KeyEvent.KEYCODE_P, 0),
                KeyboardShortcutInfo(getString(R.string.shortcut_keep), KeyEvent.KEYCODE_K, 0),
                KeyboardShortcutInfo(getString(R.string.shortcut_dislike), KeyEvent.KEYCODE_FORWARD_DEL, 0),
                KeyboardShortcutInfo(getString(R.string.shortcut_fullscreen), KeyEvent.KEYCODE_F11, 0),
                KeyboardShortcutInfo(getString(R.string.shortcut_go_home), KeyEvent.KEYCODE_1, KeyEvent.META_CTRL_ON),
                KeyboardShortcutInfo(getString(R.string.shortcut_go_reserve), KeyEvent.KEYCODE_2, KeyEvent.META_CTRL_ON),
                KeyboardShortcutInfo(getString(R.string.shortcut_go_sources), KeyEvent.KEYCODE_3, KeyEvent.META_CTRL_ON),
                KeyboardShortcutInfo(getString(R.string.shortcut_go_schedule), KeyEvent.KEYCODE_4, KeyEvent.META_CTRL_ON),
                KeyboardShortcutInfo(getString(R.string.shortcut_go_storage), KeyEvent.KEYCODE_5, KeyEvent.META_CTRL_ON),
                KeyboardShortcutInfo(getString(R.string.shortcut_dislike), KeyEvent.KEYCODE_DEL, 0),
                KeyboardShortcutInfo(getString(R.string.shortcut_undo), KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON),
                KeyboardShortcutInfo(getString(R.string.shortcut_copy_attribution), KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON),
                KeyboardShortcutInfo(getString(R.string.shortcut_save_as), KeyEvent.KEYCODE_S, KeyEvent.META_CTRL_ON),
                KeyboardShortcutInfo(getString(R.string.shortcut_open_schedule), KeyEvent.KEYCODE_COMMA, KeyEvent.META_CTRL_ON),
                KeyboardShortcutInfo(getString(R.string.shortcut_escape), KeyEvent.KEYCODE_ESCAPE, 0)
            )
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::viewModel.isInitialized) return
        // Free-form window resizes fire this per frame; only a real Light/Dark flip needs the
        // (expensive) snapshot reload. Size/density changes are covered by the debounced check.
        val nightMask = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (nightMask != lastNightMask) {
            lastNightMask = nightMask
            viewModel.onSystemUiModeChanged()
        }
        scheduleDebouncedDisplayCheck()
    }
}
