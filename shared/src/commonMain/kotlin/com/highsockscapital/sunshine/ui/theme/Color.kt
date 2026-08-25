package com.highsockscapital.sunshine.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

data class SunshinePalette(
    val background: Color,
    val backgroundGradientTop: Color,
    val settingsBackground: Color,
    val sidebarBackground: Color,
    val sidebarControl: Color,
    val settingsIcon: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val surfaceHigher: Color,
    val surfaceVariant: Color,
    val outline: Color,
    val outlineSoft: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val error: Color,
    val messageBubble: Color,
    val scrim: Color,
)

val LightSunshinePalette = SunshinePalette(
    background = Color(0xFFF6F3E7),
    backgroundGradientTop = Color(0xFFF6F3E7),
    settingsBackground = Color(0xFFF6F3E7),
    sidebarBackground = Color(0xFFFBF9F0),
    sidebarControl = Color(0xFFF3F0E4),
    settingsIcon = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFF3F3F2),
    surfaceHigher = Color(0xFFECECEC),
    surfaceVariant = Color(0xFFE5E5E5),
    outline = Color(0xFF161610),
    outlineSoft = Color(0xFFE3DFD0),
    onSurface = Color(0xFF161610),
    onSurfaceVariant = Color(0xFF5F5B4E),
    primary = Color(0xFFFF9E43),
    onPrimary = Color(0xFF3D2400),
    primaryContainer = Color(0xFFFFE3C2),
    onPrimaryContainer = Color(0xFF6B3D00),
    secondary = Color(0xFF4A7B6B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDF1E8),
    onSecondaryContainer = Color(0xFF1E4A3B),
    tertiary = Color(0xFFFFB866),
    error = Color(0xFFB43E3E),
    messageBubble = Color(0xFFFFE8CF),
    scrim = Color(0x22000000),
)

val DarkSunshinePalette = SunshinePalette(
    background = Color(0xFF151619),
    backgroundGradientTop = Color(0xFF1B1D22),
    settingsBackground = Color(0xFF151619),
    sidebarBackground = Color(0xFF1C1F23),
    sidebarControl = Color(0xFF24282D),
    settingsIcon = Color(0xFFF3F1EC),
    surface = Color(0xFF1C1F23),
    surfaceHigh = Color(0xFF24282D),
    surfaceHigher = Color(0xFF2C3036),
    surfaceVariant = Color(0xFF343941),
    outline = Color(0xFF4A5059),
    outlineSoft = Color(0xFF3D424A),
    onSurface = Color(0xFFF3F1EC),
    onSurfaceVariant = Color(0xFFB9B4AA),
    primary = Color(0xFFFFAE56),
    onPrimary = Color(0xFF3D2400),
    primaryContainer = Color(0xFF5C3A10),
    onPrimaryContainer = Color(0xFFFFE3C2),
    secondary = Color(0xFF89C8AF),
    onSecondary = Color(0xFF143126),
    secondaryContainer = Color(0xFF24483A),
    onSecondaryContainer = Color(0xFFDDF6EA),
    tertiary = Color(0xFFFFC98A),
    error = Color(0xFFFF8E8E),
    messageBubble = Color(0xFF4A3418),
    scrim = Color(0x66000000),
)

val LightHighContrastSunshinePalette = LightSunshinePalette.copy(
    outline = Color(0xFFA39E95),
    outlineSoft = Color(0xFFBBB6AC),
    onSurface = Color(0xFF111214),
    onSurfaceVariant = Color(0xFF514D46),
    primary = Color(0xFFB35F00),
    primaryContainer = Color(0xFFFFD9AD),
    onPrimaryContainer = Color(0xFF4A2900),
    error = Color(0xFFB43E3E),
)

val DarkHighContrastSunshinePalette = DarkSunshinePalette.copy(
    outline = Color(0xFF777F89),
    outlineSoft = Color(0xFF656C75),
    onSurface = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFFD2CCC1),
    primary = Color(0xFFFFD1A3),
    primaryContainer = Color(0xFF5C3A10),
    onPrimaryContainer = Color(0xFFFFE3C2),
    error = Color(0xFFFFB0B0),
)

private var currentPalette by mutableStateOf(LightSunshinePalette)

fun updateSunshinePalette(darkTheme: Boolean, increasedContrast: Boolean = false) {
    val palette = when {
        darkTheme && increasedContrast -> DarkHighContrastSunshinePalette
        darkTheme -> DarkSunshinePalette
        increasedContrast -> LightHighContrastSunshinePalette
        else -> LightSunshinePalette
    }
    if (currentPalette != palette) {
        currentPalette = palette
    }
}

val SunshineBackground: Color
    get() = currentPalette.background

val SunshineBackgroundGradientTop: Color
    get() = currentPalette.backgroundGradientTop

val SunshineSettingsBackground: Color
    get() = currentPalette.settingsBackground

val SunshineSidebarBackground: Color
    get() = currentPalette.sidebarBackground

val SunshineSidebarControl: Color
    get() = currentPalette.sidebarControl

val SunshineSettingsIcon: Color
    get() = currentPalette.settingsIcon

val SunshineSurface: Color
    get() = currentPalette.surface

val SunshineSurfaceHigh: Color
    get() = currentPalette.surfaceHigh

val SunshineSurfaceHigher: Color
    get() = currentPalette.surfaceHigher

val SunshineSurfaceVariant: Color
    get() = currentPalette.surfaceVariant

val SunshineOutline: Color
    get() = currentPalette.outline

val SunshineOutlineSoft: Color
    get() = currentPalette.outlineSoft

val SunshineOnSurface: Color
    get() = currentPalette.onSurface

val SunshineOnSurfaceVariant: Color
    get() = currentPalette.onSurfaceVariant

val SunshinePrimary: Color
    get() = currentPalette.primary

val SunshineOnPrimary: Color
    get() = currentPalette.onPrimary

val SunshinePrimaryContainer: Color
    get() = currentPalette.primaryContainer

val SunshineOnPrimaryContainer: Color
    get() = currentPalette.onPrimaryContainer

val SunshineSecondary: Color
    get() = currentPalette.secondary

val SunshineOnSecondary: Color
    get() = currentPalette.onSecondary

val SunshineSecondaryContainer: Color
    get() = currentPalette.secondaryContainer

val SunshineOnSecondaryContainer: Color
    get() = currentPalette.onSecondaryContainer

val SunshineTertiary: Color
    get() = currentPalette.tertiary

val SunshineError: Color
    get() = currentPalette.error

val SunshineMessageBubble: Color
    get() = currentPalette.messageBubble

val SunshineScrim: Color
    get() = currentPalette.scrim
