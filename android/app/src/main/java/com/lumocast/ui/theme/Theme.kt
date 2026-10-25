package com.lumocast.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkScheme = darkColorScheme(
    primary = LumoViolet,
    onPrimary = Color.White,
    primaryContainer = LumoIndigo,
    secondary = LumoMint,
    onSecondary = Ink900,
    tertiary = LumoAmber,
    background = Ink900,
    onBackground = TextPrimary,
    surface = Ink800,
    onSurface = TextPrimary,
    surfaceVariant = Ink700,
    onSurfaceVariant = TextSecondary,
    outline = Color(0xFF2C3142),
    error = LumoRed
)

private val LightScheme = lightColorScheme(
    primary = LumoIndigo,
    onPrimary = Color.White,
    secondary = Color(0xFF00A693),
    background = LightBg,
    onBackground = LightText,
    surface = LightSurface,
    onSurface = LightText,
    surfaceVariant = Color(0xFFEDEFF7),
    onSurfaceVariant = Color(0xFF5B6076),
    outline = Color(0xFFD8DCEA)
)

@Composable
fun LumoTheme(
    darkTheme: Boolean = isSystemInDarkTheme().not(),
    content: @Composable () -> Unit
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = scheme, typography = LumoTypography, content = content)
}

/** 供配色不来自 Material 的自定义组件使用。 */
object Lumo {
    val IsDark: Boolean
        @Composable get() = MaterialTheme.colorScheme.background != LightBg
    val CardBg: Color
        @Composable get() = if (IsDark) Color.White.copy(alpha = 0.055f) else Color.White
    val CardBorder: Color
        @Composable get() = if (IsDark) Color.White.copy(alpha = 0.09f) else Color(0xFFE6E9F3)
    val Subtle: Color
        @Composable get() = if (IsDark) TextTertiary else Color(0xFF8A90A6)
    val Gradient: List<Color>
        @Composable get() = listOf(LumoViolet, LumoBlue)
    val GradientMint: List<Color>
        @Composable get() = listOf(LumoMint, LumoBlue)
}
