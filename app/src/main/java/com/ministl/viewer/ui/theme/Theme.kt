package com.ministl.viewer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val BambuDarkColorScheme = darkColorScheme(
    primary = BambuGreenPrimary,
    onPrimary = Color(0xFF041F0C),
    primaryContainer = BambuGreenDim,
    onPrimaryContainer = BambuGreenBright,
    secondary = BambuGreenBright,
    onSecondary = Color(0xFF041F0C),
    secondaryContainer = BambuSlateElevated,
    onSecondaryContainer = BambuTextPrimary,
    tertiary = AxisZBlue,
    onTertiary = Color.White,
    background = BambuGraphiteBg,
    onBackground = BambuTextPrimary,
    surface = BambuSlateSurface,
    onSurface = BambuTextPrimary,
    surfaceVariant = BambuSlateElevated,
    onSurfaceVariant = BambuTextSecondary,
    outline = BambuBorderSubtle,
    outlineVariant = Color(0xFF282B33),
    error = OverhangAlertRed,
    onError = Color.White,
    errorContainer = Color(0xFF421210),
    onErrorContainer = Color(0xFFFFB4AB)
)

// Tipografía compacta y técnica inspirada en Bambu Studio (valores métricos con números tabulares)
val BambuTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.2).sp,
        color = BambuTextPrimary
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        color = BambuTextPrimary
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.4.sp,
        fontFeatureSettings = "tnum",
        color = BambuTextPrimary
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = BambuTextPrimary
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = BambuTextSecondary
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = "tnum",
        color = BambuTextSecondary
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
        color = BambuTextPrimary
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp,
        fontFeatureSettings = "tnum",
        color = BambuTextSecondary
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.6.sp,
        fontFeatureSettings = "tnum",
        color = BambuTextMuted
    )
)

@Composable
fun MiniSTLTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = BambuDarkColorScheme,
        typography = BambuTypography,
        content = content
    )
}
