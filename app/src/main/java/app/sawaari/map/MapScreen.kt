package app.sawaari.map

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.sawaari.geo.GeoPoint
import app.sawaari.location.locationUpdates
import app.sawaari.nav.NavigationController
import app.sawaari.nav.RidePlan
import app.sawaari.places.PlaceCategory
import app.sawaari.places.PlaceDialog
import app.sawaari.places.PlaceMatcher
import app.sawaari.places.PlaceOnRoute
import app.sawaari.places.PlacesPanel
import app.sawaari.places.PlacesRepository
import app.sawaari.places.PlaceSearch
import app.sawaari.places.MapSearchBar
import app.sawaari.share.RouteImportException
import app.sawaari.share.RouteImporter
import app.sawaari.share.RouteShare
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.sawaari.group.MeetingPoint
import app.sawaari.group.MeetingPointDialog
import app.sawaari.group.RiderDetailsDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.IconButton
import app.sawaari.nav.formatDistance
import app.sawaari.nav.formatDuration
import app.sawaari.nav.iconForSign
import app.sawaari.routing.RoutingRepository
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import app.sawaari.group.Rider
import app.sawaari.group.SosAlert
import androidx.compose.material3.FloatingActionButton
import androidx.compose.ui.graphics.Color
import app.sawaari.group.Role
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val SRC_ROUTE = "sawaari-route"
private const val SRC_ME = "sawaari-me"
private const val SRC_DEST = "sawaari-dest"
private const val SRC_RIDERS = "sawaari-riders"
private const val SRC_POIS = "sawaari-pois"

/**
 * Full-screen map. Works offline for downloaded areas.
 * Long-press to pick a destination, plan a route offline, then start turn-by-turn navigation.
 */
