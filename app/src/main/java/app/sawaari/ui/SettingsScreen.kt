package app.sawaari.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.sawaari.BuildConfig
import app.sawaari.ui.theme.AppPalette
import app.sawaari.ui.theme.DarkMode
import app.sawaari.ui.theme.ThemeOptions

@Composable
fun SettingsScreen(
    palette: AppPalette,
    mode: DarkMode,
    onPalette: (AppPalette) -> Unit,
    onMode: (DarkMode) -> Unit,
    onTourGuide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text("App colours", style = MaterialTheme.typography.titleMedium)
        ThemeOptions(palette, mode, onPalette, onMode)
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("Help", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onTourGuide, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Filled.HelpOutline, null); Spacer(Modifier.width(8.dp)); Text("Show the tour guide")
        }
        Spacer(Modifier.size(8.dp))
        Text("Sawaari ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
    }
}
