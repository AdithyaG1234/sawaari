package app.sawaari.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Saffron = Color(0xFFE8711A)
private val Asphalt = Color(0xFF263238)

private val Light = lightColorScheme(primary = Saffron, secondary = Asphalt)
private val Dark = darkColorScheme(primary = Saffron, secondary = Color(0xFFB0BEC5))

@Composable
fun SawaariTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