@Composable
fun MapScreen(
    routing: RoutingRepository,
    onDownloadArea: (name: String, bounds: LatLngBounds) -> Unit,
    modifier: Modifier = Modifier,
    groupRiders: List<Rider> = emptyList(),
    myUid: String? = null,
    onLocation: (android.location.Location) -> Unit = {},
    onPositionChanged: (GeoPoint?) -> Unit = {},
    sosAlerts: List<SosAlert> = emptyList(),
    onDismissSos: (String) -> Unit = {},
    onSosPressed: () -> Unit = {},
    groupPlan: RidePlan? = null,
    groupPlanBy: String = "",
    iAmLeader: Boolean = false,
    onSharePlan: ((RidePlan) -> Unit)? = null,
    meet: MeetingPoint? = null,
    onSetMeetingPoint: ((GeoPoint, Long, String) -> Unit)? = null,
    onReroute: () -> Unit = {},
    onGroupRoute: (List<GeoPoint>?) -> Unit = {},
    rideTo: GeoPoint? = null,
    onRideToHandled: () -> Unit = {},
    places: PlacesRepository? = null,
    importLink: String? = null,
    onImportHandled: () -> Unit = {},
    onTourGuide: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nav = remember { NavigationController(context, routing, scope) }
    DisposableEffect(nav) { onDispose { nav.shutdown() } }
    nav.onReroute = onReroute
    LaunchedEffect(rideTo) {
        rideTo?.let { nav.loadPlan(RidePlan(end = it)); onRideToHandled() }
    }
    // While riding the group's shared plan, publish the route line so alerts can spot riders off it.
    LaunchedEffect(nav.navigating, nav.route, groupPlan, nav.plan) {
        onGroupRoute(if (nav.navigating && groupPlan != null && nav.plan == groupPlan) nav.route?.points else null)
    }
    var tappedRider by remember { mutableStateOf<String?>(null) }

    var meetAt by remember { mutableStateOf<GeoPoint?>(null) }

    val mapView = rememberMapViewWithLifecycle()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var pendingBounds by remember { mutableStateOf<LatLngBounds?>(null) }
    var pressedPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var pressedLabel by remember { mutableStateOf<String?>(null) }
    val placeSearch = remember(places) { places?.let { PlaceSearch(it) } }
    val importer = remember(placeSearch) { placeSearch?.let { RouteImporter(it) } }
    fun importRoute(text: String) {
        val imp = importer ?: return
        scope.launch {
            nav.message = "Opening the shared route…"
            try {
                val result = imp.import(text, nav.myPosition ?: map?.cameraPosition?.target?.let { GeoPoint(it.latitude, it.longitude) })
                result.names.forEach { (p, n) -> nav.setLabel(p, n) }
                nav.loadPlan(result.plan)
                val n = result.plan.stops.size
                nav.message = "Route loaded" + (if (n > 0) " with $n stop${if (n > 1) "s" else ""}" else "") +
                    (if (result.skipped > 0) ". ${result.skipped} place(s) couldn't be found." else ".")
            } catch (e: RouteImportException) {
                nav.message = e.message
            } catch (e: Exception) {
                nav.message = "Couldn't open that link."
            }
        }
    }
    LaunchedEffect(importLink, importer) {
        if (importLink != null && importer != null) { importRoute(importLink); onImportHandled() }
    }
    var hiddenPlan by remember { mutableStateOf<RidePlan?>(null) }
    val snackbar = remember { SnackbarHostState() }

    // Places along the route (food, fuel, mechanics, tyres, hospitals).
    val visiblePlacesRef = remember { object { var value: List<PlaceOnRoute> = emptyList() } }
    var placeCategory by remember { mutableStateOf<PlaceCategory?>(null) }
    var placesOnRoute by remember { mutableStateOf<List<PlaceOnRoute>>(emptyList()) }
    var placesLoading by remember { mutableStateOf(false) }
    var openPlace by remember { mutableStateOf<PlaceOnRoute?>(null) }
    LaunchedEffect(nav.route, placeCategory) {
        val r = nav.route
        val cat = placeCategory
        if (r == null || cat == null || places == null) { placesOnRoute = emptyList(); return@LaunchedEffect }
        placesLoading = true
        placesOnRoute = withContext(Dispatchers.Default) {
            val all = places.placesNear(r.points.first()) + places.placesNear(r.points.last())
            PlaceMatcher.alongRoute(r, all.distinctBy { it.id }.filter { it.category == cat })
        }
        placesLoading = false
    }
    val currentAlong = if (nav.navigating) nav.route?.let { r -> nav.progress?.let { r.geometryLength - it.distanceRemaining } } else null
    val visiblePlaces = remember(placesOnRoute, currentAlong?.let { (it / 500).toInt() }) {
        (if (currentAlong != null) placesOnRoute.filter { it.alongMeters >= currentAlong - 200 } else placesOnRoute).take(40)
    }
    visiblePlacesRef.value = visiblePlaces
    LaunchedEffect(style, visiblePlaces) {
        val features = visiblePlaces.map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.place.point.lon, p.place.point.lat)).apply {
                addStringProperty("id", p.place.id)
                addStringProperty("label", p.place.category.letter)
                addStringProperty("color", p.place.category.color)
            }
        }
        style?.getSourceAs<GeoJsonSource>(SRC_POIS)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    // Location permission and GPS updates.
    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    var permitted by remember { mutableStateOf(hasPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permitted = it.values.any { granted -> granted }
    }
    LaunchedEffect(Unit) {
        if (!permitted) permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    LaunchedEffect(permitted) {
        if (!permitted) return@LaunchedEffect
        if (!isLocationEnabled(context)) nav.message = "Location is turned off. Turn it on in quick settings to see where you are."
        locationUpdates(context).collect { nav.onLocation(it); onLocation(it) }
    }

    // Jump to the rider the first time we get a fix.
    var centeredOnce by remember { mutableStateOf(false) }
    LaunchedEffect(map, nav.location) {
        val m = map ?: return@LaunchedEffect
        val p = nav.myPosition ?: return@LaunchedEffect
        if (!centeredOnce && !nav.navigating) {
            centeredOnce = true
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lon), 15.0), 1200)
        }
    }

    // Keep the screen on while navigating.
    val view = LocalView.current
    DisposableEffect(nav.navigating) {
        view.keepScreenOn = nav.navigating
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(nav.message) {
        nav.message?.let { snackbar.showSnackbar(it); nav.message = null }
    }

    // Draw route, rider and destination.
    LaunchedEffect(style, nav.route) {
        val s = style ?: return@LaunchedEffect
        val r = nav.route
        s.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(
            if (r == null || r.points.size < 2) FeatureCollection.fromFeatures(emptyList<Feature>())
            else FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(LineString.fromLngLats(r.points.map { Point.fromLngLat(it.lon, it.lat) }))))
        )
        if (r != null && !nav.navigating && r.points.size >= 2) {
            val b = LatLngBounds.Builder().apply { r.points.forEach { include(LatLng(it.lat, it.lon)) } }.build()
            map?.animateCamera(CameraUpdateFactory.newLatLngBounds(b, 120), 800)
        }
    }
    LaunchedEffect(style, nav.location, nav.progress) {
        val s = style ?: return@LaunchedEffect
        val p = if (nav.navigating) nav.progress?.snapped ?: nav.myPosition else nav.myPosition
        s.getSourceAs<GeoJsonSource>(SRC_ME)?.setGeoJson(pointOrEmpty(p))
        val loc = nav.location
        if (nav.navigating && p != null) {
            val bearing = if (loc != null && loc.hasBearing() && loc.speed > 2f) loc.bearing.toDouble()
            else nav.progress?.routeBearing ?: 0.0
            map?.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(LatLng(p.lat, p.lon)).zoom(16.5).bearing(bearing).tilt(45.0).build()
                ),
                900,
            )
        }
    }
    LaunchedEffect(nav.location) { onPositionChanged(nav.myPosition) }

    // A new SOS from the group: vibrate hard and say it out loud.
    LaunchedEffect(sosAlerts.firstOrNull()?.id) {
        val a = sosAlerts.firstOrNull() ?: return@LaunchedEffect
        vibrateAlarm(context)
        nav.announce("Emergency. ${a.name} has sent an S O S.")
    }
    LaunchedEffect(style, groupRiders, myUid) {
        val s = style ?: return@LaunchedEffect
        val now = System.currentTimeMillis()
        val features = groupRiders.filter { it.uid != myUid && it.lat != null && it.lon != null && now - it.updatedAt < 6 * 60 * 60_000L }
            .map { r ->
                val ageMin = (now - r.updatedAt) / 60_000
                Feature.fromGeometry(Point.fromLngLat(r.lon!!, r.lat!!)).apply {
                    addStringProperty("label", if (ageMin >= 2) "${r.name} (${ageMin}m ago)" else r.name)
                    addStringProperty("uid", r.uid)
                    addStringProperty("color", when (r.role) { Role.LEADER -> "#2E7D32"; Role.SWEEP -> "#EF6C00"; else -> "#7B1FA2" })
                }
            }
        s.getSourceAs<GeoJsonSource>(SRC_RIDERS)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }
    LaunchedEffect(style, nav.plan, nav.stopsReached, meet) {
        style?.getSourceAs<GeoJsonSource>(SRC_DEST)?.setGeoJson(planFeatures(nav.plan, nav.stopsReached, meet?.point))
    }

    Box(modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                mapView.apply {
                    getMapAsync { m ->
                        m.setStyle(Style.Builder().fromUri(MapConfig.STYLE_URL)) { s ->
                            addOverlayLayers(s)
                            style = s
                        }
                        m.cameraPosition = CameraPosition.Builder()
                            .target(LatLng(MapConfig.DEFAULT_LAT, MapConfig.DEFAULT_LON))
                            .zoom(MapConfig.DEFAULT_ZOOM)
                            .build()
                        m.addOnMapClickListener { ll ->
                            val px = m.projection.toScreenLocation(ll)
                            val box = android.graphics.RectF(px.x - 40, px.y - 40, px.x + 40, px.y + 40)
                            val hit = m.queryRenderedFeatures(box, "sawaari-riders").firstOrNull()?.getStringProperty("uid")
                            if (hit != null) tappedRider = hit
                            val poi = if (hit == null) m.queryRenderedFeatures(box, "sawaari-pois").firstOrNull()?.getStringProperty("id") else null
                            if (poi != null) openPlace = visiblePlacesRef.value.firstOrNull { it.place.id == poi }
                            hit != null || poi != null
                        }
                        m.addOnMapLongClickListener { ll ->
                            if (!nav.navigating) { pressedLabel = null; pressedPoint = GeoPoint(ll.latitude, ll.longitude) }
                            true
                        }
                        map = m
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (nav.navigating) NavigationBanner(nav)
            if (!nav.navigating && placeSearch != null) {
                MapSearchBar(
                    search = placeSearch,
                    near = { map?.cameraPosition?.target?.let { GeoPoint(it.latitude, it.longitude) } ?: nav.myPosition },
                    onPick = { r ->
                        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(r.point.lat, r.point.lon), 13.0), 800)
                        nav.setLabel(r.point, r.name)
                        pressedLabel = r.name
                        pressedPoint = r.point
                    },
                    onLink = ::importRoute,
                    onOpenLinkSettings = { openLinkSettings(context) },
                )
            }
            if (groupPlan != null && !iAmLeader && !nav.navigating && groupPlan != nav.plan && groupPlan != hiddenPlan) {
                SharedPlanBanner(
                    plan = groupPlan,
                    by = groupPlanBy,
                    onLoad = { nav.loadPlan(groupPlan); hiddenPlan = groupPlan },
                    onHide = { hiddenPlan = groupPlan },
                )
            }
            sosAlerts.take(2).forEach { a ->
                SosBanner(
                    alert = a,
                    myPosition = nav.myPosition,
                    onNavigate = {
                        if (a.lat != null && a.lon != null) {
                            nav.loadPlan(RidePlan(end = GeoPoint(a.lat, a.lon)))
                        }
                    },
                    onDismiss = { onDismissSos(a.id) },
                )
            }
        }

        if (!nav.navigating) {
            SmallFloatingActionButton(
                onClick = {
                    val p = nav.myPosition
                    if (!permitted) permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    else if (!isLocationEnabled(context)) nav.message = "Location is turned off. Turn it on in quick settings."
                    else if (p == null) nav.message = "Finding your location… this can take a minute outdoors."
                    else map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lon), 14.0), 800)
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 92.dp, end = 16.dp),
            ) { Icon(Icons.Default.MyLocation, contentDescription = "My location") }
            if (onTourGuide != null) SmallFloatingActionButton(
                onClick = onTourGuide,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 148.dp, end = 16.dp),
            ) { Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "Tour guide") }
        }


        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SnackbarHost(snackbar)
            if (nav.route != null && places != null) {
                PlacesPanel(
                    selected = placeCategory, onSelect = { placeCategory = it },
                    places = visiblePlaces, loading = placesLoading, currentAlong = currentAlong,
                    repo = places, onOpen = { openPlace = it },
                )
                Spacer(Modifier.size(8.dp))
            }
            when {
                nav.navigating -> Button(onClick = { nav.stop() }) {
                    Icon(Icons.Default.Close, null); Spacer(Modifier.width(8.dp)); Text("End navigation")
                }
                !nav.plan.isEmpty -> RouteCard(
                    nav, onShare = if (iAmLeader && onSharePlan != null) onSharePlan else null, sharedPlan = groupPlan,
                    onShareRoute = { scope.launch { RouteShare.share(context, map, nav.plan, nav.route, nav::labelFor) } },
                )
                else -> SaveAreaCard(onSave = { pendingBounds = map?.projection?.visibleRegion?.latLngBounds })
            }
        }
    }

    pressedPoint?.let { p ->
        AlertDialog(
            onDismissRequest = { pressedPoint = null },
            title = { Text(pressedLabel ?: "Add to your ride plan") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        (if (pressedLabel != null) "Add to your ride plan · " else "") + "%.4f, %.4f".format(java.util.Locale.US, p.lat, p.lon),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.size(4.dp))
                    FilledTonalButton(onClick = { nav.chooseDestination(p); pressedPoint = null }, modifier = Modifier.fillMaxWidth()) { Text("End here (destination)") }
                    FilledTonalButton(onClick = { nav.addStop(p); pressedPoint = null }, modifier = Modifier.fillMaxWidth()) {
                        Text("Add as stop ${nav.plan.stops.size + 1}")
                    }
                    OutlinedButton(onClick = { nav.setStart(p); pressedPoint = null }, modifier = Modifier.fillMaxWidth()) { Text("Start here instead of my location") }
                    if (onSetMeetingPoint != null) {
                        OutlinedButton(onClick = { meetAt = p; pressedPoint = null }, modifier = Modifier.fillMaxWidth()) { Text("Set meeting point for the group") }
                    }
                    TextButton(
                        onClick = { openInGoogleMaps(context, p, pressedLabel); pressedPoint = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Icon(Icons.Default.Map, null); Spacer(Modifier.width(8.dp)); Text("Open in Google Maps") }
                }
            },
            confirmButton = { TextButton(onClick = { pressedPoint = null }) { Text("Cancel") } },
        )
    }

    openPlace?.let { item ->
        if (places != null) PlaceDialog(
            item, places, nav.navigating,
            onAddStop = { nav.addStopAhead(item.place.point) },
            onDismiss = { openPlace = null },
        )
    }
    meetAt?.let { p ->
        MeetingPointDialog(p, onSave = { time, note -> onSetMeetingPoint?.invoke(p, time, note) }, onDismiss = { meetAt = null })
    }
    tappedRider?.let { uid ->
        val r = groupRiders.firstOrNull { it.uid == uid }
        if (r == null) tappedRider = null
        else RiderDetailsDialog(
            r, nav.myPosition,
            onNavigate = if (r.lat != null && r.lon != null) ({ nav.loadPlan(RidePlan(end = GeoPoint(r.lat, r.lon))) }) else null,
            onDismiss = { tappedRider = null },
        )
    }

    pendingBounds?.let { bounds ->
        var name by remember { mutableStateOf("My ride area") }
        AlertDialog(
            onDismissRequest = { pendingBounds = null },
            title = { Text("Save this area offline") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }) },
            confirmButton = {
                TextButton(onClick = {
                    onDownloadArea(name.ifBlank { "My ride area" }, bounds)
                    pendingBounds = null
                }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { pendingBounds = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RouteCard(nav: NavigationController, onShare: ((RidePlan) -> Unit)?, sharedPlan: RidePlan?, onShareRoute: () -> Unit) {
    val plan = nav.plan
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val r = nav.route
            if (r == null) {
                Text("Ride plan", style = MaterialTheme.typography.titleMedium)
            } else {
                Text("${formatDistance(r.distanceMeters)} · ${formatDuration(r.timeMillis)}", style = MaterialTheme.typography.titleLarge)
            }
            // Start, stops, destination, each removable.
            PlanRow("S", PLAN_START, if (plan.start == null) "Start: my location" else "Start: ${nav.labelFor(plan.start) ?: coord(plan.start)}",
                onRemove = plan.start?.let { { nav.setStart(null) } })
            plan.stops.forEachIndexed { i, p ->
                PlanRow("${i + 1}", PLAN_STOP, "Stop ${i + 1}: ${nav.labelFor(p) ?: coord(p)}", onRemove = { nav.removeStop(i) })
            }
            PlanRow("E", PLAN_END, plan.end?.let { "Destination: ${nav.labelFor(it) ?: coord(it)}" } ?: "Destination: search above, or long-press the map",
                onRemove = plan.end?.let { { nav.clearEnd() } })
            Text(
                if (r == null) "Search above or long-press the map to add stops. Routes are calculated on your phone, no signal needed."
                else "Fastest route through your stops, calculated offline",
                style = MaterialTheme.typography.bodySmall,
            )
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
            if (plan.end != null) {
                TextButton(onClick = onShareRoute) {
                    Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text("Share route (WhatsApp etc.)")
                }
            }
            if (onShare != null && plan.end != null) {
                val shared = sharedPlan == plan
                TextButton(onClick = { onShare(plan) }, enabled = !shared) {
                    Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp))
                    Text(if (shared) "Shared with your group" else "Share plan with group")
                }
            }
        }
    }
}

