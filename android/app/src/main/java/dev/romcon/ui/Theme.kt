package dev.romcon.ui

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
    primary = Color(0xFF2557D6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4FF),
    onPrimaryContainer = Color(0xFF001849),
    secondaryContainer = Color(0xFFDFE3F5),
    surface = Color(0xFFF8F9FF),
    surfaceContainer = Color(0xFFECEEF8),
    surfaceContainerHigh = Color(0xFFE5E8F3),
    surfaceContainerHighest = Color(0xFFDFE2ED),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB4C5FF),
    onPrimary = Color(0xFF002A77),
    primaryContainer = Color(0xFF0E3FA8),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondaryContainer = Color(0xFF3B4253),
    surface = Color(0xFF111318),
    surfaceContainer = Color(0xFF1D2027),
    surfaceContainerHigh = Color(0xFF272A32),
    surfaceContainerHighest = Color(0xFF32353D),
)

@Composable
fun RomConTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
