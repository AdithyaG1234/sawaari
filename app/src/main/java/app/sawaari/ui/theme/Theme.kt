package app.sawaari.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Colour combinations riders can pick from. */
enum class AppPalette(val label: String, val swatch: List<Color>, val light: ColorScheme, val dark: ColorScheme) {
    SAFFRON(
        "Saffron & Asphalt", listOf(Color(0xFFE8711A), Color(0xFF263238)),
        lightColorScheme(
            primary = Color(0xFFE8711A), onPrimary = Color.White,
            primaryContainer = Color(0xFFFFDBC8), onPrimaryContainer = Color(0xFF341100),
            secondary = Color(0xFF263238), onSecondary = Color.White,
            secondaryContainer = Color(0xFFCFD8DC), onSecondaryContainer = Color(0xFF101C21),
            tertiary = Color(0xFF00897B),
        ),
        darkColorScheme(
            primary = Color(0xFFFF9A4D), onPrimary = Color(0xFF4A1C00),
            primaryContainer = Color(0xFF8A3D00), onPrimaryContainer = Color(0xFFFFDBC8),
            secondary = Color(0xFFB0BEC5), onSecondary = Color(0xFF1B262B),
            secondaryContainer = Color(0xFF37474F), onSecondaryContainer = Color(0xFFCFD8DC),
            tertiary = Color(0xFF4DB6AC),
        ),
    ),
    FOREST(
        "Forest & Sand", listOf(Color(0xFF2E7D32), Color(0xFFC8A165)),
        lightColorScheme(
            primary = Color(0xFF2E7D32), onPrimary = Color.White,
            primaryContainer = Color(0xFFB9F0B4), onPrimaryContainer = Color(0xFF002204),
            secondary = Color(0xFF8D6E3F), onSecondary = Color.White,
            secondaryContainer = Color(0xFFF3DFBC), onSecondaryContainer = Color(0xFF2B1D04),
            tertiary = Color(0xFFC8A165),
        ),
        darkColorScheme(
            primary = Color(0xFF81C784), onPrimary = Color(0xFF00390A),
            primaryContainer = Color(0xFF1B5E20), onPrimaryContainer = Color(0xFFB9F0B4),
            secondary = Color(0xFFDBC293), onSecondary = Color(0xFF3D2E10),
            secondaryContainer = Color(0xFF5A4520), onSecondaryContainer = Color(0xFFF3DFBC),
            tertiary = Color(0xFFC8A165),
        ),
    ),
    OCEAN(
        "Ocean & Teal", listOf(Color(0xFF1565C0), Color(0xFF00ACC1)),
        lightColorScheme(
            primary = Color(0xFF1565C0), onPrimary = Color.White,
            primaryContainer = Color(0xFFD3E3FF), onPrimaryContainer = Color(0xFF001B3E),
            secondary = Color(0xFF00838F), onSecondary = Color.White,
            secondaryContainer = Color(0xFFB2EBF2), onSecondaryContainer = Color(0xFF002024),
            tertiary = Color(0xFF00ACC1),
        ),
        darkColorScheme(
            primary = Color(0xFF90CAF9), onPrimary = Color(0xFF003063),
            primaryContainer = Color(0xFF0D47A1), onPrimaryContainer = Color(0xFFD3E3FF),
            secondary = Color(0xFF4DD0E1), onSecondary = Color(0xFF00363D),
            secondaryContainer = Color(0xFF006064), onSecondaryContainer = Color(0xFFB2EBF2),
            tertiary = Color(0xFF80DEEA),
        ),
    ),
}

enum class DarkMode(val label: String) { SYSTEM("Phone setting"), LIGHT("Light"), DARK("Dark") }

/** The chosen palette and light/dark mode, saved on the phone. */
object ThemeSettings {
    private const val PREFS = "theme"
    private val _palette = MutableStateFlow(AppPalette.SAFFRON)
    private val _mode = MutableStateFlow(DarkMode.SYSTEM)
    val palette: StateFlow<AppPalette> = _palette
    val mode: StateFlow<DarkMode> = _mode

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _palette.value = runCatching { AppPalette.valueOf(p.getString("palette", null)!!) }.getOrDefault(AppPalette.SAFFRON)
        _mode.value = runCatching { DarkMode.valueOf(p.getString("mode", null)!!) }.getOrDefault(DarkMode.SYSTEM)
    }

    fun setPalette(context: Context, palette: AppPalette) {
        _palette.value = palette
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("palette", palette.name).apply()
    }

    fun setMode(context: Context, mode: DarkMode) {
        _mode.value = mode
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("mode", mode.name).apply()
    }
}

@Composable
fun SawaariTheme(
    palette: AppPalette = AppPalette.SAFFRON,
    mode: DarkMode = DarkMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        DarkMode.SYSTEM -> isSystemInDarkTheme()
        DarkMode.LIGHT -> false
        DarkMode.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) palette.dark else palette.light, content = content)
}
