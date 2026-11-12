package app.sawaari.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.filled.Groups
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sawaari.geo.GeoPoint
import app.sawaari.geo.distanceMeters
import app.sawaari.nav.NavigationController
import app.sawaari.nav.RidePlan
import app.sawaari.nav.formatDistance
import app.sawaari.nav.formatDuration
import app.sawaari.places.PlaceNames
import app.sawaari.places.PlacesRepository

private const val START_COLOR = "#2E7D32"
private const val STOP_COLOR = "#F9A825"
private const val END_COLOR = "#E53935"
private const val PROBLEM_COLOR = "#C62828"

/** Roads wind: a rough factor from straight-line to road distance, and an easy group riding speed. */
private const val ROAD_FACTOR = 1.3
private const val GROUP_KMH = 40.0

/**
 * The ride plan: start, stops and destination with names, a distance estimate, round trip, and
 * red marks on places that can't be routed offline. Swipe down (or tap the arrow) to shrink it.
 */
@Composable
internal fun RouteCard(
    nav: NavigationController,
    places: PlacesRepository?,
    onShare: ((RidePlan) -> Unit)?,
    sharedPlan: RidePlan?,
    onShareRoute: () -> Unit,
    onFindNearby: (String) -> Unit,
    onCreateGroup: (() -> Unit)? = null,
) {
    val plan = nav.plan
    val r = nav.route
    var expanded by rememberSaveable { mutableStateOf(true) }
    Card(
        Modifier.fillMaxWidth().pointerInput(Unit) {
            var drag = 0f
            detectVerticalDragGestures(
                onDragStart = { drag = 0f },
                onDragEnd = { if (drag > 40f) expanded = false else if (drag < -40f) expanded = true },
            ) { _, dy -> drag += dy }
        },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Handle, title, summary.
            Box(
                Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { expanded = !expanded }) {
                Column(Modifier.weight(1f)) {
                    if (r != null) {
                        Text("${formatDistance(r.distanceMeters)} · ${formatDuration(r.timeMillis)}", style = MaterialTheme.typography.titleLarge)
                    } else {
                        Text("Ride plan", style = MaterialTheme.typography.titleMedium)
                        estimate(plan, nav.myPosition)?.let { (m, ms) ->
                            Text("About ${formatDistance(m)} · ${formatDuration(ms)} (rough estimate)", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (!expanded) {
                        val n = plan.stops.size
                        Text(
                            (if (n == 0) "No stops" else "$n stop${if (n == 1) "" else "s"}") + if (nav.problems.isNotEmpty()) " · needs a fix" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (nav.problems.isNotEmpty()) Color(android.graphics.Color.parseColor(PROBLEM_COLOR)) else Color.Unspecified,
                        )
                    }
                }
                Icon(if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp, if (expanded) "Shrink" else "Expand")
            }

            if (expanded) {
                val startText = when {
                    plan.start == null -> "Your location"
                    nav.myPosition?.let { distanceMeters(it, plan.start) < 200 } == true -> "Your location"
                    else -> pointName(plan.start, nav, places)
                }
                PlanRow("S", START_COLOR, "Start: $startText", plan.start?.let { nav.problems[it] },
                    onRemove = plan.start?.let { { nav.setStart(null) } })
                plan.stops.forEachIndexed { i, p ->
                    val name = pointName(p, nav, places)
                    PlanRow(
                        "${i + 1}", STOP_COLOR, "Stop ${i + 1}: $name", nav.problems[p],
                        onRemove = { nav.removeStop(i) },
                        onUp = if (i > 0) ({ nav.moveStop(i, i - 1) }) else null,
                        onDown = if (i < plan.stops.lastIndex) ({ nav.moveStop(i, i + 1) }) else null,
                        onFindNearby = nav.labelFor(p)?.let { label -> { nav.removeStop(i); onFindNearby(label) } },
                    )
                }
                val end = plan.end
                PlanRow(
                    "E", END_COLOR,
                    when {
                        end == null -> "Destination: search above, or long-press the map"
                        nav.isRoundTrip -> "Destination: back to the start"
                        else -> "Destination: ${pointName(end, nav, places)}"
                    },
                    end?.let { nav.problems[it] },
                    onRemove = end?.let { { nav.clearEnd() } },
                    onFindNearby = end?.let { e -> nav.labelFor(e)?.let { label -> { nav.clearEnd(); onFindNearby(label) } } },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Round trip (come back to the start)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = nav.isRoundTrip, onCheckedChange = { nav.makeRoundTrip(it) })
                }
                Text(
                    if (r == null) "Search above or long-press the map to add stops. Routes are calculated on your phone, no signal needed."
                    else "Fastest route through your stops, calculated offline",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (r == null) {
                    Button(onClick = { nav.planRoute() }, enabled = !nav.busy && plan.end != null) {
                        if (nav.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
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
                TextButton(onClick = onShareRoute) {
                    Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text("Share route (WhatsApp etc.)")
                }
            }
            if (expanded && onShare != null && plan.end != null) {
                val shared = sharedPlan == plan
                TextButton(onClick = { onShare(plan) }, enabled = !shared) {
                    Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp))
                    Text(if (shared) "Shared with your group" else "Share plan with group")
                }
            }
            if (expanded && onCreateGroup != null && plan.end != null) {
                androidx.compose.material3.FilledTonalButton(onClick = onCreateGroup, modifier = Modifier.fillMaxWidth()) {
                    Icon(androidx.compose.material.icons.Icons.Default.Groups, null); Spacer(Modifier.width(8.dp))
                    Text("Riding with friends? Create a group for this route")
                }
            }
        }
    }
}

/** The name from search or a link, else the nearest town from the places file, else coordinates. */
@Composable
private fun pointName(p: GeoPoint, nav: NavigationController, places: PlacesRepository?): String {
    val label = nav.labelFor(p)
    val town by produceState<String?>(null, p, places, label) {
        value = if (label != null) null else places?.let { runCatching { PlaceNames.describe(p, it.placesNear(p)) }.getOrNull() }
    }
    return label ?: town ?: "%.4f, %.4f".format(java.util.Locale.US, p.lat, p.lon)
}

/** Straight-line distance through the plan, stretched for winding roads, and time at group pace. */
private fun estimate(plan: RidePlan, me: GeoPoint?): Pair<Double, Long>? {
    val pts = plan.waypoints(me) ?: return null
    val m = pts.zipWithNext { a, b -> distanceMeters(a, b) }.sum() * ROAD_FACTOR
    if (m < 100) return null
    return m to (m / 1000.0 / GROUP_KMH * 3_600_000).toLong()
}

@Composable
private fun PlanRow(
    badge: String,
    color: String,
    text: String,
    problem: String?,
    onRemove: (() -> Unit)?,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onFindNearby: (() -> Unit)? = null,
) {
    val red = Color(android.graphics.Color.parseColor(PROBLEM_COLOR))
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(24.dp).background(if (problem != null) red else Color(android.graphics.Color.parseColor(color)), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text(badge, color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(12.dp))
            Text(
                text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                color = if (problem != null) red else Color.Unspecified, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (onUp != null) IconButton(onClick = onUp, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
            if (onDown != null) IconButton(onClick = onDown, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
            if (onRemove != null) IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, "Remove") }
        }
        if (problem != null) {
            Column(Modifier.padding(start = 36.dp)) {
                Text(problem, style = MaterialTheme.typography.bodySmall, color = red)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onRemove != null) TextButton(onClick = onRemove) { Text("Remove") }
                    if (onFindNearby != null) TextButton(onClick = onFindNearby) {
                        Icon(Icons.Default.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Find nearby")
                    }
                }
            }
        }
    }
}
