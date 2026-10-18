package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ModernDarkColorScheme = darkColorScheme(
    primary = BrandIndigoLight,
    onPrimary = Color(0xFF281453),
    primaryContainer = Color(0xFF3F2B70),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFF7AEAF5),
    onSecondary = Color(0xFF00363E),
    secondaryContainer = Color(0xFF00505B),
    onSecondaryContainer = Color(0xFFABF4FF),
    tertiary = BrandPink,
    onTertiary = Color(0xFF4A1333),
    tertiaryContainer = Color(0xFF692A4C),
    onTertiaryContainer = Color(0xFFFFD8EA),
    background = SlateDarkBg,
    onBackground = SlateDarkTextPrimary,
    surface = SlateDarkSurface,
    onSurface = SlateDarkTextPrimary,
    surfaceVariant = SlateDarkCard,
    onSurfaceVariant = SlateDarkTextSecondary,
    outline = SlateDarkBorder,
    outlineVariant = SlateDarkBorderLight,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF881337),
    onErrorContainer = Color(0xFFFFE4E6)
)

private val ModernLightColorScheme = lightColorScheme(
    primary = BrandIndigoDark,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF251046),
    secondary = BrandSky,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFABF4FF),
    onSecondaryContainer = Color(0xFF00363E),
    tertiary = Color(0xFF8E466B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8EA),
    onTertiaryContainer = Color(0xFF3B0727),
    background = SlateLightBg,
    onBackground = SlateLightTextPrimary,
    surface = SlateLightSurface,
    onSurface = SlateLightTextPrimary,
    surfaceVariant = SlateLightCard,
    onSurfaceVariant = SlateLightTextSecondary,
    outline = SlateLightBorder,
    outlineVariant = SlateLightBorderDark,
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE4E6),
    onErrorContainer = Color(0xFF9F1239)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    amoled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) ModernDarkColorScheme.copy(
        background = if (amoled) Color.Black else Color(0xFF141318),
        surface = if (amoled) Color(0xFF08070B) else Color(0xFF141318),
        surfaceVariant = Color(0xFF25232C), outlineVariant = Color(0xFF3E3A47),
        onBackground = Color(0xFFEEEAF3), onSurface = Color(0xFFEEEAF3), onSurfaceVariant = Color(0xFFC0BBC9)
    ) else ModernLightColorScheme.copy(background = Color(0xFFFAF9FC), surface = Color(0xFFFAF9FC),
        surfaceVariant = Color(0xFFF0EEF5), onSurface = Color(0xFF22212A), onBackground = Color(0xFF22212A),
        onSurfaceVariant = Color(0xFF615D6C), outlineVariant = Color(0xFFDEDBE5))
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

