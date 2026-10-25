package com.anan.anannotes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.anan.anannotes.core.model.ThemeMode

private val LightColorScheme =
  lightColorScheme(
    primary = AnAnColors.Graphite,
    onPrimary = AnAnColors.White,
    primaryContainer = AnAnColors.CoolGray,
    onPrimaryContainer = AnAnColors.TextPrimary,
    secondary = AnAnColors.GraphiteLight,
    onSecondary = AnAnColors.White,
    secondaryContainer = AnAnColors.CoolGray,
    onSecondaryContainer = AnAnColors.TextPrimary,
    tertiary = AnAnColors.SlateAccent,
    onTertiary = AnAnColors.White,
    tertiaryContainer = AnAnColors.CoolGray,
    onTertiaryContainer = AnAnColors.TextPrimary,
    background = AnAnColors.Canvas,
    onBackground = AnAnColors.TextPrimary,
    surface = AnAnColors.White,
    onSurface = AnAnColors.TextPrimary,
    surfaceVariant = AnAnColors.CoolGray,
    onSurfaceVariant = AnAnColors.TextSecondary,
    outline = AnAnColors.Outline,
    outlineVariant = AnAnColors.Outline,
    surfaceTint = AnAnColors.White,
    inversePrimary = AnAnColors.GraphiteLight,
    error = AnAnColors.GraphiteLight,
    onError = AnAnColors.White,
    errorContainer = AnAnColors.CoolGray,
    onErrorContainer = AnAnColors.TextPrimary,
  )

private val DarkColorScheme =
  darkColorScheme(
    primary = AnAnColors.DarkTextPrimary,
    onPrimary = AnAnColors.DarkCanvas,
    primaryContainer = AnAnColors.DarkSurfaceVariant,
    onPrimaryContainer = AnAnColors.DarkTextPrimary,
    secondary = AnAnColors.DarkTextSecondary,
    onSecondary = AnAnColors.DarkCanvas,
    secondaryContainer = AnAnColors.DarkSurfaceVariant,
    onSecondaryContainer = AnAnColors.DarkTextPrimary,
    tertiary = AnAnColors.DarkSlateAccent,
    onTertiary = AnAnColors.DarkCanvas,
    tertiaryContainer = AnAnColors.DarkSurfaceVariant,
    onTertiaryContainer = AnAnColors.DarkTextPrimary,
    background = AnAnColors.DarkCanvas,
    onBackground = AnAnColors.DarkTextPrimary,
    surface = AnAnColors.DarkSurface,
    onSurface = AnAnColors.DarkTextPrimary,
    surfaceVariant = AnAnColors.DarkSurfaceVariant,
    onSurfaceVariant = AnAnColors.DarkTextSecondary,
    outline = AnAnColors.DarkOutline,
    outlineVariant = AnAnColors.DarkOutline,
    surfaceTint = AnAnColors.DarkSurface,
    inversePrimary = AnAnColors.SlateAccent,
    error = AnAnColors.DarkTextSecondary,
    onError = AnAnColors.DarkCanvas,
    errorContainer = AnAnColors.DarkSurfaceVariant,
    onErrorContainer = AnAnColors.DarkTextPrimary,
  )

@Composable
fun AnAnNotesTheme(
  themeMode: ThemeMode = ThemeMode.SYSTEM,
  content: @Composable () -> Unit,
) {
  val darkTheme = when (themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
  }
  MaterialTheme(
    colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
    typography = AnAnTypography,
    content = content,
  )
}
