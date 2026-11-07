package app.sawaari.offline

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.maplibre.android.geometry.LatLngBounds

/** Offline maps manager: downloaded regions with size/progress, plus one-tap popular ride areas. */
@Composable
fun DownloadsScreen(
    downloads: List<DownloadedRegion>,
    onDownloadPreset: (String, LatLngBounds) -> Unit,
    onDelete: (Long) -> Unit,
    onResume: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val downloadedNames = downloads.map { it.name }.toSet()

    LazyColumn(
        modifier = modifier,
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
                            val size = Formatter.formatShortFileSize(context, region.completedBytes)
                            Text(
                                region.error ?: if (region.isComplete) "Ready offline · $size"
                                else "Downloading ${(region.progress * 100).toInt()}% · $size"
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
        item { Text("Popular ride areas", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp)) }
        items(regionPresets, key = { it.name }) { preset ->
            ListItem(
                headlineContent = { Text(preset.name) },
                trailingContent = {
                    if (preset.name !in downloadedNames) {
                        IconButton(onClick = { onDownloadPreset(preset.name, preset.bounds) }) {
                            Icon(Icons.Default.Download, "Download")
                        }
                    }
                },
            )
        }
    }
}
