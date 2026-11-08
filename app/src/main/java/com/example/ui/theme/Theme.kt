package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
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
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) ModernDarkColorScheme.copy(
        background = Color.Black,
        primary = Color(0xFFA5BFFF), onPrimary = Color(0xFF082758),
        primaryContainer = Color(0xFF142649), onPrimaryContainer = Color(0xFFD8E5FF),
        secondary = Color(0xFFC9B6FF), secondaryContainer = Color(0xFF282040), onSecondaryContainer = Color(0xFFEADDFF),
        surface = Color(0xFF0B0C10), surfaceVariant = Color(0xFF171A22),
        surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF0B0C10),
        surfaceContainer = Color(0xFF11131A), surfaceContainerHigh = Color(0xFF171A22), surfaceContainerHighest = Color(0xFF20232D),
        outline = Color(0xFF394150), outlineVariant = Color(0xFF252B36),
        onBackground = Color(0xFFF3F5FB), onSurface = Color(0xFFF3F5FB), onSurfaceVariant = Color(0xFFAAB2C2)
    ) else ModernLightColorScheme.copy(
        primary = Color(0xFF1859EF), onPrimary = Color.White,
        primaryContainer = Color(0xFFEAF0FF), onPrimaryContainer = Color(0xFF14316B),
        secondary = Color(0xFF7353CE), secondaryContainer = Color(0xFFF0EBFF), onSecondaryContainer = Color(0xFF352258),
        background = Color(0xFFF7F8FC), surface = Color.White,
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White,
        surfaceContainer = Color(0xFFF4F6FB), surfaceContainerHigh = Color(0xFFEEF1F7), surfaceContainerHighest = Color(0xFFE5EAF4),
        surfaceVariant = Color(0xFFF0F3FA), onSurface = Color(0xFF131B2B), onBackground = Color(0xFF131B2B),
        onSurfaceVariant = Color(0xFF606C80), outline = Color(0xFFB4BECE), outlineVariant = Color(0xFFE1E6EF))
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes(extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp)),
        content = content
    )
}

