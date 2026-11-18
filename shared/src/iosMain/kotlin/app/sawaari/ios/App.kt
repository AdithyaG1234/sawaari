package app.sawaari.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.material.icons.filled.Groups
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import app.sawaari.geo.GeoPoint
import app.sawaari.geo.distanceMeters
import app.sawaari.group.GroupBackend
import app.sawaari.group.GroupClient
import app.sawaari.group.RideGroup
import app.sawaari.group.Role
import app.sawaari.group.SosAlert
import app.sawaari.nav.formatDistance
import app.sawaari.util.nowMillis
import kotlinx.coroutines.MainScope
import app.sawaari.offline.DownloadedRegion
import app.sawaari.offline.regionPresets
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIViewController

/** Entry point the iPhone app (iosApp/) shows full screen, with the Swift MapLibre map passed in. */
@Suppress("unused", "FunctionName")
fun MainViewController(bridge: IosMapBridge, backend: GroupBackend): UIViewController {
    val groups = GroupClient(backend, UserDefaultsStore(), MainScope())
    return ComposeUIViewController { SawaariIosApp(bridge, groups) }
}

private enum class Screen { MAP, SETTINGS, OFFLINE, RIDE, CHAT }

/**
 * Test hooks for the simulator screenshots in CI, passed as launch arguments
 * (e.g. `-SawaariScreen offline -SawaariDemoDownload YES`). Riders never set these.
 */
private object LaunchArgs {
    private val d = NSUserDefaults.standardUserDefaults
    val screen: Screen = Screen.entries.firstOrNull { it.name.equals(d.stringForKey("SawaariScreen"), ignoreCase = true) } ?: Screen.MAP
    val demoDownload: Boolean = d.boolForKey("SawaariDemoDownload")
}

@Composable
fun SawaariIosApp(bridge: IosMapBridge, groups: GroupClient) {
    val palette by ThemeSettings.palette.collectAsState()
    val mode by ThemeSettings.mode.collectAsState()
    val regionsFlow = remember { MutableStateFlow<List<DownloadedRegion>>(emptyList()) }
    val regions by regionsFlow.collectAsState()
    LaunchedEffect(Unit) {
        bridge.observeRegions { regionsFlow.value = it }
        if (LaunchArgs.demoDownload) {
            val demo = regionPresets.first { it.name == "Bengaluru to Nandi Hills" }
            bridge.download(demo.name, demo.bounds, MIN_ZOOM, MAX_ZOOM)
        }
    }
    var screen by remember { mutableStateOf(LaunchArgs.screen) }
    val group by groups.group.collectAsState()
    val alerts by groups.alerts.collectAsState()
    var myPosition by remember { mutableStateOf<GeoPoint?>(null) }
    LaunchedEffect(Unit) {
        bridge.observeLocation { fix ->
            myPosition = GeoPoint(fix.lat, fix.lon)
            groups.publish(fix.lat, fix.lon, fix.speedMps, fix.course)
        }
    }
    // While in a ride group, keep sharing location with the screen off (like Android's ride service).
    LaunchedEffect(group != null) { bridge.setBackgroundLocation(group != null) }
    LaunchedEffect(group) {
        val g = group
        val now = nowMillis()
        bridge.showRiders(g?.riders.orEmpty().filter { it.uid != groups.myUid }.mapNotNull { r ->
            val lat = r.lat ?: return@mapNotNull null
            val lon = r.lon ?: return@mapNotNull null
            val sos = r.sosAt > 0 && now - r.sosAt < RideGroup.SOS_TAKEOVER_MS
            RiderPin(
                uid = r.uid, name = r.name,
                label = Role.label(r.role) + if (r.breakAt > 0) " · on a break" else "",
                kind = when {
                    sos -> "sos"
                    r.breakAt > 0 -> "break"
                    r.uid == g?.leaderUid -> "leader"
                    r.role == Role.SWEEP -> "sweep"
                    else -> "rider"
                },
                lat = lat, lon = lon,
                faded = r.updatedAt > 0 && now - r.updatedAt > 5 * 60_000L,
            )
        })
        bridge.showMeetingPoint(g?.meet?.point, g?.meet?.note?.ifBlank { null } ?: "Meeting point")
    }
    val showOnMap: (GeoPoint) -> Unit = { p -> bridge.moveTo(p.lat, p.lon, 14.0); screen = Screen.MAP }
    var cameFromSettings by remember { mutableStateOf(LaunchArgs.screen == Screen.OFFLINE) }

    SawaariTheme(palette, mode) {
        Box(Modifier.fillMaxSize()) {
            // The map stays in place under the other screens, like on Android.
            MapHome(
                bridge = bridge,
                onProfile = { screen = Screen.SETTINGS },
                onRide = { screen = Screen.RIDE },
                rideLabel = group?.let { "${it.riders.size} riding" } ?: "Ride",
                onDownloadArea = { name ->
                    bridge.download(name, bridge.visibleBounds(), MIN_ZOOM, MAX_ZOOM)
                    cameFromSettings = false
                    screen = Screen.OFFLINE
                },
            )
            if (screen == Screen.MAP) SosAlertsBanner(alerts, myPosition, onShow = showOnMap, onDismiss = groups::dismissAlert)
            when (screen) {
                Screen.MAP -> Unit
                Screen.RIDE -> SubScreen("Map", onBack = { screen = Screen.MAP }) {
                    GroupScreen(groups, myPosition, onOpenChat = { screen = Screen.CHAT }, onShowOnMap = showOnMap)
                }
                Screen.CHAT -> SubScreen("Ride group", onBack = { screen = Screen.RIDE }) { ChatScreen(groups) }
                Screen.SETTINGS -> SubScreen("Map", onBack = { screen = Screen.MAP }) {
                    SettingsScreen(
                        palette = palette,
                        mode = mode,
                        onPalette = ThemeSettings::setPalette,
                        onMode = ThemeSettings::setMode,
                        onOfflineMaps = { cameFromSettings = true; screen = Screen.OFFLINE },
                    )
                }
                Screen.OFFLINE -> SubScreen(
                    if (cameFromSettings) "Profile" else "Map",
                    onBack = { screen = if (cameFromSettings) Screen.SETTINGS else Screen.MAP },
                ) {
                    OfflineMapsScreen(
                        downloads = regions,
                        onDownloadPreset = { bridge.download(it.name, it.bounds, MIN_ZOOM, MAX_ZOOM) },
                        onDelete = bridge::deleteRegion,
                        onResume = bridge::resumeRegion,
                    )
                }
            }
        }
    }
}

