package ai.androidclaw.ui.theme

import ai.androidclaw.data.ThemePreference
import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors: ColorScheme =
    lightColorScheme(
        primary = Color(0xFF3A6A12),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD5F2B8),
        onPrimaryContainer = Color(0xFF0D2000),
        secondary = Color(0xFF55624C),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFD9E7CB),
        onSecondaryContainer = Color(0xFF131F0D),
        tertiary = Color(0xFF386667),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFBBEBEC),
        onTertiaryContainer = Color(0xFF002020),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFF8FAF3),
        onBackground = Color(0xFF191D16),
        surface = Color(0xFFF8FAF3),
        onSurface = Color(0xFF191D16),
        surfaceVariant = Color(0xFFDFE4D7),
        onSurfaceVariant = Color(0xFF43483E),
        outline = Color(0xFF73796D),
        outlineVariant = Color(0xFFC3C8BB),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF2F5EC),
        surfaceContainer = Color(0xFFECEFE6),
        surfaceContainerHigh = Color(0xFFE7E9E1),
        surfaceContainerHighest = Color(0xFFE1E4DB),
        inverseSurface = Color(0xFF2E322A),
        inverseOnSurface = Color(0xFFEFF2E8),
        inversePrimary = Color(0xFF9FD677),
    )

private val DarkColors: ColorScheme =
    darkColorScheme(
        primary = Color(0xFF9FD677),
        onPrimary = Color(0xFF183800),
        primaryContainer = Color(0xFF235100),
        onPrimaryContainer = Color(0xFFD5F2B8),
        secondary = Color(0xFFBDCBB0),
        onSecondary = Color(0xFF283420),
        secondaryContainer = Color(0xFF3E4A35),
        onSecondaryContainer = Color(0xFFD9E7CB),
        tertiary = Color(0xFFA0CFD0),
        onTertiary = Color(0xFF003738),
        tertiaryContainer = Color(0xFF1E4E4F),
        onTertiaryContainer = Color(0xFFBBEBEC),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF11140F),
        onBackground = Color(0xFFE1E4DB),
        surface = Color(0xFF11140F),
        onSurface = Color(0xFFE1E4DB),
        surfaceVariant = Color(0xFF43483E),
        onSurfaceVariant = Color(0xFFC3C8BB),
        outline = Color(0xFF8D9387),
        outlineVariant = Color(0xFF43483E),
        surfaceContainerLowest = Color(0xFF0C0F0A),
        surfaceContainerLow = Color(0xFF191D16),
        surfaceContainer = Color(0xFF1D211A),
        surfaceContainerHigh = Color(0xFF282B24),
        surfaceContainerHighest = Color(0xFF32362E),
        inverseSurface = Color(0xFFE1E4DB),
        inverseOnSurface = Color(0xFF2E322A),
        inversePrimary = Color(0xFF3A6A12),
    )

@Composable
fun AndroidClawTheme(
    themePreference: ThemePreference = ThemePreference.System,
    content: @Composable () -> Unit,
) {
    val darkTheme =
        when (themePreference) {
            ThemePreference.System -> isSystemInDarkTheme()
            ThemePreference.Light -> false
            ThemePreference.Dark -> true
        }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
