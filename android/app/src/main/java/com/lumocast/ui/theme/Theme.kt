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
    onPrimary = Color(0xFF14172B),
    primaryContainer = LumoIndigo,
    onPrimaryContainer = Color(0xFFF4F6FB),
    secondary = LumoMint,
    onSecondary = Color(0xFF12241F),
    tertiary = LumoAmber,
    onTertiary = Color(0xFF2A2013),
    background = Ink900,
    onBackground = TextPrimary,
    surface = Ink800,
    onSurface = TextPrimary,
    surfaceVariant = Ink700,
    onSurfaceVariant = TextSecondary,
    outline = Color(0xFF4A5163),
    outlineVariant = Color(0xFF3A414F),
    error = LumoRed,
    onError = Color(0xFF2B1416)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF5A63D8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E8FF),
    onPrimaryContainer = Color(0xFF232A6B),
    secondary = Color(0xFF1D9E85),
    onSecondary = Color.White,
    tertiary = Color(0xFFB0761F),
    background = LightBg,
    onBackground = LightText,
    surface = LightSurface,
    onSurface = LightText,
    surfaceVariant = Color(0xFFEBEEF7),
    onSurfaceVariant = Color(0xFF4F5669),
    outline = Color(0xFFCBD1E0),
    outlineVariant = Color(0xFFDFE3EE)
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
        @Composable get() = if (IsDark) Color(0xFF2E3441) else Color.White
    val CardBorder: Color
        @Composable get() = if (IsDark) Color.White.copy(alpha = 0.10f) else Color(0xFFE2E6F0)
    /** 三级文字：深色下达 5.5:1 对比度，浅色下 4.6:1 —— 小字也看得清。 */
    val Subtle: Color
        @Composable get() = if (IsDark) TextTertiary else Color(0xFF656D82)
    val Gradient: List<Color>
        @Composable get() = listOf(LumoViolet, LumoBlue)
    val GradientMint: List<Color>
        @Composable get() = listOf(LumoMint, Color(0xFF7FC6D8))
}
