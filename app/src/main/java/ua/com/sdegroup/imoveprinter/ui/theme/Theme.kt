package ua.com.sdegroup.imoveprinter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Brush

private val LightColorScheme = lightColorScheme(
    primary            = PrimaryLight,
    onPrimary          = WhiteLight,
    secondary          = BlueLight,
    onSecondary        = WhiteLight,
    background         = BackgroundLight,
    onBackground       = GrayColor,
    surface            = WhiteLight,
    onSurface          = GrayColor,
    error              = Red,
    onError            = WhiteLight
)

private val DarkColorScheme = darkColorScheme(
    primary            = PrimaryDark,
    onPrimary          = WhiteDark,
    secondary          = BlueDark,
    onSecondary        = WhiteDark,
    background         = BackgroundDark,
    onBackground       = GrayColor,
    surface            = ModalBackgroundDark,
    onSurface          = GrayColor,
    error              = Red,
    onError            = WhiteDark
)

@Composable
fun IMovePrinterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColorScheme else LightColorScheme

    val appGradient = remember(darkTheme) {
        Brush.linearGradient(
            colors = if (darkTheme)
                listOf(GradientStartDark, GradientEndDark)
            else
                listOf(GradientStartLight, GradientEndLight)
        )
    }

    MaterialTheme(
        colorScheme = colors,
        typography  = AppTypography,
        content     = {
            content()
        }
    )
}
