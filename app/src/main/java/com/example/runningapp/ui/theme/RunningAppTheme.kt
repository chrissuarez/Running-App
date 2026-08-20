package com.example.runningapp.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object RunningUiTokens {
    val MinTouchTarget = 48.dp
    val PagePadding = 16.dp
    val SectionSpacing = 12.dp
    val CardPadding = 14.dp
    /**
     * The medal disc on a Run's page, and the gap a row with no medal leaves in its place.
     *
     * One number rather than two literals, because the achievements card draws the disc and the
     * Segments card draws the hole beside it: two figures free to disagree would step the names in
     * and out down the page, which is exactly what the hole is there to prevent.
     */
    val MedalDiscSize = 28.dp
}

private val DaylightHighContrastColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFFFA000),
    onPrimary = Color(0xFF101010),
    primaryContainer = Color(0xFF4A3500),
    onPrimaryContainer = Color(0xFFFFE0A3),
    secondary = Color(0xFF7ED3FF),
    onSecondary = Color(0xFF001723),
    secondaryContainer = Color(0xFF0F3141),
    onSecondaryContainer = Color(0xFFD1EEFF),
    tertiary = Color(0xFF98E892),
    onTertiary = Color(0xFF052107),
    tertiaryContainer = Color(0xFF1C3A1A),
    onTertiaryContainer = Color(0xFFC4F7C0),
    error = Color(0xFFFF7A7A),
    onError = Color(0xFF2C0000),
    errorContainer = Color(0xFF5E1010),
    onErrorContainer = Color(0xFFFFDADA),
    background = Color(0xFF111418),
    onBackground = Color(0xFFF2F5F8),
    surface = Color(0xFF1A1E24),
    onSurface = Color(0xFFF2F5F8),
    surfaceVariant = Color(0xFF2A313A),
    onSurfaceVariant = Color(0xFFD7DEE7),
    outline = Color(0xFF95A4B5)
)

private val RunningTypography = Typography().copy(
    displayLarge = TextStyle(fontSize = 56.sp, lineHeight = 60.sp, fontWeight = FontWeight.ExtraBold),
    headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)
)

@Composable
fun RunningAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DaylightHighContrastColorScheme,
        typography = RunningTypography,
        content = content
    )
}
