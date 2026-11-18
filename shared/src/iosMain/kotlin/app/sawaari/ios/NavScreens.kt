package app.sawaari.ios

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.RoundaboutRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.UTurnRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sawaari.geo.GeoPoint
import app.sawaari.geo.distanceMeters
import app.sawaari.nav.NavSession
import app.sawaari.nav.RidePlan
import app.sawaari.nav.formatDistance
import app.sawaari.nav.formatDuration
import app.sawaari.ride.RideLog
import app.sawaari.ride.SavedRide
import app.sawaari.routing.Sign
import app.sawaari.share.RouteLinks
import app.sawaari.util.Text as TextUtil
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

private val START_COLOR = Color(0xFF2E7D32)
private val STOP_COLOR = Color(0xFFF9A825)
private val END_COLOR = Color(0xFFE53935)

/** Roads wind: a rough factor from straight-line to road distance, and an easy group riding speed. */
private const val ROAD_FACTOR = 1.3
private const val GROUP_KMH = 40.0

internal fun iconForSign(sign: Int): ImageVector = when (sign) {
    Sign.SHARP_LEFT -> Icons.Filled.TurnSharpLeft
    Sign.LEFT -> Icons.Filled.TurnLeft
    Sign.SLIGHT_LEFT, Sign.KEEP_LEFT -> Icons.Filled.TurnSlightLeft
    Sign.SLIGHT_RIGHT, Sign.KEEP_RIGHT -> Icons.Filled.TurnSlightRight
    Sign.RIGHT -> Icons.Filled.TurnRight
    Sign.SHARP_RIGHT -> Icons.Filled.TurnSharpRight
    Sign.U_TURN_LEFT, Sign.U_TURN_UNKNOWN -> Icons.Filled.UTurnLeft
    Sign.U_TURN_RIGHT -> Icons.Filled.UTurnRight
    Sign.ROUNDABOUT, Sign.LEAVE_ROUNDABOUT -> Icons.Filled.RoundaboutRight
    Sign.FINISH, Sign.VIA_REACHED -> Icons.Filled.Flag
    else -> Icons.Filled.Straight
}

/** The name from the plan's labels, or the coordinates. Place names come with search in the next step. */
private fun pointName(p: GeoPoint, nav: NavSession): String =
    nav.labelFor(p) ?: "${TextUtil.fixed(p.lat, 4)}, ${TextUtil.fixed(p.lon, 4)}"

/** Straight-line distance through the plan, stretched for winding roads, and time at group pace. */
private fun estimate(plan: RidePlan, me: GeoPoint?): Pair<Double, Long>? {
    val pts = plan.waypoints(me) ?: return null
    val m = pts.zipWithNext { a, b -> distanceMeters(a, b) }.sum() * ROAD_FACTOR
    if (m < 100) return null
    return m to (m / 1000.0 / GROUP_KMH * 3_600_000).toLong()
}

/**
 * The ride plan: start, stops and destination, a distance estimate, round trip, then Plan route and Start.
 * Like RouteCard on Android. Tap the title to shrink it.
 */
