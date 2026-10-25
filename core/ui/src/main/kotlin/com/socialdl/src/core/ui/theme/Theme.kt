package com.socialdl.src.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = SocialDLAccent,
    onPrimary = SocialDLOnSurface,
    primaryContainer = SocialDLAccentDark,
    onPrimaryContainer = SocialDLOnSurface,
    secondary = SocialDLButton,
    onSecondary = SocialDLOnSurface,
    secondaryContainer = SocialDLActivePill,
    onSecondaryContainer = SocialDLOnSurface,
    tertiary = SocialDLIndicator,
    onTertiary = SocialDLBg,
    background = SocialDLBg,
    onBackground = SocialDLOnSurface,
    surface = SocialDLSurface,
    onSurface = SocialDLOnSurface,
    surfaceVariant = SocialDLCard,
    onSurfaceVariant = SocialDLOnSurfaceVariant,
    surfaceContainer = SocialDLSurfaceContainerLow,
    surfaceContainerHigh = SocialDLSurfaceContainerHigh,
    error = SocialDLError,
    onError = SocialDLOnSurface,
    outline = SocialDLOnSurfaceDim,
)

val SocialDLShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),       // Card radius
    extraLarge = RoundedCornerShape(32.dp)   // Sheet radius
)

/**
 * SocialDL Theme matching the Material 3 mobile downloader mock.
 * Always renders in dark palette with custom tokens.
 */
@Composable
fun SocialDLTheme(
    content: @Composable () -> Unit
) {
    // We enforce our sleek dark palette as the default per spec §1 & §2.2
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = SocialDLTypography,
        shapes = SocialDLShapes,
        content = content
    )
}
