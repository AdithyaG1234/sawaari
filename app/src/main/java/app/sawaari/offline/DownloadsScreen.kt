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
import androidx.compose.material.icons.filled.Close
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
import app.sawaari.routing.PackState
import app.sawaari.routing.RoutingPack
import app.sawaari.routing.routingPacks
import org.maplibre.android.geometry.LatLngBounds

/** Offline maps manager: downloaded regions with size/progress, plus one-tap popular ride areas. */
@Composable
fun DownloadsScreen(
    downloads: List<DownloadedRegion>,
    onDownloadPreset: (String, LatLngBounds) -> Unit,
    onDelete: (Long) -> Unit,
    onResume: (Long) -> Unit,
    routingStates: Map<String, PackState>,
    onDownloadRoutingPack: (RoutingPack) -> Unit,
    onDeleteRoutingPack: (RoutingPack) -> Unit,
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
        item {
            Column(Modifier.padding(top = 16.dp)) {
                Text("Offline navigation", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Download routing data to get turn-by-turn directions with no signal. Get the map for the same area too.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        items(routingPacks, key = { "routing-" + it.id }) { pack ->
            val state = routingStates[pack.id] ?: PackState.NotDownloaded
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    ListItem(
                        headlineContent = { Text(pack.name) },
                        supportingContent = {
                            Text(
                                when (state) {
                                    PackState.NotDownloaded -> "Not downloaded"
                                    is PackState.Downloading -> if (state.totalBytes > 0)
                                        "Downloading ${(state.progress * 100).toInt()}% of ${Formatter.formatShortFileSize(context, state.totalBytes)}"
                                    else "Downloading ${Formatter.formatShortFileSize(context, state.bytes)}"
                                    is PackState.Ready -> "Ready offline · ${Formatter.formatShortFileSize(context, state.sizeBytes)}"
                                    is PackState.Failed -> state.message
                                }
                            )
                        },
                        trailingContent = {
                            when (state) {
                                is PackState.Ready -> IconButton(onClick = { onDeleteRoutingPack(pack) }) { Icon(Icons.Default.Delete, "Delete") }
                                is PackState.Downloading -> IconButton(onClick = { onDeleteRoutingPack(pack) }) { Icon(Icons.Default.Close, "Cancel") }
                                else -> IconButton(onClick = { onDownloadRoutingPack(pack) }) { Icon(Icons.Default.Download, "Download") }
                            }
                        },
                    )
                    if (state is PackState.Downloading) {
                        LinearProgressIndicator(
                            progress = { state.progress },
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
