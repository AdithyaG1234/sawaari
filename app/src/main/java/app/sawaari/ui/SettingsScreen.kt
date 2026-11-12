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
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.runtime.remember
import app.sawaari.garage.GarageReminders
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
    onSosSetup: () -> Unit,
    onRideHistory: () -> Unit,
    onGarage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text("Emergency SOS", style = MaterialTheme.typography.titleMedium)
        Text(
            "Emergency contacts, medical info and SMS permission for the red SOS button.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onSosSetup, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.HealthAndSafety, null); Spacer(Modifier.width(8.dp)); Text("Set up SOS contacts")
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("Your rides", style = MaterialTheme.typography.titleMedium)
        Text("Distance, riding time and speeds for every ride, kept on this phone.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onRideHistory, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Timeline, null); Spacer(Modifier.width(8.dp)); Text("Ride history and stats")
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("Your garage", style = MaterialTheme.typography.titleMedium)
        val due = remember(Unit) { GarageReminders.all().size }
        Text(
            if (due > 0) "$due job${if (due == 1) "" else "s"} need attention." else "Your bikes, service reminders and insurance and PUC expiry dates.",
            style = MaterialTheme.typography.bodySmall,
            color = if (due > 0) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified,
        )
        OutlinedButton(onClick = onGarage, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.TwoWheeler, null); Spacer(Modifier.width(8.dp)); Text("Bikes and service")
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
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
