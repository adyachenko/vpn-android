package com.sbcfg.manager.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Palette from Stitch design "The Kinetic Void"
val SbBackground = Color(0xFF0C0E11)
val SbSurface = Color(0xFF0C0E11)
val SbSurfaceContainer = Color(0xFF171A1D)
val SbSurfaceContainerHigh = Color(0xFF1D2024)
val SbSurfaceContainerHighest = Color(0xFF23262A)
val SbSurfaceVariant = Color(0xFF23262A)
val SbOnSurface = Color(0xFFF9F9FD)
val SbOnSurfaceVariant = Color(0xFFAAABAF)
val SbOutline = Color(0xFF747579)
val SbOutlineVariant = Color(0xFF46484B)

val SbPrimary = Color(0xFF91F78E)
val SbOnPrimary = Color(0xFF002A06)
val SbPrimaryContainer = Color(0xFF52B555)
val SbOnPrimaryContainer = Color(0xFF002A06)

val SbSecondary = Color(0xFF86FAAC)
val SbOnSecondary = Color(0xFF005F32)
val SbSecondaryContainer = Color(0xFF006D3A)
val SbOnSecondaryContainer = Color(0xFFE3FFE6)

val SbTertiary = Color(0xFF90F1FF)
val SbOnTertiary = Color(0xFF005B64)

val SbError = Color(0xFFFF7351)
val SbOnError = Color(0xFF450900)
val SbErrorContainer = Color(0xFFB92902)
val SbOnErrorContainer = Color(0xFFFFD2C8)

private val SbDarkColorScheme = darkColorScheme(
    primary = SbPrimary,
    onPrimary = SbOnPrimary,
    primaryContainer = SbPrimaryContainer,
    onPrimaryContainer = SbOnPrimaryContainer,
    secondary = SbSecondary,
    onSecondary = SbOnSecondary,
    secondaryContainer = SbSecondaryContainer,
    onSecondaryContainer = SbOnSecondaryContainer,
    tertiary = SbTertiary,
    onTertiary = SbOnTertiary,
    error = SbError,
    onError = SbOnError,
    errorContainer = SbErrorContainer,
    onErrorContainer = SbOnErrorContainer,
    background = SbBackground,
    onBackground = SbOnSurface,
    surface = SbSurface,
    onSurface = SbOnSurface,
    surfaceVariant = SbSurfaceVariant,
    onSurfaceVariant = SbOnSurfaceVariant,
    surfaceContainer = SbSurfaceContainer,
    surfaceContainerHigh = SbSurfaceContainerHigh,
    surfaceContainerHighest = SbSurfaceContainerHighest,
    outline = SbOutline,
    outlineVariant = SbOutlineVariant,
)

@Composable
fun SbcfgTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = SbDarkColorScheme,
        content = content
    )
}
