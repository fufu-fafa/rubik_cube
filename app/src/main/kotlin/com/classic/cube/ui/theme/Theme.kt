package com.classic.cube.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colors for the raised keys and cards, separate from Material roles. [track] is the well behind a
 * segmented control and [trackSelected] its picked segment.
 */
@Immutable
data class KeyPalette(
    val key: Color,
    val keyShadow: Color,
    val gold: Color,
    val track: Color,
    val trackSelected: Color,
)

private val LightKeys = KeyPalette(
    key = Color(0xFFFFFFFF),
    keyShadow = Color(0x1A1E2A3D),
    gold = Color(0xFFB7791F),
    track = Color(0xFFE6EAF1),
    trackSelected = Color(0xFFFFFFFF),
)
private val DarkKeys = KeyPalette(
    key = Color(0xFF1E212B),
    keyShadow = Color(0x33000000),
    gold = Color(0xFFF2C14E),
    track = Color(0xFF1E212B),
    trackSelected = Color(0xFF343A4C),
)

val LocalKeys = staticCompositionLocalOf { LightKeys }

private val LightScheme = lightColorScheme(
    primary = Color(0xFF2457D6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF0E2A73),
    secondary = Color(0xFF5F6675),
    background = Color(0xFFF4F6FA),
    onBackground = Color(0xFF1E222B),
    surface = Color(0xFFF4F6FA),
    onSurface = Color(0xFF1E222B),
    surfaceVariant = Color(0xFFE6EAF1),
    onSurfaceVariant = Color(0xFF5F6675),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF7F8FB),
    error = Color(0xFFE0393E),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8BA6FA),
    onPrimary = Color(0xFF101A3C),
    primaryContainer = Color(0xFF2A3F73),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFFA2AABA),
    background = Color(0xFF12141C),
    onBackground = Color(0xFFF2F4F8),
    surface = Color(0xFF12141C),
    onSurface = Color(0xFFF2F4F8),
    surfaceVariant = Color(0xFF242A37),
    onSurfaceVariant = Color(0xFFA3A9B8),
    surfaceContainerLow = Color(0xFF1B202B),
    surfaceContainer = Color(0xFF1B202B),
    surfaceContainerHigh = Color(0xFF212734),
    error = Color(0xFFFF6B6F),
)

@Composable
fun CubeTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalKeys provides if (dark) DarkKeys else LightKeys) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}
