package ua.com.sdegroup.imoveprinter.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = Primary,
    secondary = PrimaryBlue,
    background = PrimaryLightGray,
    surface = PrimaryWhite,
    onPrimary = PrimaryWhite,
    onSecondary = PrimaryWhite,
    onBackground = PrimaryGray,
    onSurface = PrimaryGray,
    error = PrimaryRed
)

private val DarkColors = darkColorScheme(
    primary = PrimaryDark,
    secondary = PrimaryBlue,
    background = PrimaryLightGray2,
    surface = PrimaryDark,
    onPrimary = PrimaryWhite,
    onSecondary = PrimaryWhite,
    onBackground = PrimaryGray,
    onSurface = PrimaryGray,
    error = PrimaryRed
)

@Composable
fun IMovePrinterTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}