package app.axis.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Amber = Color(0xFFF2A31B)
val AmberSurface = Color(0xFFFFFAF0)
val AmberInk = Color(0xFF6B4300)

private val Light = lightColorScheme(
    primary = Color(0xFF101B26), onPrimary = Color.White,
    secondary = Color(0xFF6A5CFF), background = Color(0xFFE9EEF1), surface = Color.White,
    onBackground = Color(0xFF101B26), onSurface = Color(0xFF101B26), error = Color(0xFFB3261E),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF19C2E8), onPrimary = Color(0xFF001F27),
    secondary = Color(0xFFA58BFF), background = Color(0xFF0B0D12), surface = Color(0xFF151922),
    onBackground = Color(0xFFE6E8EE), onSurface = Color(0xFFE6E8EE), error = Color(0xFFFF6B6B),
)

@Composable
fun AxisTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
