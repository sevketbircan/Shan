package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class ShanExtendedColors(
    val outgoingBubble: Color,
    val incomingBubble: Color,
    val border: Color,
    val accentCyan: Color,
    val accentBronze: Color,
    val emeraldGlow: Color,
    val isDark: Boolean
)

val LocalShanColors = staticCompositionLocalOf {
    ShanExtendedColors(
        outgoingBubble = ShanOutgoingBubbleDark,
        incomingBubble = ShanIncomingBubbleDark,
        border = ShanBorderDark,
        accentCyan = ShanCyan,
        accentBronze = ShanBronze,
        emeraldGlow = ShanEmerald,
        isDark = true
    )
}

private val ShanDarkColorScheme = darkColorScheme(
    primary = ShanEmerald,
    onPrimary = ShanOnEmerald,
    primaryContainer = ShanOutgoingBubbleDark,
    onPrimaryContainer = ShanTextPrimaryDark,
    secondary = ShanCyan,
    onSecondary = Color(0xFF041C2B),
    tertiary = ShanBronze,
    background = ShanBgDark,
    onBackground = ShanTextPrimaryDark,
    surface = ShanSurfaceDark,
    onSurface = ShanTextPrimaryDark,
    surfaceVariant = ShanSurfaceElevatedDark,
    onSurfaceVariant = ShanTextSecondaryDark,
    outline = ShanBorderDark,
    error = ShanDanger,
    onError = Color.White
)

private val ShanLightColorScheme = lightColorScheme(
    primary = ShanEmeraldLight,
    onPrimary = Color.White,
    primaryContainer = ShanOutgoingBubbleLight,
    onPrimaryContainer = ShanTextPrimaryLight,
    secondary = Color(0xFF0284C7),
    onSecondary = Color.White,
    tertiary = ShanBronze,
    background = ShanBgLight,
    onBackground = ShanTextPrimaryLight,
    surface = ShanSurfaceLight,
    onSurface = ShanTextPrimaryLight,
    surfaceVariant = ShanSurfaceElevatedLight,
    onSurfaceVariant = ShanTextSecondaryLight,
    outline = ShanBorderLight,
    error = ShanDanger,
    onError = Color.White
)

@Composable
fun ShanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) ShanDarkColorScheme else ShanLightColorScheme
    val extendedColors = if (darkTheme) {
        ShanExtendedColors(
            outgoingBubble = ShanOutgoingBubbleDark,
            incomingBubble = ShanIncomingBubbleDark,
            border = ShanBorderDark,
            accentCyan = ShanCyan,
            accentBronze = ShanBronze,
            emeraldGlow = ShanEmerald,
            isDark = true
        )
    } else {
        ShanExtendedColors(
            outgoingBubble = ShanOutgoingBubbleLight,
            incomingBubble = ShanIncomingBubbleLight,
            border = ShanBorderLight,
            accentCyan = Color(0xFF0284C7),
            accentBronze = ShanBronze,
            emeraldGlow = ShanEmeraldLight,
            isDark = false
        )
    }

    CompositionLocalProvider(LocalShanColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ShanTypography,
            content = content
        )
    }
}