@Composable
private fun PlanRow(badge: String, color: String, text: String, onRemove: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(24.dp).background(Color(android.graphics.Color.parseColor(color)), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(badge, color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (onRemove != null) IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, "Remove") }
    }
}

@Composable
private fun SharedPlanBanner(plan: RidePlan, by: String, onLoad: () -> Unit, onHide: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${by.ifBlank { "Your leader" }} shared a ride plan", style = MaterialTheme.typography.titleMedium)
            Text("${plan.stops.size} stop${if (plan.stops.size == 1) "" else "s"} and a destination", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onLoad) { Text("Ride this plan") }
                TextButton(onClick = onHide) { Text("Not now") }
            }
        }
    }
}

private fun coord(p: GeoPoint) = "%.4f, %.4f".format(java.util.Locale.US, p.lat, p.lon)

private const val PLAN_START = "#2E7D32"
private const val PLAN_STOP = "#F9A825"
private const val PLAN_END = "#E53935"
private const val PLAN_DONE = "#9E9E9E"
private const val PLAN_MEET = "#6A1B9A"

/** Numbered markers for the plan: S, 1, 2, ..., E. Stops already reached are grey. */
private fun planFeatures(plan: RidePlan, reached: Int, meet: GeoPoint?): FeatureCollection {
    val f = mutableListOf<Feature>()
    fun add(p: GeoPoint, label: String, color: String) {
        f += Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
            addStringProperty("label", label); addStringProperty("color", color)
        }
    }
    plan.start?.let { add(it, "S", PLAN_START) }
    plan.stops.forEachIndexed { i, p -> add(p, "${i + 1}", if (i < reached) PLAN_DONE else PLAN_STOP) }
    plan.end?.let { add(it, "E", PLAN_END) }
    meet?.let { add(it, "M", PLAN_MEET) }
    return FeatureCollection.fromFeatures(f)
}

