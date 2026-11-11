package com.sunloom.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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
    error = Color(0xFFF87171),
    primaryContainer = Color(0xFF0C4A6E),
    onPrimaryContainer = Color(0xFFBAE6FD),
    secondaryContainer = Color(0xFF5C3A0A),
    onSecondaryContainer = Color(0xFFFDE68A),
    onTertiary = Color(0xFF1E1B4B),
    tertiaryContainer = Color(0xFF3730A3),
    onTertiaryContainer = Color(0xFFE0E7FF),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFECACA),
    surfaceTint = Color(0xFF38BDF8),
    inverseSurface = Color(0xFFE2E8F0),
    inverseOnSurface = Color(0xFF0F172A),
    inversePrimary = Color(0xFF0369A1),
    scrim = Color(0xFF000000),
    surfaceDim = Color(0xFF0B1120),
    surfaceBright = Color(0xFF2A3650),
    surfaceContainerLowest = Color(0xFF080D19),
    surfaceContainerLow = Color(0xFF0F1729),
    surfaceContainer = Color(0xFF131C31),
    surfaceContainerHigh = Color(0xFF1A2438),
    surfaceContainerHighest = Color(0xFF222D45),
    primaryFixed = Color(0xFFBAE6FD),
    primaryFixedDim = Color(0xFF38BDF8),
    onPrimaryFixed = Color(0xFF082F49),
    onPrimaryFixedVariant = Color(0xFF0C4A6E),
    secondaryFixed = Color(0xFFFDE68A),
    secondaryFixedDim = Color(0xFFF59E0B),
    onSecondaryFixed = Color(0xFF451A03),
    onSecondaryFixedVariant = Color(0xFF78350F),
    tertiaryFixed = Color(0xFFE0E7FF),
    tertiaryFixedDim = Color(0xFF818CF8),
    onTertiaryFixed = Color(0xFF1E1B4B),
    onTertiaryFixedVariant = Color(0xFF3730A3)
)

internal val SunloomLightPalette = lightColorScheme(
    primary = Color(0xFF0369A1),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF92400E),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF4F46E5),
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE2E8F0),
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFF64748B),
    outlineVariant = Color(0xFFCBD5E1),
    error = Color(0xFFB91C1C),
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF0C4A6E),
    secondaryContainer = Color(0xFFFEF3C7),
    onSecondaryContainer = Color(0xFF78350F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE0E7FF),
    onTertiaryContainer = Color(0xFF312E81),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    surfaceTint = Color(0xFF0369A1),
    inverseSurface = Color(0xFF1E293B),
    inverseOnSurface = Color(0xFFF1F5F9),
    inversePrimary = Color(0xFF38BDF8),
    scrim = Color(0xFF000000),
    surfaceDim = Color(0xFFCBD5E1),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFF1F5F9),
    surfaceContainerHigh = Color(0xFFE9EEF5),
    surfaceContainerHighest = Color(0xFFE2E8F0),
    primaryFixed = Color(0xFFBAE6FD),
    primaryFixedDim = Color(0xFF7DD3FC),
    onPrimaryFixed = Color(0xFF082F49),
    onPrimaryFixedVariant = Color(0xFF0C4A6E),
    secondaryFixed = Color(0xFFFDE68A),
    secondaryFixedDim = Color(0xFFFCD34D),
    onSecondaryFixed = Color(0xFF451A03),
    onSecondaryFixedVariant = Color(0xFF78350F),
    tertiaryFixed = Color(0xFFE0E7FF),
    tertiaryFixedDim = Color(0xFFC7D2FE),
    onTertiaryFixed = Color(0xFF1E1B4B),
    onTertiaryFixedVariant = Color(0xFF3730A3)
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

/** The corner sizes. Components take theirs from `MaterialTheme.shapes`, so a change here changes the whole app. */
internal object SunloomShapeScale {
    val extraSmall = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val large = 16.dp
    val extraLarge = 28.dp
}

internal val SunloomShapes = Shapes(
    extraSmall = RoundedCornerShape(SunloomShapeScale.extraSmall),
    small = RoundedCornerShape(SunloomShapeScale.small),
    medium = RoundedCornerShape(SunloomShapeScale.medium),
    large = RoundedCornerShape(SunloomShapeScale.large),
    extraLarge = RoundedCornerShape(SunloomShapeScale.extraLarge)
)

/** The gaps: padding and spacing use these, so rhythm is the same on every screen. */
internal object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}
