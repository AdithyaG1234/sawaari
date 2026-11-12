package app.sawaari.ride

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sawaari.garage.Garage
import app.sawaari.nav.formatDistance
import app.sawaari.nav.formatDuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max

/** Your rides, newest first, with distance, riding time and speeds. Kept only on this phone. */
@Composable
fun RideHistoryScreen(modifier: Modifier = Modifier) {
    val rides by RideRecorder.rides.collectAsState()
    var confirmDelete by remember { mutableStateOf<SavedRide?>(null) }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Your rides", style = MaterialTheme.typography.titleLarge)
            Text(
                "Recorded automatically while you navigate or ride with a group. Stored only on this phone.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (rides.isEmpty()) item {
            Text("No rides yet. Start navigation or join a group ride and your ride will appear here.", style = MaterialTheme.typography.bodyMedium)
        } else {
            item {
                val km = rides.sumOf { it.stats.distanceMeters }
                val ms = rides.sumOf { it.stats.movingMillis }
                Text(
                    "${rides.size} ride${if (rides.size == 1) "" else "s"} · ${formatDistance(km)} · ${formatDuration(ms)} riding",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(rides, key = { it.id }) { r -> RideCard(r, onDelete = { confirmDelete = r }) }
        }
    }
    confirmDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this ride?") },
            text = { Text(r.title.ifBlank { "This ride" } + " will be removed from this phone.") },
            confirmButton = { TextButton(onClick = { RideRecorder.delete(r.id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RideCard(r: SavedRide, onDelete: () -> Unit) {
    val s = r.stats
    val color = MaterialTheme.colorScheme.primary
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // The ride's shape.
            Canvas(Modifier.size(72.dp)) {
                val pts = r.track
                if (pts.size < 2) return@Canvas
                val minLat = pts.minOf { it.lat }; val maxLat = pts.maxOf { it.lat }
                val minLon = pts.minOf { it.lon }; val maxLon = pts.maxOf { it.lon }
                val k = cos(Math.toRadians((minLat + maxLat) / 2))
                val span = max((maxLon - minLon) * k, maxLat - minLat).takeIf { it > 0 } ?: return@Canvas
                val scale = (size.minDimension * 0.9f) / span.toFloat()
                val ox = (size.width - ((maxLon - minLon) * k).toFloat() * scale) / 2
                val oy = (size.height - (maxLat - minLat).toFloat() * scale) / 2
                fun at(lat: Double, lon: Double) = Offset(ox + ((lon - minLon) * k).toFloat() * scale, size.height - oy - (lat - minLat).toFloat() * scale)
                val path = Path()
                pts.forEachIndexed { i, p -> val o = at(p.lat, p.lon); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
                drawPath(path, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(r.title.ifBlank { "Ride" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(SimpleDateFormat("EEE d MMM yyyy, h:mm a", Locale.ENGLISH).format(Date(r.id)), style = MaterialTheme.typography.bodySmall)
                Text("${formatDistance(s.distanceMeters)} · ${formatDuration(s.movingMillis)} riding · ${formatDuration(s.totalMillis)} total", style = MaterialTheme.typography.bodyMedium)
                Text("Average ${s.avgMovingKmh.toInt()} km/h · Top ${s.topKmh.toInt()} km/h", style = MaterialTheme.typography.bodySmall)
                Garage.state.value.bikes.firstOrNull { it.id == r.bikeId }?.let { Text("On ${it.name}", style = MaterialTheme.typography.bodySmall) }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete ride") }
        }
    }
}
