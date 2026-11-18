package app.sawaari.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.sawaari.offline.DownloadedRegion
import app.sawaari.offline.RegionPreset
import app.sawaari.offline.formatBytes
import app.sawaari.offline.regionPresets
import platform.Foundation.NSBundle

/** Profile and settings. More sections (group, SOS, rides, garage) arrive in the next steps. */
@Composable
internal fun SettingsScreen(
    palette: AppPalette,
    mode: DarkMode,
    onPalette: (AppPalette) -> Unit,
    onMode: (DarkMode) -> Unit,
    onOfflineMaps: () -> Unit,
    onRides: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Profile and settings", style = MaterialTheme.typography.headlineSmall)
        Text("Your rides", style = MaterialTheme.typography.titleMedium)
        Text("Rides are recorded while you navigate or ride with a group.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onRides, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Timeline, null); Spacer(Modifier.width(8.dp)); Text("Your rides")
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("Offline maps", style = MaterialTheme.typography.titleMedium)
        Text("Download maps for areas with no signal.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onOfflineMaps, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.DownloadForOffline, null); Spacer(Modifier.width(8.dp)); Text("Offline maps")
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("App colours", style = MaterialTheme.typography.titleMedium)
        ThemeOptions(palette, mode, onPalette, onMode)
        Spacer(Modifier.size(8.dp))
        val version = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: ""
        Text("Sawaari for iPhone $version (preview)", style = MaterialTheme.typography.bodySmall)
    }
}

/** Palette and light/dark choices, the same as on Android. Changes apply straight away. */
@Composable
internal fun ThemeOptions(palette: AppPalette, mode: DarkMode, onPalette: (AppPalette) -> Unit, onMode: (DarkMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AppPalette.entries.forEach { p ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .border(
                        if (p == palette) 2.dp else 1.dp,
                        if (p == palette) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(12.dp),
                    )
                    .clickable { onPalette(p) }.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = p == palette, onClick = { onPalette(p) })
                p.swatch.forEach { c -> Box(Modifier.padding(end = 4.dp).size(24.dp).clip(CircleShape).background(c)) }
                Spacer(Modifier.width(8.dp))
                Text(p.label, style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.size(4.dp))
        }
        Text("Light or dark", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DarkMode.entries.forEach { m -> FilterChip(selected = m == mode, onClick = { onMode(m) }, label = { Text(m.label) }) }
        }
    }
}

/** Offline maps: downloaded areas with size and progress, plus one-tap popular ride areas (as on Android). */
@Composable
internal fun OfflineMapsScreen(
    downloads: List<DownloadedRegion>,
    onDownloadPreset: (RegionPreset) -> Unit,
    onDelete: (Long) -> Unit,
    onResume: (Long) -> Unit,
) {
    val downloadedNames = downloads.map { it.name }.toSet()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("Your offline maps", style = MaterialTheme.typography.titleLarge) }
        if (downloads.isEmpty()) {
            item { Text("Nothing downloaded yet. Pick an area below, or use \"Download this area\" on the map.") }
        }
        items(downloads, key = { it.id }) { region ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    ListItem(
                        headlineContent = { Text(region.name) },
                        supportingContent = {
                            val size = formatBytes(region.completedBytes)
                            Text(
                                region.error ?: if (region.isComplete) "Ready offline · $size"
                                else "Downloading ${(region.progress * 100).toInt()}% · $size",
                            )
                        },
                        trailingContent = {
                            Row {
                                if (!region.isComplete) {
                                    IconButton(onClick = { onResume(region.id) }) { Icon(Icons.Default.Refresh, "Resume") }
                                }
                                IconButton(onClick = { onDelete(region.id) }) { Icon(Icons.Default.Delete, "Delete") }
                            }
                        },
                    )
                    if (!region.isComplete) {
                        LinearProgressIndicator(
                            progress = { region.progress },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(top = 16.dp)) {
                Text("Offline navigation", style = MaterialTheme.typography.titleLarge)
                Text(
                    "On iPhone, turn-by-turn directions are worked out online for now. Offline directions come later.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item { Text("Popular ride areas", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp)) }
        items(regionPresets, key = { it.name }) { preset ->
            ListItem(
                headlineContent = { Text(preset.name) },
                trailingContent = {
                    if (preset.name !in downloadedNames) {
                        IconButton(onClick = { onDownloadPreset(preset) }) { Icon(Icons.Default.Download, "Download") }
                    }
                },
            )
        }
    }
}
