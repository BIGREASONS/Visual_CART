package com.paypal.visualcart.core.designsystem

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = ForestGreen,
    onPrimary = PureWhite,
    primaryContainer = ForestGreenLight,
    onPrimaryContainer = ForestGreenDark,
    secondary = MutedSage,
    onSecondary = DeepInk,
    secondaryContainer = SageLight,
    onSecondaryContainer = DeepInk,
    tertiary = Terracotta,
    onTertiary = PureWhite,
    tertiaryContainer = TerracottaLight,
    onTertiaryContainer = TerracottaDark,
    background = WarmIvory,
    onBackground = DeepInk,
    surface = PureWhite,
    onSurface = DeepInk,
    surfaceVariant = StoneLight,
    onSurfaceVariant = MutedSlate,
    outline = Stone,
    outlineVariant = StoneLight
)

private val DarkColorScheme = darkColorScheme(
    primary = ForestGreenBright,
    onPrimary = DeepInkSurface,
    primaryContainer = ForestGreenDark,
    onPrimaryContainer = WarmIvory,
    secondary = MutedSage,
    onSecondary = DeepInkSurface,
    secondaryContainer = DeepInkElevated,
    onSecondaryContainer = WarmIvory,
    tertiary = TerracottaBright,
    onTertiary = DeepInkSurface,
    tertiaryContainer = TerracottaDark,
    onTertiaryContainer = WarmIvory,
    background = DeepInkSurface,
    onBackground = WarmIvory,
    surface = DeepInkCard,
    onSurface = WarmIvory,
    surfaceVariant = DeepInkElevated,
    onSurfaceVariant = MutedSage,
    outline = DeepInkBorder,
    outlineVariant = DeepInkCard
)

@Composable
fun VisualCartTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = colorScheme.background.toArgb()
                window.navigationBarColor = colorScheme.background.toArgb()
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = VisualCartTypography,
        shapes = VisualCartShapes,
        content = content
    )
}