@Composable
internal fun RouteCard(nav: NavSession, onSharePlan: ((RidePlan) -> Unit)?, sharedPlan: RidePlan?) {
    val plan by nav.plan.collectAsState()
    val r by nav.route.collectAsState()
    val busy by nav.busy.collectAsState()
    nav.labels.collectAsState().value
    nav.roundTrip.collectAsState().value
    var expanded by remember { mutableStateOf(true) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { expanded = !expanded }) {
                Column(Modifier.weight(1f)) {
                    val route = r
                    if (route != null) {
                        Text("${formatDistance(route.distanceMeters)} · ${formatDuration(route.timeMillis)}", style = MaterialTheme.typography.titleLarge)
                    } else {
                        Text("Ride plan", style = MaterialTheme.typography.titleMedium)
                        estimate(plan, nav.myPosition)?.let { (m, ms) ->
                            Text("About ${formatDistance(m)} · ${formatDuration(ms)} (rough estimate)", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (!expanded) {
                        val n = plan.stops.size
                        Text(if (n == 0) "No stops" else "$n stop${if (n == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Icon(if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp, if (expanded) "Shrink" else "Expand")
            }

            if (expanded) {
                val start = plan.start
                val startText = when {
                    start == null -> "Your location"
                    nav.myPosition?.let { distanceMeters(it, start) < 200 } == true -> "Your location"
                    else -> pointName(start, nav)
                }
                PlanRow("S", START_COLOR, "Start: $startText", onRemove = start?.let { { nav.setStart(null) } })
                plan.stops.forEachIndexed { i, p ->
                    PlanRow(
                        "${i + 1}", STOP_COLOR, "Stop ${i + 1}: ${pointName(p, nav)}",
                        onRemove = { nav.removeStop(i) },
                        onUp = if (i > 0) ({ nav.moveStop(i, i - 1) }) else null,
                        onDown = if (i < plan.stops.lastIndex) ({ nav.moveStop(i, i + 1) }) else null,
                    )
                }
                val end = plan.end
                PlanRow(
                    "E", END_COLOR,
                    when {
                        end == null -> "Destination: search above, or long-press the map"
                        nav.isRoundTrip -> "Destination: back to the start"
                        else -> "Destination: ${pointName(end, nav)}"
                    },
                    onRemove = end?.let { { nav.clearEnd() } },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Round trip (come back to the start)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = nav.isRoundTrip, onCheckedChange = { nav.makeRoundTrip(it) })
                }
                Text(
                    if (r == null) "Search above or long-press the map to add stops. On iPhone the route is planned online, so you need signal to plan it."
                    else "Fastest motorcycle route through your stops",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (r == null) {
                    Button(onClick = { nav.planRoute() }, enabled = !busy && plan.end != null) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Directions, null)
                        Spacer(Modifier.width(8.dp)); Text("Plan route")
                    }
                } else {
                    Button(onClick = { nav.start() }) {
                        Icon(Icons.Default.Navigation, null); Spacer(Modifier.width(8.dp)); Text("Start")
                    }
                }
                OutlinedButton(onClick = { nav.clear() }) { Text("Clear") }
            }
            if (expanded && plan.end != null) {
                TextButton(onClick = {
                    val text = buildString {
                        RouteLinks.sawaariLink(plan, nav::labelFor)?.let { append("Open in Sawaari: ").append(it).append("\n") }
                        RouteLinks.googleLink(plan)?.let { append("Google Maps: ").append(it) }
                    }
                    shareText(text)
                }) {
                    Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text("Share route (WhatsApp etc.)")
                }
            }
            if (expanded && onSharePlan != null && plan.end != null) {
                val shared = sharedPlan == plan
                TextButton(onClick = { onSharePlan(plan) }, enabled = !shared) {
                    Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp))
                    Text(if (shared) "Shared with your group" else "Share plan with group")
                }
            }
        }
    }
}

@Composable
private fun PlanRow(
    badge: String,
    color: Color,
    text: String,
    onRemove: (() -> Unit)?,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
            Text(badge, color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (onUp != null) IconButton(onClick = onUp, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
        if (onDown != null) IconButton(onClick = onDown, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
        if (onRemove != null) IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, "Remove") }
    }
}

