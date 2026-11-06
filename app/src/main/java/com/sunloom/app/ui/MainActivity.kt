package com.sunloom.app.ui

import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.sunloom.app.BuildConfig
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val DISPLAY_EVENT_DEBOUNCE_MS = 1_500L
    }

    private lateinit var gateway: SunloomGateway
    private lateinit var viewModel: MainViewModel
    private val mainHandler = Handler(Looper.getMainLooper())

    /** True while the system bars are hidden for unobstructed wallpaper viewing (F11). */
    private var isFullscreen by mutableStateOf(false)
    private var lastNightMask = Int.MIN_VALUE
    private val dropPermissions = mutableListOf<android.view.DragAndDropPermissions>()
    private var hasStartedBefore = false
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

        gateway = AndroidSunloomGateway(applicationContext)
        viewModel = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MainViewModel(gateway) as T
                }
            }
        )[MainViewModel::class.java]

        setContent {
            val uiState by viewModel.uiState.collectAsState()
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
                onCompleteSetup = { viewModel.completeFirstRunSetup(it) },
                onSelectTab = { viewModel.selectTab(it) },
                onNextWallpaper = { viewModel.triggerNextWallpaperSmart() },
                onPreviousWallpaper = { viewModel.returnToPreviousWallpaper() },
                onToggleKeep = { viewModel.toggleKeepCurrentWallpaper() },
                onRejectForever = { viewModel.rejectCurrentWallpaperForever() },
                onTogglePause = { viewModel.togglePauseAutomation() },
                onRestoreOriginal = { viewModel.restoreOriginalBaselineWallpaper() },
                onRestoreSystemDefault = { viewModel.restoreSystemDefaultWallpaper() },
                onApplySpecific = { viewModel.applySpecificWallpaper(it) },
                onDeleteFromReserve = { viewModel.deleteFromReserve(it) },
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
                isFullscreen = isFullscreen,
                onToggleFullscreen = { applyFullscreen(!isFullscreen) },
                onDropImages = { dragEvent -> importDroppedImages(dragEvent) },
                onPickLocalPhoto = { asBaseline ->
                    pickingAsBaselineRestore = asBaseline
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            )
        }
    }

    /** Imports images dropped from the Files app or another window (each becomes a reserve item). */
    private fun importDroppedImages(dragEvent: DragEvent): Boolean {
        val clip = dragEvent.clipData ?: return false
        // Cross-app content URIs are only readable while this grant is held.
        requestDragAndDropPermissions(dragEvent)?.let { dropPermissions.add(it) }
        var imported = 0
        for (i in 0 until clip.itemCount) {
            val uri = clip.getItemAt(i).uri ?: continue
            val type = contentResolver.getType(uri)
            if (type != null && !type.startsWith("image/")) continue
            viewModel.importLocalUri(uri.toString(), asBaselineRestore = false)
            imported++
        }
        return imported > 0
    }

    private fun applyFullscreen(enabled: Boolean) {
        isFullscreen = enabled
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
            "Wallpaper",
            listOf(
                KeyboardShortcutInfo("Next wallpaper", KeyEvent.KEYCODE_N, 0),
                KeyboardShortcutInfo("Previous wallpaper", KeyEvent.KEYCODE_P, 0),
                KeyboardShortcutInfo("Keep in favorites", KeyEvent.KEYCODE_K, 0),
                KeyboardShortcutInfo("Dislike and exclude (asks first)", KeyEvent.KEYCODE_FORWARD_DEL, 0),
                KeyboardShortcutInfo("Toggle fullscreen", KeyEvent.KEYCODE_F11, 0),
                KeyboardShortcutInfo("Go to Home", KeyEvent.KEYCODE_1, KeyEvent.META_CTRL_ON),
                KeyboardShortcutInfo("Go to Reserve", KeyEvent.KEYCODE_2, KeyEvent.META_CTRL_ON),
                KeyboardShortcutInfo("Go to Sources", KeyEvent.KEYCODE_3, KeyEvent.META_CTRL_ON),
                KeyboardShortcutInfo("Go to Schedule", KeyEvent.KEYCODE_4, KeyEvent.META_CTRL_ON),
                KeyboardShortcutInfo("Go to Diagnostics", KeyEvent.KEYCODE_5, KeyEvent.META_CTRL_ON),
                KeyboardShortcutInfo("Close dialog or exit fullscreen", KeyEvent.KEYCODE_ESCAPE, 0)
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
