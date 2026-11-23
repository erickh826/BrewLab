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

private val BrewLightColorScheme = lightColorScheme(
  primary = PrecisionAmber,
  onPrimary = Color.White,
  primaryContainer = AmberContainer,
  onPrimaryContainer = OnAmberContainer,
  secondary = EspressoBrown,
  onSecondary = Color.White,
  secondaryContainer = EspressoContainer,
  onSecondaryContainer = EspressoBrown,
  tertiary = SignalTeal,
  onTertiary = Color.White,
  tertiaryContainer = SignalTealBg,
  onTertiaryContainer = SignalTeal,
  background = WarmOffWhite,
  onBackground = CharcoalInk,
  surface = InstrumentPaper,
  onSurface = CharcoalInk,
  surfaceVariant = WarmSand,
  onSurfaceVariant = SlateTaupe,
  surfaceContainerLowest = InstrumentPaper,
  surfaceContainerLow = WarmOffWhite,
  surfaceContainer = WarmSand,
  surfaceContainerHigh = WarmSandHigh,
  outline = HairlineWarm,
  outlineVariant = HairlineWarm,
  error = SignalCrimson,
  onError = Color.White,
  errorContainer = SignalCrimsonBg,
  onErrorContainer = SignalCrimson,
)

private val BrewDarkColorScheme = darkColorScheme(
  primary = DarkAmberPrimary,
  onPrimary = Color(0xFF281700),
  primaryContainer = Color(0xFF5D3A00),
  onPrimaryContainer = AmberContainer,
  secondary = Color(0xFFD0BFA9),
  onSecondary = Color(0xFF2B2016),
  secondaryContainer = Color(0xFF3E3024),
  onSecondaryContainer = Color(0xFFEADFD5),
  tertiary = Color(0xFF66B8A1),
  onTertiary = Color(0xFF00382B),
  background = DarkEspressoBg,
  onBackground = DarkWarmInk,
  surface = DarkInstrumentSurface,
  onSurface = DarkWarmInk,
  surfaceVariant = DarkSandContainer,
  onSurfaceVariant = DarkMutedTaupe,
  surfaceContainerLowest = DarkEspressoBg,
  surfaceContainerLow = DarkInstrumentSurface,
  surfaceContainer = DarkSandContainer,
  surfaceContainerHigh = Color(0xFF373028),
  outline = Color(0xFF4B433B),
  outlineVariant = Color(0xFF3B342D),
  error = Color(0xFFE87A6D),
  onError = Color(0xFF450E08),
)

val BrewLabShapes = Shapes(
  small = RoundedCornerShape(10.dp),
  medium = RoundedCornerShape(14.dp),
  large = RoundedCornerShape(18.dp),
)

@Composable
fun BrewLabTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) BrewDarkColorScheme else BrewLightColorScheme
  MaterialTheme(
    colorScheme = colorScheme,
    typography = BrewLabTypography,
    shapes = BrewLabShapes,
    content = content,
  )
}
