package com.vipstudios.via.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ViaBlack = Color(0xFF050810)
val ViaNavy = Color(0xFF0B1226)
val ViaPanel = Color(0xFF111B38)
val ViaRoyal = Color(0xFF2457FF)
val ViaCyan = Color(0xFF00D4FF)
val ViaGold = Color(0xFFFFC83D)
val ViaGoldDeep = Color(0xFFB8860B)

private val DarkScheme = darkColorScheme(
    primary = ViaGold, onPrimary = Color.Black,
    secondary = ViaCyan, onSecondary = Color.Black,
    tertiary = ViaRoyal, onTertiary = Color.White,
    background = ViaBlack, onBackground = Color(0xFFEAF2FF),
    surface = ViaNavy, onSurface = Color(0xFFEAF2FF),
    surfaceVariant = ViaPanel, onSurfaceVariant = Color(0xFFB8C7E6),
    outline = Color(0xFF2B3A66), error = Color(0xFFFF6B6B)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1A3FD0), onPrimary = Color.White,
    secondary = Color(0xFF0089B3), onSecondary = Color.White,
    tertiary = ViaGoldDeep, onTertiary = Color.White,
    background = Color(0xFFF6F8FF), onBackground = Color(0xFF0A1230),
    surface = Color.White, onSurface = Color(0xFF0A1230),
    surfaceVariant = Color(0xFFE6ECFF), onSurfaceVariant = Color(0xFF3A4A78),
    outline = Color(0xFF9AA8D6), error = Color(0xFFB3261E)
)

/** mode: "dark" (default brand look), "light" or "system" */
@Composable
fun ViaTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
}
