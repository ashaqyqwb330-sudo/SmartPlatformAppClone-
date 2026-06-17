package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val CosmicGoldColorScheme = darkColorScheme(
    primary = MetallicGold,
    secondary = EmeraldGlow,
    tertiary = BrightGold,
    background = SlateBg,
    surface = CardSlateBg,
    onPrimary = SlateBg,
    onSecondary = SlateBg,
    onBackground = TextSilver,
    onSurface = TextSilver,
    error = DangerRed
)

private val LightGoldColorScheme = lightColorScheme(
    primary = MetallicGold,
    secondary = EmeraldGlow,
    tertiary = DarkGold,
    background = SlateBg, // Maintain gorgeous dark slate mode as the default for luxurious aesthetics
    surface = CardSlateBg,
    onPrimary = SlateBg,
    onSecondary = SlateBg,
    onBackground = TextSilver,
    onSurface = TextSilver,
    error = DangerRed
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Disable Android dynamic colors to guarantee our gorgeous premium custom gold branding is applied on all devices
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) CosmicGoldColorScheme else LightGoldColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
