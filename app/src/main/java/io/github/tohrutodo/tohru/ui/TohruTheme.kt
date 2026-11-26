package io.github.tohrutodo.tohru.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fallback scheme derived from the app mark: navy dress, coral bow,
// green tail, pale gold lining, cream background. Dynamic color still
// wins on Android 12+ when enabled.
private val MarkDark = darkColorScheme(
    primary = Color(0xFFE5D985),
    onPrimary = Color(0xFF2B2F3E),
    primaryContainer = Color(0xFF4A4429),
    onPrimaryContainer = Color(0xFFF4EEC6),
    secondary = Color(0xFF6FC9AC),
    onSecondary = Color(0xFF10261F),
    secondaryContainer = Color(0xFF274F42),
    onSecondaryContainer = Color(0xFFC9EDE0),
    tertiary = Color(0xFFF49C8F),
    onTertiary = Color(0xFF3A1310),
    surface = Color(0xFF14161F),
    onSurface = Color(0xFFECE9DF),
    surfaceContainerHigh = Color(0xFF232838),
    onSurfaceVariant = Color(0xFFB9BCCB),
    outline = Color(0xFF8B90A3),
    outlineVariant = Color(0xFF3C4257),
)

private val MarkLight = lightColorScheme(
    primary = Color(0xFF6E5C10),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF2E4A1),
    onPrimaryContainer = Color(0xFF211A00),
    secondary = Color(0xFF2C6B57),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC6E9DC),
    onSecondaryContainer = Color(0xFF00201A),
    tertiary = Color(0xFFB03A2E),
    onTertiary = Color(0xFFFFFFFF),
    surface = Color(0xFFFBF8F0),
    onSurface = Color(0xFF1B1D26),
    surfaceContainerHigh = Color(0xFFEFE9DB),
    onSurfaceVariant = Color(0xFF4A4E5E),
    outline = Color(0xFF6E7280),
    outlineVariant = Color(0xFFD3CEBF),
)

@Composable
fun TohruTheme(
    dynamic: Boolean = true,
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = mode.isDark(systemDark)
    val scheme = remember(dynamic, dark, mode, context) {
        val base = when {
            dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            dark -> MarkDark
            else -> MarkLight
        }
        if (mode == ThemeMode.BLACK) base.oledBlack() else base
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

// Pure-black surfaces for OLED panels. Accent colors remain untouched so
// dynamic color still selects the primary and secondary palette.
private fun ColorScheme.oledBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color.Black,
    surfaceTint = Color.Black,
    surfaceDim = Color.Black,
    surfaceBright = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color.Black,
    surfaceContainer = Color.Black,
    surfaceContainerHigh = Color.Black,
    surfaceContainerHighest = Color.Black,
    outline = Color(0xFF7A7A7A),
    outlineVariant = Color(0xFF2A2A2A),
    scrim = Color.Black,
)
