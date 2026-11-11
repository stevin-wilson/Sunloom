package com.sunloom.app.diagnostics

import android.os.Build
import com.sunloom.app.BuildConfig

/** Which build this is and what it runs on; the opening lines of every crash entry and diagnostics report. */
data class AppInfo(
    val versionName: String,
    val versionCode: Int,
    val flavor: String,
    val sdkInt: Int,
    val deviceModel: String
) {
    /** "1.7.0 (13) github" */
    val buildLabel: String get() = "$versionName ($versionCode) $flavor"

    companion object {
        fun current() = AppInfo(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            flavor = BuildConfig.FLAVOR,
            sdkInt = Build.VERSION.SDK_INT,
            deviceModel = Build.MODEL.orEmpty()
        )
    }
}
