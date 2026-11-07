package app.sawaari.places

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocation
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.sawaari.nav.formatDistance
import kotlinx.coroutines.launch
import java.util.Locale

/** Category chips, and when one is picked, the places of that kind along the route. */
@Composable
fun PlacesPanel(
    selected: PlaceCategory?,
    onSelect: (PlaceCategory?) -> Unit,
    places: List<PlaceOnRoute>,
    loading: Boolean,
    currentAlong: Double?,
    repo: PlacesRepository,
    onOpen: (PlaceOnRoute) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PlaceCategory.entries.forEach { c ->
                FilterChip(
                    selected = selected == c,
                    onClick = { onSelect(if (selected == c) null else c) },
                    label = { Text(c.label) },
                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }
        }
        if (selected != null) Card(Modifier.fillMaxWidth()) {
            when {
                loading -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text("Finding places along the route…")
                }
                places.isEmpty() -> Text(
                    "No ${selected.label.lowercase()} places within 1 km of this route in the map data.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall,
                )
                else -> LazyColumn(Modifier.heightIn(max = 220.dp)) {
                    items(places, key = { it.place.id }) { p ->
                        var rating by remember(p.place.id) { mutableStateOf(repo.cachedRiderRating(p.place.id)) }
                        LaunchedEffect(p.place.id) { if (rating == null) rating = repo.riderRating(p.place.id) }
                        Column(Modifier.fillMaxWidth().clickable { onOpen(p) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(p.place.displayName, style = MaterialTheme.typography.titleSmall)
                            val ahead = currentAlong?.let { p.alongMeters - it } ?: p.alongMeters
                            val where = (if (currentAlong != null) "${formatDistance(ahead.coerceAtLeast(0.0))} ahead" else "${formatDistance(ahead)} into the route") +
                                " · ${formatDistance(p.offRouteMeters)} off route"
                            val stars = rating?.takeIf { it.count > 0 }?.let { " · ★ %.1f (%d)".format(Locale.US, it.average, it.count) } ?: ""
                            Text(where + stars, style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/** Details for one place: rider and Google ratings, rate it, call, or add it as a stop. */
@Composable
fun PlaceDialog(
    item: PlaceOnRoute,
    repo: PlacesRepository,
    navigating: Boolean,
    onAddStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val place = item.place
    var riders by remember { mutableStateOf(repo.cachedRiderRating(place.id)) }
    var google by remember { mutableStateOf<GoogleRating?>(null) }
    var googleLoading by remember { mutableStateOf(repo.googleEnabled) }
    LaunchedEffect(place.id) { riders = repo.riderRating(place.id) }
    LaunchedEffect(place.id) { if (repo.googleEnabled) { google = repo.googleRating(place); googleLoading = false } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(place.displayName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${place.category.label} · ${formatDistance(item.offRouteMeters)} off the route", style = MaterialTheme.typography.bodySmall)
                Text(
                    riders?.takeIf { it.count > 0 }?.let { "Riders: ★ %.1f from %d rating%s".format(Locale.US, it.average, it.count, if (it.count == 1) "" else "s") }
                        ?: "No rider ratings yet",
                )
                if (repo.googleEnabled) {
                    Text(
                        when {
                            googleLoading -> "Google: checking…"
                            google?.rating != null -> "Google: ★ %.1f from %,d reviews".format(Locale.US, google!!.rating, google!!.count)
                            else -> "Google: no rating found (or no signal)"
                        },
                    )
                    google?.mapsUri?.let { uri ->
                        TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) } }) { Text("Reviews on Google Maps") }
                    }
                }
                Text("Your rating", style = MaterialTheme.typography.labelMedium)
                Row {
                    (1..5).forEach { n ->
                        val filled = (riders?.mine ?: 0) >= n
                        IconButton(onClick = { scope.launch { repo.rate(place.id, n)?.let { riders = it } } }, modifier = Modifier.size(40.dp)) {
                            Icon(
                                if (filled) Icons.Filled.Star else Icons.Outlined.StarOutline, "$n stars",
                                tint = if (filled) Color(0xFFF9A825) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { onAddStop(); onDismiss() }) {
                        Icon(Icons.Default.AddLocation, null); Spacer(Modifier.width(6.dp)); Text(if (navigating) "Go here next" else "Add as stop")
                    }
                    if (place.phone.isNotBlank()) OutlinedButton(onClick = { app.sawaari.group.dial(context, place.phone) }) {
                        Icon(Icons.Default.Call, null); Spacer(Modifier.width(6.dp)); Text("Call")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