/** Zoom 6-14 is enough for riding (roads and town names), the same as on Android. */
private const val MIN_ZOOM = 6.0
private const val MAX_ZOOM = 14.0

@Composable
private fun MapHome(
    bridge: IosMapBridge,
    onProfile: () -> Unit,
    onRide: () -> Unit,
    rideLabel: String,
    onDownloadArea: (String) -> Unit,
) {
    var askName by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        UIKitView(factory = { bridge.mapView() }, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shadowElevation = 4.dp,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                IconButton(onClick = onProfile) {
                    Icon(Icons.Default.AccountCircle, contentDescription = "Profile and settings", modifier = Modifier.size(32.dp))
                }
            }
            // The "Ride" button opens the ride group, like on Android.
            ExtendedFloatingActionButton(
                onClick = onRide,
                icon = { Icon(Icons.Default.Groups, null) },
                text = { Text(rideLabel) },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 64.dp),
            )
            Column(
                Modifier.align(Alignment.BottomEnd),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.End,
            ) {
                SmallFloatingActionButton(onClick = bridge::centerOnMe) {
                    Icon(Icons.Default.MyLocation, contentDescription = "My location")
                }
                ExtendedFloatingActionButton(
                    onClick = { askName = true },
                    icon = { Icon(Icons.Default.CloudDownload, null) },
                    text = { Text("Download this area") },
                )
            }
        }
    }
    if (askName) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askName = false },
            title = { Text("Download this area") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Everything on screen now is saved for riding with no signal. Zoom in first for a smaller download.")
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(40) },
                        label = { Text("Name") },
                        placeholder = { Text("My ride area") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { askName = false; onDownloadArea(name.trim().ifBlank { "My ride area" }) }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { askName = false }) { Text("Cancel") } },
        )
    }
}

/** Other riders' SOS alerts, on top of the map until dismissed. */
@Composable
private fun SosAlertsBanner(alerts: List<SosAlert>, myPosition: GeoPoint?, onShow: (GeoPoint) -> Unit, onDismiss: (String) -> Unit) {
    if (alerts.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 12.dp, end = 12.dp, top = 130.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        alerts.forEach { a ->
            Surface(color = Color(0xFFD32F2F), contentColor = Color.White, shape = MaterialTheme.shapes.large, shadowElevation = 6.dp) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("SOS: ${a.name} needs help", fontWeight = FontWeight.Bold)
                    val at = a.lat?.let { lat -> a.lon?.let { lon -> GeoPoint(lat, lon) } }
                    val ageMin = ((nowMillis() - a.ts) / 60_000).coerceAtLeast(0)
                    val where = if (at != null && myPosition != null) "${formatDistance(distanceMeters(myPosition, at))} away · " else ""
                    Text("$where${if (ageMin < 1) "just now" else "$ageMin min ago"}")
                    Row {
                        if (at != null) TextButton(onClick = { onShow(at) }) { Text("Show on map", color = Color.White) }
                        TextButton(onClick = { onDismiss(a.id) }) { Text("Dismiss", color = Color.White) }
                    }
                }
            }
        }
    }
}

/** A full-screen page over the map with a back button, like SubScreen on Android. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubScreen(backLabel: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                        Spacer(Modifier.width(6.dp))
                        Text(backLabel)
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) { content() }
    }
}