/** The next turn, how far to it, and what's left of the trip. Like NavigationBanner on Android. */
@Composable
internal fun NavigationBanner(nav: NavSession, modifier: Modifier = Modifier) {
    val route = nav.route.collectAsState().value ?: return
    val p = nav.progress.collectAsState().value
    val step = p?.let { route.steps.getOrNull(it.nextStepIndex) } ?: route.steps.firstOrNull() ?: return
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(iconForSign(step.sign), contentDescription = null, modifier = Modifier.size(56.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(p?.let { formatDistance(it.distanceToNextStep) } ?: "", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(step.text, style = MaterialTheme.typography.titleMedium)
                p?.let {
                    val left = if (route.distanceMeters > 0) route.timeMillis * (it.distanceRemaining / route.distanceMeters) else 0.0
                    Text("${formatDistance(it.distanceRemaining)} left · ${formatDuration(left.toLong())}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** What to do with a long-pressed place: destination, stop, start, or the group's meeting point. */
@Composable
internal fun PlaceActionsDialog(
    p: GeoPoint,
    nav: NavSession,
    onSetMeetingPoint: ((GeoPoint) -> Unit)?,
    onDismiss: () -> Unit,
    label: String? = null,
) {
    val plan by nav.plan.collectAsState()
    // A long-pressed place gets a name from an online lookup, so the plan reads "Nandi Hills" instead of numbers.
    var name by remember(p) { mutableStateOf(label) }
    LaunchedEffect(p) { if (name == null) name = IosPlaceSearch.nameOf(p) }
    fun choose(action: () -> Unit) { nav.setLabel(p, name); action(); onDismiss() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name ?: "Add to your ride plan") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    (if (name != null) "Add to your ride plan · " else "") + "${TextUtil.fixed(p.lat, 4)}, ${TextUtil.fixed(p.lon, 4)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(4.dp))
                FilledTonalButton(onClick = { choose { nav.chooseDestination(p) } }, modifier = Modifier.fillMaxWidth()) { Text("End here (destination)") }
                FilledTonalButton(onClick = { choose { nav.addStop(p) } }, modifier = Modifier.fillMaxWidth()) {
                    Text("Add as stop ${plan.stops.size + 1}")
                }
                OutlinedButton(onClick = { choose { nav.setStart(p) } }, modifier = Modifier.fillMaxWidth()) { Text("Start here instead of my location") }
                if (onSetMeetingPoint != null) {
                    OutlinedButton(onClick = { onSetMeetingPoint(p); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Set meeting point for the group")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Your rides, newest first, with distance, riding time and speeds. Kept only on this phone. Like Android. */
@Composable
internal fun RideHistoryScreen(rides: RideLog, bikeName: (Long) -> String? = { null }) {
    val list by rides.rides.collectAsState()
    var confirmDelete by remember { mutableStateOf<SavedRide?>(null) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Your rides", style = MaterialTheme.typography.titleLarge)
            Text(
                "Recorded automatically while you navigate or ride with a group. Stored only on this phone.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (list.isEmpty()) {
            item {
                Text("No rides yet. Start navigation or join a group ride and your ride will appear here.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            item {
                val m = list.sumOf { it.stats.distanceMeters }
                val ms = list.sumOf { it.stats.movingMillis }
                Text(
                    "${list.size} ride${if (list.size == 1) "" else "s"} · ${formatDistance(m)} · ${formatDuration(ms)} riding",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(list, key = { it.id }) { r -> RideCard(r, bikeName(r.bikeId), onDelete = { confirmDelete = r }) }
        }
    }
    confirmDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this ride?") },
            text = { Text(r.title.ifBlank { "This ride" } + " will be removed from this phone.") },
            confirmButton = { TextButton(onClick = { rides.delete(r.id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RideCard(r: SavedRide, bike: String?, onDelete: () -> Unit) {
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
                val k = cos((minLat + maxLat) / 2 * PI / 180)
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
                Text(formatRideDate(r.id), style = MaterialTheme.typography.bodySmall)
                Text(
                    "${formatDistance(s.distanceMeters)} · ${formatDuration(s.movingMillis)} riding · ${formatDuration(s.totalMillis)} total",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Average ${s.avgMovingKmh.toInt()} km/h · Top ${s.topKmh.toInt()} km/h", style = MaterialTheme.typography.bodySmall)
                if (bike != null) Text("On $bike", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete ride") }
        }
    }
}
