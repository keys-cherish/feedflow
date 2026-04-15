package com.feedflow.app.ui

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// ---------------------------------------------------------------------------
// Brand colors -- warm gold accent
// ---------------------------------------------------------------------------

private val GoldDark = Color(0xFFC89B63)
private val GoldLight = Color(0xFF9A6D32)
private val GoldContainer = Color(0xFF3D2E14)
private val OnGoldContainer = Color(0xFFF5E0C4)

// ---------------------------------------------------------------------------
// Color schemes
// ---------------------------------------------------------------------------

private val DarkColors = darkColorScheme(
    primary = GoldDark,
    onPrimary = Color(0xFF1E1200),
    primaryContainer = GoldContainer,
    onPrimaryContainer = OnGoldContainer,
    secondary = Color(0xFFD4BFA1),
    onSecondary = Color(0xFF3A2D16),
    secondaryContainer = Color(0xFF52432B),
    onSecondaryContainer = Color(0xFFF0DBBB),
    tertiary = Color(0xFFAAD0A4),
    onTertiary = Color(0xFF173717),
    surface = Color(0xFF1A1A1A),
    onSurface = Color(0xFFE6E1D9),
    surfaceVariant = Color(0xFF2C2C2C),
    onSurfaceVariant = Color(0xFFCEC5B5),
    outline = Color(0xFF978F81),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE6E1D9),
)

private val LightColors = lightColorScheme(
    primary = GoldLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF5E0C4),
    onPrimaryContainer = Color(0xFF2D1D00),
    secondary = Color(0xFF6E5D41),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF8E1BD),
    onSecondaryContainer = Color(0xFF261B04),
    tertiary = Color(0xFF426740),
    onTertiary = Color.White,
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF1E1B16),
    surfaceVariant = Color(0xFFF0E6D8),
    onSurfaceVariant = Color(0xFF4E4639),
    outline = Color(0xFF807668),
    background = Color(0xFFFFFBFF),
    onBackground = Color(0xFF1E1B16),
)

// ---------------------------------------------------------------------------
// Theme composable
// ---------------------------------------------------------------------------

/**
 * Applies Material 3 theming with optional dynamic colors (Android 12+).
 *
 * @param themeMode one of "light", "dark", or "system"
 * @param dynamicColor whether to use wallpaper-based dynamic colors when available
 */
@Composable
fun FeedFlowTheme(
    themeMode: String = "system",
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val useDark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    val targetScheme = when {
        // Android 12+ can derive colors from the user's wallpaper
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (useDark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        useDark -> DarkColors
        else -> LightColors
    }

    // Smooth color transition animation (400ms)
    val animSpec = tween<Color>(durationMillis = 400)
    val colorScheme = targetScheme.copy(
        primary = animateColorAsState(targetScheme.primary, animSpec).value,
        onPrimary = animateColorAsState(targetScheme.onPrimary, animSpec).value,
        primaryContainer = animateColorAsState(targetScheme.primaryContainer, animSpec).value,
        onPrimaryContainer = animateColorAsState(targetScheme.onPrimaryContainer, animSpec).value,
        secondary = animateColorAsState(targetScheme.secondary, animSpec).value,
        onSecondary = animateColorAsState(targetScheme.onSecondary, animSpec).value,
        secondaryContainer = animateColorAsState(targetScheme.secondaryContainer, animSpec).value,
        onSecondaryContainer = animateColorAsState(targetScheme.onSecondaryContainer, animSpec).value,
        tertiary = animateColorAsState(targetScheme.tertiary, animSpec).value,
        onTertiary = animateColorAsState(targetScheme.onTertiary, animSpec).value,
        surface = animateColorAsState(targetScheme.surface, animSpec).value,
        onSurface = animateColorAsState(targetScheme.onSurface, animSpec).value,
        surfaceVariant = animateColorAsState(targetScheme.surfaceVariant, animSpec).value,
        onSurfaceVariant = animateColorAsState(targetScheme.onSurfaceVariant, animSpec).value,
        outline = animateColorAsState(targetScheme.outline, animSpec).value,
        background = animateColorAsState(targetScheme.background, animSpec).value,
        onBackground = animateColorAsState(targetScheme.onBackground, animSpec).value,
    )

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
