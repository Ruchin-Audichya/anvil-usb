package dev.ruchin.anvil.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFFC2410C),            // ember
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBC8),
    onPrimaryContainer = Color(0xFF3A0B00),
    secondary = Color(0xFF57534E),           // forged steel
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7E5E4),
    onSecondaryContainer = Color(0xFF1C1917),
    tertiary = Color(0xFF0F766E),
    background = Color(0xFFFFF8F5),
    surface = Color(0xFFFFF8F5),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB68F),
    onPrimary = Color(0xFF5A1A00),
    primaryContainer = Color(0xFF8A2E00),
    onPrimaryContainer = Color(0xFFFFDBC8),
    secondary = Color(0xFFD6D3D1),
    onSecondary = Color(0xFF292524),
    secondaryContainer = Color(0xFF44403C),
    onSecondaryContainer = Color(0xFFE7E5E4),
    tertiary = Color(0xFF5EEAD4),
    background = Color(0xFF14110F),
    surface = Color(0xFF14110F),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
)

@Composable
fun AnvilTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Off by default: Anvil keeps its own forge palette instead of the wallpaper's colours.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
