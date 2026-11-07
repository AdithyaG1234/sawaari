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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nav = remember { NavigationController(context, routing, scope) }
    DisposableEffect(nav) { onDispose { nav.shutdown() } }

    val mapView = rememberMapViewWithLifecycle()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var pendingBounds by remember { mutableStateOf<LatLngBounds?>(null) }
    val snackbar = remember { SnackbarHostState() }

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
    LaunchedEffect(style, groupRiders, myUid) {
        val s = style ?: return@LaunchedEffect
        val now = System.currentTimeMillis()
        val features = groupRiders.filter { it.uid != myUid && it.lat != null && it.lon != null && now - it.updatedAt < 6 * 60 * 60_000L }
            .map { r ->
                val ageMin = (now - r.updatedAt) / 60_000
                Feature.fromGeometry(Point.fromLngLat(r.lon!!, r.lat!!)).apply {
                    addStringProperty("label", if (ageMin >= 2) "${r.name} (${ageMin}m ago)" else r.name)
                    addStringProperty("color", when (r.role) { Role.LEADER -> "#2E7D32"; Role.SWEEP -> "#EF6C00"; else -> "#7B1FA2" })
                }
            }
        s.getSourceAs<GeoJsonSource>(SRC_RIDERS)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }
    LaunchedEffect(style, nav.destination) {
        style?.getSourceAs<GeoJsonSource>(SRC_DEST)?.setGeoJson(pointOrEmpty(nav.destination))
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
                        m.addOnMapLongClickListener { ll ->
                            nav.chooseDestination(GeoPoint(ll.latitude, ll.longitude))
                            true
                        }
                        map = m
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (nav.navigating) {
            NavigationBanner(nav, Modifier.align(Alignment.TopCenter).padding(12.dp))
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
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            ) { Icon(Icons.Default.MyLocation, contentDescription = "My location") }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SnackbarHost(snackbar)
            when {
                nav.navigating -> Button(onClick = { nav.stop() }) {
                    Icon(Icons.Default.Close, null); Spacer(Modifier.width(8.dp)); Text("End navigation")
                }
                nav.destination != null -> RouteCard(nav)
                else -> {
                    Text(
                        "Long-press the map to pick a destination",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    ExtendedFloatingActionButton(
                        onClick = { pendingBounds = map?.projection?.visibleRegion?.latLngBounds },
                        icon = { Icon(Icons.Default.Download, contentDescription = null) },
                        text = { Text("Download this area") },
                    )
                }
            }
        }
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
private fun RouteCard(nav: NavigationController) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val r = nav.route
            if (r == null) {
                Text("Destination set", style = MaterialTheme.typography.titleMedium)
                Text("Routes are calculated on your phone, no signal needed.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("${formatDistance(r.distanceMeters)} · ${formatDuration(r.timeMillis)}", style = MaterialTheme.typography.titleLarge)
                Text("Fastest route, calculated offline", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (r == null) {
                    Button(onClick = { nav.planRoute() }, enabled = !nav.busy) {
                        if (nav.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Directions, null)
                        Spacer(Modifier.width(8.dp)); Text("Route here")
                    }
                } else {
                    Button(onClick = { nav.start() }) {
                        Icon(Icons.Default.Navigation, null); Spacer(Modifier.width(8.dp)); Text("Start")
                    }
                    FilledTonalButton(onClick = { nav.planRoute() }, enabled = !nav.busy) { Text("Re-plan") }
                }
                OutlinedButton(onClick = { nav.clear() }) { Text("Clear") }
            }
        }
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
        CircleLayer("sawaari-dest", SRC_DEST).withProperties(
            PropertyFactory.circleRadius(9f),
            PropertyFactory.circleColor("#E53935"),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(3f),
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