@Composable
private fun SosBanner(alert: SosAlert, myPosition: GeoPoint?, onNavigate: () -> Unit, onDismiss: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("SOS from ${alert.name}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            val ageMin = ((System.currentTimeMillis() - alert.ts) / 60_000).coerceAtLeast(0)
            val where = if (alert.lat != null && alert.lon != null && myPosition != null)
                "${formatDistance(app.sawaari.geo.distanceMeters(myPosition, GeoPoint(alert.lat, alert.lon)))} away · " else ""
            Text("$where${if (ageMin < 1) "just now" else "$ageMin min ago"}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (alert.lat != null && alert.lon != null) {
                    Button(
                        onClick = onNavigate,
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFD32F2F)),
                    ) { Text("Go to rider") }
                }
                OutlinedButton(onClick = onDismiss) { Text("Dismiss", color = Color.White) }
            }
        }
    }
}

private fun vibrateAlarm(context: android.content.Context) {
    runCatching {
        val pattern = longArrayOf(0, 600, 200, 600, 200, 600)
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            (context.getSystemService(android.os.VibratorManager::class.java)).defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(android.os.Vibrator::class.java)
        vibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
    }
}

@Composable
private fun NavigationBanner(nav: NavigationController, modifier: Modifier = Modifier) {
    val route = nav.route ?: return
    val p = nav.progress
    val step = p?.let { route.steps.getOrNull(it.nextStepIndex) } ?: route.steps.firstOrNull() ?: return
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(iconForSign(step.sign), contentDescription = null, modifier = Modifier.size(56.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    p?.let { formatDistance(it.distanceToNextStep) } ?: "",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(step.text, style = MaterialTheme.typography.titleMedium)
                p?.let {
                    val secondsLeft = if (route.distanceMeters > 0) route.timeMillis * (it.distanceRemaining / route.distanceMeters) else 0.0
                    Text(
                        "${formatDistance(it.distanceRemaining)} left · ${formatDuration(secondsLeft.toLong())}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun isLocationEnabled(context: android.content.Context): Boolean {
    val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
    return androidx.core.location.LocationManagerCompat.isLocationEnabled(lm)
}

private fun pointOrEmpty(p: GeoPoint?): FeatureCollection =
    if (p == null) FeatureCollection.fromFeatures(emptyList<Feature>())
    else FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat))))

