package com.sunloom.app.ui

import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
        }
        super.onCreate(savedInstanceState)

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
                onPickLocalPhoto = { asBaseline ->
                    pickingAsBaselineRestore = asBaseline
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        displayManager?.registerDisplayListener(displayListener, mainHandler)
        notifyDisplaysChangedNow()
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::viewModel.isInitialized) {
            viewModel.onSystemUiModeChanged()
            scheduleDebouncedDisplayCheck()
        }
    }
}
