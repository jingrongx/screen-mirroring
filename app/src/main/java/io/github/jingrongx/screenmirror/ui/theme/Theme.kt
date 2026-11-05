package io.github.jingrongx.screenmirror.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blue = Color(0xFF3D5AFE)
private val BlueDark = Color(0xFF9FA8FF)

private val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E9FF),
    onPrimaryContainer = Color(0xFF00174B),
    secondary = Color(0xFF585E71),
)

private val DarkColors = darkColorScheme(
    primary = BlueDark,
    onPrimary = Color(0xFF00217A),
    primaryContainer = Color(0xFF2A3EB1),
    onPrimaryContainer = Color(0xFFE3E9FF),
    secondary = Color(0xFFC1C5DD),
)

@Composable
fun ScreenMirrorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
