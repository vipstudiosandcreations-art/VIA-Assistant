package com.vipstudios.via.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.vipstudios.via.R

val ViaBlack = Color(0xFF050810)
val ViaNavy = Color(0xFF0B1226)
val ViaPanel = Color(0xFF111B38)
val ViaRoyal = Color(0xFF2457FF)
val ViaCyan = Color(0xFF00D4FF)
val ViaGold = Color(0xFFFFC83D)
val ViaGoldDeep = Color(0xFFB8860B)

val OrbitronFontFamily = FontFamily(
    Font(R.font.orbitron, FontWeight.Normal),
    Font(R.font.orbitron, FontWeight.SemiBold),
    Font(R.font.orbitron, FontWeight.Bold),
    Font(R.font.orbitron, FontWeight.Black)
)

val PlusJakartaFontFamily = FontFamily(
    Font(R.font.plus_jakarta_sans, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans, FontWeight.Bold)
)

val ViaTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = OrbitronFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        letterSpacing = 1.4.sp
    ),
    titleLarge = TextStyle(
        fontFamily = OrbitronFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        letterSpacing = 1.0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = PlusJakartaFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = PlusJakartaFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = PlusJakartaFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = OrbitronFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        letterSpacing = 0.8.sp
    )
)

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
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = ViaTypography,
        content = content
    )
}
