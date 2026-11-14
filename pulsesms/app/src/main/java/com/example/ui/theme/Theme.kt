package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val PulseDarkColorScheme =
  darkColorScheme(
    primary = MintGlow,
    onPrimary = ObsidianDark,
    primaryContainer = EmeraldDeep,
    onPrimaryContainer = MintSoft,
    secondary = EmeraldBright,
    onSecondary = ObsidianDark,
    secondaryContainer = SurfaceAltDark,
    onSecondaryContainer = InkDark,
    tertiary = AmberSignal,
    onTertiary = Color.White,
    tertiaryContainer = AmberInk,
    onTertiaryContainer = AmberSoft,
    background = ObsidianDark,
    onBackground = InkDark,
    surface = SurfaceDark,
    onSurface = InkDark,
    surfaceVariant = SurfaceAltDark,
    onSurfaceVariant = InkMutedDark,
    outline = OutlineDark,
    error = CoralAlert,
    onError = Color.White,
  )

private val PulseLightColorScheme =
  lightColorScheme(
    primary = EmeraldDeep,
    onPrimary = Color.White,
    primaryContainer = MintSoft,
    onPrimaryContainer = MintInk,
    secondary = EmeraldBright,
    onSecondary = Color.White,
    secondaryContainer = SurfaceAltLight,
    onSecondaryContainer = InkLight,
    tertiary = AmberSignal,
    onTertiary = Color.White,
    tertiaryContainer = AmberSoft,
    onTertiaryContainer = AmberInk,
    background = PaperLight,
    onBackground = InkLight,
    surface = SurfaceLight,
    onSurface = InkLight,
    surfaceVariant = SurfaceAltLight,
    onSurfaceVariant = InkMutedLight,
    outline = OutlineLight,
    error = CoralAlert,
    onError = Color.White,
  )

val PulseShapes =
  Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
  )

@Composable
fun PulseSmsTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) PulseDarkColorScheme else PulseLightColorScheme
  MaterialTheme(
    colorScheme = colorScheme,
    typography = PulseTypography,
    shapes = PulseShapes,
    content = content,
  )
}
