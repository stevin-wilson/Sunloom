package com.sunloom.app.ui

import androidx.compose.runtime.compositionLocalOf
import java.time.ZoneId

/**
 * First-run setup is a short walk (Article XII §1, Hick's law and the goal gradient): four steps, one group of decisions
 * each, with a visible "step N of 4" and a way back. Everything has a default, so a person who wants none of it can finish
 * from the first step.
 */
enum class SetupStep {
    /** What Sunloom does and how, in plain words; "Start with defaults" or "Customize". */
    WELCOME,

    /** Whether and when the wallpaper changes. */
    SCHEDULE,

    /** What it shows and where. */
    CONTENT,

    /** A preview, and the last choices before starting. */
    FINISH;

    val number: Int get() = ordinal + 1
    val progress: Float get() = number.toFloat() / entries.size
    val isFirst: Boolean get() = ordinal == 0
    val isLast: Boolean get() = ordinal == entries.lastIndex

    fun next(): SetupStep? = entries.getOrNull(ordinal + 1)
    fun back(): SetupStep? = entries.getOrNull(ordinal - 1)
}

/** The device's time zone, for the first guess at a city; tests provide another. */
val LocalDeviceZone = compositionLocalOf { ZoneId.systemDefault() }
