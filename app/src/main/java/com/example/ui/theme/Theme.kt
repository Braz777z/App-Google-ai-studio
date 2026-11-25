package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.data.ThemePreference

private val StudioDarkColorScheme = darkColorScheme(
    primary = DarkNeutralAccent,
    onPrimary = Color(0xFF111113),
    primaryContainer = Color(0xFF2C2C30),
    onPrimaryContainer = DarkTextPrimary,
    secondary = DarkNeutralAccentSoft,
    onSecondary = Color(0xFF141416),
    secondaryContainer = Color(0xFF252528),
    onSecondaryContainer = DarkNeutralAccent,
    tertiary = Color(0xFFD4D4D8),
    background = DarkBlackBg,
    onBackground = DarkTextPrimary,
    surface = DarkCharcoalSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkCharcoalContainer,
    onSurfaceVariant = DarkTextSecondary,
    surfaceContainerLow = DarkCharcoalSurface,
    surfaceContainer = DarkCharcoalContainer,
    surfaceContainerHigh = DarkCharcoalHigh,
    outline = DarkCharcoalOutline,
    outlineVariant = DarkCharcoalDivider,
    error = StudioRose,
    onError = Color.White
)

private val StudioLightColorScheme = lightColorScheme(
    primary = LightAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E4E7),
    onPrimaryContainer = PaperTextPrimary,
    secondary = LightAccentSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF4F4F5),
    onSecondaryContainer = PaperTextPrimary,
    tertiary = Color(0xFF3F3F46),
    background = PaperBg,
    onBackground = PaperTextPrimary,
    surface = PaperSurface,
    onSurface = PaperTextPrimary,
    surfaceVariant = PaperSurfaceContainer,
    onSurfaceVariant = PaperTextSecondary,
    surfaceContainerLow = PaperSurface,
    surfaceContainer = PaperSurfaceContainer,
    surfaceContainerHigh = PaperSurfaceHigh,
    outline = PaperOutline,
    outlineVariant = PaperDivider,
    error = Color(0xFFE11D48),
    onError = Color.White
)

@Composable
fun StudioPortalTheme(
    themePreference: ThemePreference = ThemePreference.SYSTEM,
    content: @Composable () -> Unit
) {
    val isDark = when (themePreference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.DARK -> true
        ThemePreference.LIGHT -> false
    }

    val colorScheme = if (isDark) StudioDarkColorScheme else StudioLightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !isDark
                insetsController.isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = StudioTypography,
        content = content
    )
}
