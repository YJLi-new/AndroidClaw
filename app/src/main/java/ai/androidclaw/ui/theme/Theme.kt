package ai.androidclaw.ui.theme

import ai.androidclaw.data.ThemePreference
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF22314A),
    onPrimary = Color(0xFFF8F4EA),
    secondary = Color(0xFF4B6579),
    onSecondary = Color.White,
    tertiary = Color(0xFFB87333),
    background = Color(0xFFF4EFE6),
    surface = Color(0xFFFFFBF6),
    onSurface = Color(0xFF17202B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF2C572),
    onPrimary = Color(0xFF2B1E07),
    secondary = Color(0xFF98ADC0),
    onSecondary = Color(0xFF102030),
    tertiary = Color(0xFFFFB36B),
    background = Color(0xFF0E1620),
    surface = Color(0xFF15212D),
    onSurface = Color(0xFFF3EDE1),
)

@Composable
fun AndroidClawTheme(
    themePreference: ThemePreference = ThemePreference.System,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themePreference) {
        ThemePreference.System -> isSystemInDarkTheme()
        ThemePreference.Light -> false
        ThemePreference.Dark -> true
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
