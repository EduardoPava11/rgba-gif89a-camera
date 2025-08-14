package com.rgbagif.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = ProcessingOrange,
    onPrimary = NeutralDark,
    primaryContainer = ProcessingOrange.copy(alpha = 0.3f),
    onPrimaryContainer = ProcessingOrange,
    
    secondary = MatrixGreen,
    onSecondary = NeutralDark,
    secondaryContainer = MatrixGreen.copy(alpha = 0.3f),
    onSecondaryContainer = MatrixGreen,
    
    tertiary = InfoBlue,
    onTertiary = NeutralLight,
    tertiaryContainer = InfoBlue.copy(alpha = 0.3f),
    onTertiaryContainer = InfoBlue,
    
    error = ErrorRed,
    onError = NeutralLight,
    errorContainer = ErrorRed.copy(alpha = 0.3f),
    onErrorContainer = ErrorRed,
    
    background = NeutralDark,
    onBackground = NeutralLight,
    
    surface = NeutralDark,
    onSurface = NeutralLight,
    surfaceVariant = NeutralMid.copy(alpha = 0.3f),
    onSurfaceVariant = NeutralLight,
    
    outline = NeutralMid,
    outlineVariant = NeutralMid.copy(alpha = 0.5f),
    
    scrim = NeutralDark.copy(alpha = 0.8f)
)

private val LightColorScheme = lightColorScheme(
    primary = ProcessingOrange,
    onPrimary = NeutralLight,
    primaryContainer = ProcessingOrange.copy(alpha = 0.2f),
    onPrimaryContainer = ProcessingOrange,
    
    secondary = MatrixGreen,
    onSecondary = NeutralLight,
    secondaryContainer = MatrixGreen.copy(alpha = 0.2f),
    onSecondaryContainer = MatrixGreen,
    
    tertiary = InfoBlue,
    onTertiary = NeutralLight,
    tertiaryContainer = InfoBlue.copy(alpha = 0.2f),
    onTertiaryContainer = InfoBlue,
    
    error = ErrorRed,
    onError = NeutralLight,
    errorContainer = ErrorRed.copy(alpha = 0.2f),
    onErrorContainer = ErrorRed,
    
    background = NeutralDarkLight,
    onBackground = NeutralLightDark,
    
    surface = NeutralDarkLight,
    onSurface = NeutralLightDark,
    surfaceVariant = NeutralMidLight.copy(alpha = 0.3f),
    onSurfaceVariant = NeutralLightDark,
    
    outline = NeutralMidLight,
    outlineVariant = NeutralMidLight.copy(alpha = 0.5f),
    
    scrim = NeutralLightDark.copy(alpha = 0.8f)
)

@Composable
fun RGBAGif89aTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is disabled - we use our functional color system
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = SquareShapes,
        content = content
    )
}