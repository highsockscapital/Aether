package com.highsockscapital.sunshine.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.sp
import com.highsockscapital.sunshine.R
import com.highsockscapital.sunshine.data.AppLanguage
import com.highsockscapital.sunshine.data.AppThemeMode
import com.highsockscapital.sunshine.platform.LocalReduceMotion
import com.highsockscapital.sunshine.platform.rememberPlatformAccessibilityPreferences

private val LightHighContrastSunshineColors = lightColorScheme(
    primary = LightHighContrastSunshinePalette.primary,
    onPrimary = LightHighContrastSunshinePalette.onPrimary,
    primaryContainer = LightHighContrastSunshinePalette.primaryContainer,
    onPrimaryContainer = LightHighContrastSunshinePalette.onPrimaryContainer,
    secondary = LightHighContrastSunshinePalette.secondary,
    onSecondary = LightHighContrastSunshinePalette.onSecondary,
    secondaryContainer = LightHighContrastSunshinePalette.secondaryContainer,
    onSecondaryContainer = LightHighContrastSunshinePalette.onSecondaryContainer,
    background = LightHighContrastSunshinePalette.background,
    surface = LightHighContrastSunshinePalette.surface,
    surfaceVariant = LightHighContrastSunshinePalette.surfaceVariant,
    surfaceContainerHighest = LightHighContrastSunshinePalette.surfaceVariant,
    onSurface = LightHighContrastSunshinePalette.onSurface,
    onSurfaceVariant = LightHighContrastSunshinePalette.onSurfaceVariant,
    tertiary = LightHighContrastSunshinePalette.tertiary,
    error = LightHighContrastSunshinePalette.error,
    outline = LightHighContrastSunshinePalette.outline,
)

private val DarkHighContrastSunshineColors = darkColorScheme(
    primary = DarkHighContrastSunshinePalette.primary,
    onPrimary = DarkHighContrastSunshinePalette.onPrimary,
    primaryContainer = DarkHighContrastSunshinePalette.primaryContainer,
    onPrimaryContainer = DarkHighContrastSunshinePalette.onPrimaryContainer,
    secondary = DarkHighContrastSunshinePalette.secondary,
    onSecondary = DarkHighContrastSunshinePalette.onSecondary,
    secondaryContainer = DarkHighContrastSunshinePalette.secondaryContainer,
    onSecondaryContainer = DarkHighContrastSunshinePalette.onSecondaryContainer,
    background = DarkHighContrastSunshinePalette.background,
    surface = DarkHighContrastSunshinePalette.surface,
    surfaceVariant = DarkHighContrastSunshinePalette.surfaceVariant,
    surfaceContainerHighest = DarkHighContrastSunshinePalette.surfaceVariant,
    onSurface = DarkHighContrastSunshinePalette.onSurface,
    onSurfaceVariant = DarkHighContrastSunshinePalette.onSurfaceVariant,
    tertiary = DarkHighContrastSunshinePalette.tertiary,
    error = DarkHighContrastSunshinePalette.error,
    outline = DarkHighContrastSunshinePalette.outline,
)

private val LightSunshineColors = lightColorScheme(
    primary = LightSunshinePalette.primary,
    onPrimary = LightSunshinePalette.onPrimary,
    primaryContainer = LightSunshinePalette.primaryContainer,
    onPrimaryContainer = LightSunshinePalette.onPrimaryContainer,
    secondary = LightSunshinePalette.secondary,
    onSecondary = LightSunshinePalette.onSecondary,
    secondaryContainer = LightSunshinePalette.secondaryContainer,
    onSecondaryContainer = LightSunshinePalette.onSecondaryContainer,
    background = LightSunshinePalette.background,
    surface = LightSunshinePalette.surface,
    surfaceVariant = LightSunshinePalette.surfaceVariant,
    surfaceContainerHighest = LightSunshinePalette.surfaceVariant,
    onSurface = LightSunshinePalette.onSurface,
    onSurfaceVariant = LightSunshinePalette.onSurfaceVariant,
    tertiary = LightSunshinePalette.tertiary,
    error = LightSunshinePalette.error,
    outline = LightSunshinePalette.outline,
)

private val DarkSunshineColors = darkColorScheme(
    primary = DarkSunshinePalette.primary,
    onPrimary = DarkSunshinePalette.onPrimary,
    primaryContainer = DarkSunshinePalette.primaryContainer,
    onPrimaryContainer = DarkSunshinePalette.onPrimaryContainer,
    secondary = DarkSunshinePalette.secondary,
    onSecondary = DarkSunshinePalette.onSecondary,
    secondaryContainer = DarkSunshinePalette.secondaryContainer,
    onSecondaryContainer = DarkSunshinePalette.onSecondaryContainer,
    background = DarkSunshinePalette.background,
    surface = DarkSunshinePalette.surface,
    surfaceVariant = DarkSunshinePalette.surfaceVariant,
    surfaceContainerHighest = DarkSunshinePalette.surfaceVariant,
    onSurface = DarkSunshinePalette.onSurface,
    onSurfaceVariant = DarkSunshinePalette.onSurfaceVariant,
    tertiary = DarkSunshinePalette.tertiary,
    error = DarkSunshinePalette.error,
    outline = DarkSunshinePalette.outline,
)

val VazirmatnFontFamily = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_semibold, FontWeight.SemiBold),
    Font(R.font.vazirmatn_bold, FontWeight.Bold)
)

private fun getSunshineTypography(fontFamily: FontFamily) = Typography(
    headlineLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.9).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 29.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp
    ),
    titleLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 31.sp
    ),
    titleMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 25.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 28.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 24.sp
    ),
    bodySmall = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp
    )
)

@Composable
fun SunshineTheme(
    themeMode: AppThemeMode = AppThemeMode.System,
    language: AppLanguage = AppLanguage.English,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        AppThemeMode.System -> isSystemInDarkTheme()
        AppThemeMode.Light -> false
        AppThemeMode.Dark -> true
    }
    val accessibility = rememberPlatformAccessibilityPreferences()
    SideEffect {
        updateSunshinePalette(darkTheme, accessibility.increasedContrast)
    }
    val currentFontFamily = if (language == AppLanguage.Persian) {
        VazirmatnFontFamily
    } else {
        FontFamily.SansSerif
    }
    val layoutDirection = if (language == AppLanguage.Persian) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }
    val typography = remember(currentFontFamily) {
        getSunshineTypography(currentFontFamily)
    }
    CompositionLocalProvider(
        LocalLayoutDirection provides layoutDirection,
        LocalReduceMotion provides accessibility.reduceMotion,
    ) {
        MaterialTheme(
            colorScheme = when {
                darkTheme && accessibility.increasedContrast -> DarkHighContrastSunshineColors
                darkTheme -> DarkSunshineColors
                accessibility.increasedContrast -> LightHighContrastSunshineColors
                else -> LightSunshineColors
            },
            typography = typography,
            content = content
        )
    }
}