private fun addOverlayLayers(style: Style) {
    style.addSource(GeoJsonSource(SRC_ROUTE))
    style.addSource(GeoJsonSource(SRC_DEST))
    style.addSource(GeoJsonSource(SRC_ME))
    style.addSource(GeoJsonSource(SRC_RIDERS))
    style.addSource(GeoJsonSource(SRC_POIS))
    style.addLayer(
        LineLayer("sawaari-route-casing", SRC_ROUTE).withProperties(
            PropertyFactory.lineColor("#0D47A1"),
            PropertyFactory.lineWidth(10f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
    )
    style.addLayer(
        LineLayer("sawaari-route-line", SRC_ROUTE).withProperties(
            PropertyFactory.lineColor("#42A5F5"),
            PropertyFactory.lineWidth(6f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
    )
    style.addLayer(
        CircleLayer("sawaari-pois", SRC_POIS).withProperties(
            PropertyFactory.circleRadius(10f),
            PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2f),
        )
    )
    style.addLayer(
        SymbolLayer("sawaari-poi-labels", SRC_POIS).withProperties(
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(11f),
            PropertyFactory.textColor("#FFFFFF"),
            PropertyFactory.textAllowOverlap(true),
            PropertyFactory.textIgnorePlacement(true),
        )
    )
    style.addLayer(
        CircleLayer("sawaari-dest", SRC_DEST).withProperties(
            PropertyFactory.circleRadius(12f),
            PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(3f),
        )
    )
    style.addLayer(
        SymbolLayer("sawaari-dest-labels", SRC_DEST).withProperties(
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(12f),
            PropertyFactory.textColor("#FFFFFF"),
            PropertyFactory.textAllowOverlap(true),
            PropertyFactory.textIgnorePlacement(true),
        )
    )
    style.addLayer(
        CircleLayer("sawaari-riders", SRC_RIDERS).withProperties(
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2.5f),
        )
    )
    style.addLayer(
        SymbolLayer("sawaari-rider-names", SRC_RIDERS).withProperties(
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(13f),
            PropertyFactory.textOffset(arrayOf(0f, 1.3f)),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textColor("#212121"),
            PropertyFactory.textHaloColor("#FFFFFF"),
            PropertyFactory.textHaloWidth(1.5f),
            PropertyFactory.textAllowOverlap(true),
            PropertyFactory.textIgnorePlacement(true),
        )
    )
    style.addLayer(
        CircleLayer("sawaari-me", SRC_ME).withProperties(
            PropertyFactory.circleRadius(9f),
            PropertyFactory.circleColor("#1E88E5"),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(3f),
        )
    )
}

@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember { MapView(context).apply { onCreate(Bundle()) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return mapView
}

/** Android 12+: the page where the rider lets Sawaari open Google Maps links. */
private fun openLinkSettings(context: android.content.Context) {
    val pkg = android.net.Uri.parse("package:${context.packageName}")
    val intent = if (android.os.Build.VERSION.SDK_INT >= 31)
        android.content.Intent(android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, pkg)
    else android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
    runCatching { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}


/** Opens a place in the Google Maps app (or the website when the app isn't installed). */
private fun openInGoogleMaps(context: android.content.Context, p: GeoPoint, @Suppress("UNUSED_PARAMETER") name: String?) {
    val q = "%.6f,%.6f".format(java.util.Locale.US, p.lat, p.lon)
    // Coordinates find the exact spot; the name alone can match a different branch or town.
    val url = "https://www.google.com/maps/search/?api=1&query=" + android.net.Uri.encode(q)
    val app = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).setPackage("com.google.android.apps.maps")
    val web = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
    runCatching { context.startActivity(app) }.onFailure { runCatching { context.startActivity(web) } }
}

/** Idle-state hint plus a compact pill for saving the visible map for riding without signal. */
@Composable
private fun SaveAreaCard(onSave: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            shadowElevation = 1.dp,
        ) {
            Text(
                "Search or long-press the map to plan a ride",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        Surface(
            onClick = onSave,
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 4.dp,
        ) {
            Row(Modifier.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.CloudDownload, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                Spacer(Modifier.width(8.dp))
                Text("Save this map for offline use", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
