package com.sunloom.app.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** The two colour schemes. Contrast of text on these is checked by `AccessibilityTest`. */
internal val SunloomDarkPalette = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF082F49),
    secondary = Color(0xFFF59E0B),
    onSecondary = Color(0xFF451A03),
    tertiary = Color(0xFF818CF8),
    background = Color(0xFF0B1120),
    surface = Color(0xFF131C31),
    surfaceVariant = Color(0xFF1E293B),
    onBackground = Color(0xFFF1F5F9),
    onSurface = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF64748B),
    outlineVariant = Color(0xFF334155),
    error = Color(0xFFF87171)
)

internal val SunloomLightPalette = lightColorScheme(
    primary = Color(0xFF0369A1),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFFD97706),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF4F46E5),
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE2E8F0),
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFFCBD5E1),
    error = Color(0xFFDC2626)
)

/** Colours that are not part of a Material scheme because they mean the same thing in light and dark. */
internal object SunloomColors {
    /** The filled heart of a favourite. */
    val Favorite = Color(0xFFF43F5E)

    /** What a preview shows until its image has decoded: a dark or a light sky, matching the wallpaper's class. */
    val PreviewFallbackDark: Brush =
        Brush.verticalGradient(listOf(Color(0xFF0B1120), Color(0xFF1E1B4B), Color(0xFF0F172A)))
    val PreviewFallbackLight: Brush =
        Brush.verticalGradient(listOf(Color(0xFFBAE6FD), Color(0xFFE0F2FE), Color(0xFFFDE68A)))

    /** The legibility overlay: system bars and dock, the clock zone, and the side icon column. */
    val ZoneBars = Color(0x5538BDF8)
    val ZoneClock = Color(0x55F59E0B)
    val ZoneSide = Color(0x44818CF8)
    val ZoneCaptionBackdrop = Color(0xAA0F172A)
}
