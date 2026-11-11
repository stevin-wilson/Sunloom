package com.sunloom.app.domain.model

/**
 * What a Follow-OS poll last verified: the theme and the wallpaper on screen, or "the person chose the system default".
 * Kept as one short string so the poll can compare it without reading the library.
 */
object FollowOsVerification {
    /** Stands in for a wallpaper id when the system's own wallpaper is the one on screen. */
    const val SYSTEM_DEFAULT_MARK = "@system"

    fun key(target: BrightnessClass, currentId: String?): String = "${target.name}:${currentId ?: SYSTEM_DEFAULT_MARK}"
}
