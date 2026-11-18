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
import androidx.compose.material3.Button
import androidx.compose.material.icons.filled.Close
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
import app.sawaari.nav.NavSession
import app.sawaari.nav.RidePlan
import app.sawaari.nav.formatDistance
import app.sawaari.ride.RideLog
import app.sawaari.ride.SavedRide
import app.sawaari.ride.TrackPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import app.sawaari.places.SearchResult
import app.sawaari.sos.SosFix
import app.sawaari.sos.SosStore
import app.sawaari.sos.SosText
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
    val scope = MainScope()
    val groups = GroupClient(backend, UserDefaultsStore(), scope)
    val speaker = Speaker()
    val nav = NavSession(ValhallaRouter(), scope, speaker::speak)
    val rides = RideLog(DocumentsRideFiles())
    val sos = SosStore(UserDefaultsStore())
    return ComposeUIViewController { SawaariIosApp(bridge, groups, nav, rides, sos) }
}

private enum class Screen { MAP, SETTINGS, OFFLINE, RIDE, CHAT, RIDES, SOS }

/**
 * Test hooks for the simulator screenshots in CI, passed as launch arguments
 * (e.g. `-SawaariScreen offline -SawaariDemoDownload YES`). Riders never set these.
 */
private object LaunchArgs {
    private val d = NSUserDefaults.standardUserDefaults
    val screen: Screen = Screen.entries.firstOrNull { it.name.equals(d.stringForKey("SawaariScreen"), ignoreCase = true) } ?: Screen.MAP
    val demoDownload: Boolean = d.boolForKey("SawaariDemoDownload")
    /** Plan a route from the simulator's location to Nandi Hills, with one stop. */
    val demoRoute: Boolean = d.boolForKey("SawaariDemoRoute")
    /** Like [demoRoute], then start navigating. */
    val demoNav: Boolean = d.boolForKey("SawaariDemoNav")
    /** Save a sample ride so the ride history has something to show. */
    val demoRides: Boolean = d.boolForKey("SawaariDemoRides")
    /** Show the SOS countdown as if the SOS button was pressed. */
    val demoSos: Boolean = d.boolForKey("SawaariDemoSos")
    /** Type this into the search box. */
    val demoSearch: String? = d.stringForKey("SawaariDemoSearch")
    /** Open this route link as if it was shared to the app. */
    val demoLink: String? = d.stringForKey("SawaariDemoLink")
}

@Composable
fun SawaariIosApp(bridge: IosMapBridge, groups: GroupClient, nav: NavSession, rides: RideLog, sos: SosStore) {
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
    val navigating by nav.navigating.collectAsState()
    val plan by nav.plan.collectAsState()
    val route by nav.route.collectAsState()
    val navMessage by nav.message.collectAsState()
    var pressedPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var pressedLabel by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // SOS: the button and crash detection share one countdown, then alert the group and open Messages.
    var sosRun by remember { mutableStateOf(if (LaunchArgs.demoSos) SosRun(SOS_COUNTDOWN_S, crash = false) else null) }
    var lastFix by remember { mutableStateOf<LocationFix?>(null) }
    val crashOn by sos.crashDetection.collectAsState()
    val crash = remember {
        CrashMonitor {
            if (sosRun == null) {
                sosRun = SosRun(CRASH_COUNTDOWN_S, crash = true)
                notifyNow("Crash detected", "Sending SOS in $CRASH_COUNTDOWN_S seconds. Open Sawaari and tap I'm OK if you're fine.")
            }
        }
    }
    LaunchedEffect(crashOn) { if (crashOn) crash.start() else crash.stop() }
    fun sendSos(afterCrash: Boolean) {
        val f = lastFix
        val groupAlerted = groups.sendSos(f?.lat, f?.lon)
        val now = nowMillis()
        val sosFix = f?.let {
            SosFix(it.lat, it.lon, it.accuracy.takeIf { a -> a > 0 }?.toInt(), ((now - it.timeMillis) / 60_000).coerceAtLeast(0), clockTime(it.timeMillis))
        }
        val text = (if (afterCrash) "Possible crash detected. " else "") + SosText.message(groups.myName.value, sosFix, sos.medical.value)
        val contacts = sos.contacts.value
        val groupNote = if (groupAlerted) " Your ride group has been alerted." else ""
        when {
            contacts.isEmpty() -> notice = "No emergency contacts saved.$groupNote Add contacts in Profile, SOS."
            SmsComposer.canSend -> SmsComposer.compose(contacts.map { it.phone }, text) { sent ->
                notice = (if (sent) "SOS text sent." else "SOS text was NOT sent.") + groupNote
            }
            else -> {
                notice = "This iPhone can't send texts, so pick an app to send the SOS.$groupNote"
                shareText(text)
            }
        }
    }
    LaunchedEffect(sosRun) {
        val run = sosRun ?: return@LaunchedEffect
        if (run.left <= 0) { sosRun = null; sendSos(run.crash); return@LaunchedEffect }
        if (run.crash) alarmBeep()
        delay(1_000)
        if (sosRun == run) sosRun = run.copy(left = run.left - 1)
    }
    // A new SOS from the group: beep and say it out loud, like Android.
    LaunchedEffect(alerts.firstOrNull()?.id) {
        val a = alerts.firstOrNull() ?: return@LaunchedEffect
        alarmBeep()
        nav.announce("SOS from ${a.name}.")
    }

    // Route links: shared to the app (sawaari://route?...) or pasted into the search box.
    fun openRouteLink(text: String) {
        scope.launch {
            try {
                val lp = IosPlaceSearch.importLink(text, myPosition)
                lp.names.forEach { (p, n) -> nav.setLabel(p, n) }
                nav.loadPlan(lp.plan)
                screen = Screen.MAP
                if (lp.skipped > 0) notice = "${lp.skipped} place${if (lp.skipped == 1) "" else "s"} in the link couldn't be found and ${if (lp.skipped == 1) "was" else "were"} left out."
            } catch (e: Exception) {
                notice = e.message ?: "Couldn't open that link."
            }
        }
    }
    val incoming by IncomingLinks.pending.collectAsState()
    LaunchedEffect(incoming) {
        val link = incoming ?: return@LaunchedEffect
        IncomingLinks.pending.value = null
        // A link that starts "from my location" needs a GPS fix first; wait a few seconds for one.
        var waited = 0
        while (nav.myPosition == null && waited < 20) { delay(250); waited++ }
        openRouteLink(link)
    }
    LaunchedEffect(Unit) { LaunchArgs.demoLink?.let { IncomingLinks.pending.value = it } }

    LaunchedEffect(Unit) {
        bridge.observeLocation { fix ->
            val p = GeoPoint(fix.lat, fix.lon)
            myPosition = p
            lastFix = fix
            crash.onSpeed(if (fix.speedMps >= 0) fix.speedMps * 3.6 else -1.0)
            nav.onLocation(p, fix.accuracy)
            rides.onLocation(fix.lat, fix.lon, fix.timeMillis, if (fix.speedMps >= 0) fix.speedMps * 3.6 else -1.0)
            groups.publish(fix.lat, fix.lon, fix.speedMps, fix.course)
        }
        bridge.observeLongPress { p -> if (!nav.navigating.value) { pressedLabel = null; pressedPoint = p } }
        nav.onReroute = groups::reportReroute
        if (LaunchArgs.demoRides) rides.add(demoRide())
        if (LaunchArgs.demoRoute || LaunchArgs.demoNav) {
            // Wait for the simulator's location, then plan to Nandi Hills via Devanahalli.
            while (nav.myPosition == null) delay(200)
            val stop = GeoPoint(13.2470, 77.7120)
            val end = GeoPoint(13.3702, 77.6835)
            nav.setLabel(stop, "Devanahalli")
            nav.setLabel(end, "Nandi Hills")
            nav.addStop(stop)
            nav.chooseDestination(end)
            nav.planRoute()
            if (LaunchArgs.demoNav) {
                while (nav.route.value == null && nav.message.value == null) delay(200)
                nav.start()
            }
        }
    }
    // While in a ride group or navigating, keep getting locations with the screen off (like Android's ride service).
    LaunchedEffect(group != null, navigating) { bridge.setBackgroundLocation(group != null || navigating) }
    // Record the ride while navigating or in a group, like Android's RideRecorder.
    LaunchedEffect(navigating) {
        rides.setActive("nav", navigating, plan.end?.let { nav.labelFor(it) })
        bridge.setNavigating(navigating)
        keepScreenOn(navigating)
    }
    LaunchedEffect(group?.code) { rides.setActive("group", group != null, group?.name) }
    LaunchedEffect(plan) { bridge.showPlan(plan.start, plan.stops, plan.end) }
    LaunchedEffect(route) {
        val r = route
        bridge.showRoute(r?.points.orEmpty())
        if (r != null && !nav.navigating.value) bridge.fitTo(r.points)
    }
    // A plan the leader shared loads by itself when you have none, like on Android.
    var lastGroupPlan by remember { mutableStateOf<RidePlan?>(null) }
    LaunchedEffect(group?.plan) {
        val gp = group?.plan
        if (gp != null && gp != lastGroupPlan && !nav.navigating.value && nav.plan.value.isEmpty) nav.loadPlan(gp)
        lastGroupPlan = gp
    }
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
                nav = nav,
                planEmpty = plan.isEmpty,
                navigating = navigating,
                onSharePlan = if (group != null && group?.leaderUid == groups.myUid) groups::sharePlan else null,
                sharedPlan = group?.plan,
                myPosition = myPosition,
                onSos = { if (sosRun == null) sosRun = SosRun(SOS_COUNTDOWN_S, crash = false) },
                onSearchPick = { r ->
                    bridge.moveTo(r.point.lat, r.point.lon, 13.0)
                    pressedLabel = r.name
                    pressedPoint = r.point
                },
                onLink = ::openRouteLink,
                onDownloadArea = { name ->
                    bridge.download(name, bridge.visibleBounds(), MIN_ZOOM, MAX_ZOOM)
                    cameFromSettings = false
                    screen = Screen.OFFLINE
                },
            )
            if (screen == Screen.MAP) pressedPoint?.let { p ->
                val g = group
                PlaceActionsDialog(
                    p, nav,
                    onSetMeetingPoint = if (g != null && g.leaderUid == groups.myUid) ({ groups.setMeetingPoint(it, 0L, pressedLabel.orEmpty()) }) else null,
                    onDismiss = { pressedPoint = null },
                    label = pressedLabel,
                )
            }
            sosRun?.let { run ->
                SosCountdownDialog(
                    run.left, run.crash,
                    onSendNow = { sosRun = run.copy(left = 0) },
                    onCancel = { sosRun = null },
                )
            }
            notice?.let { m ->
                AlertDialog(
                    onDismissRequest = { notice = null },
                    text = { Text(m) },
                    confirmButton = { TextButton(onClick = { notice = null }) { Text("OK") } },
                )
            }
            navMessage?.let { m ->
                AlertDialog(
                    onDismissRequest = nav::clearMessage,
                    text = { Text(m) },
                    confirmButton = { TextButton(onClick = nav::clearMessage) { Text("OK") } },
                )
            }
            if (screen == Screen.MAP) SosAlertsBanner(alerts, myPosition, onShow = showOnMap, onDismiss = groups::dismissAlert)
            when (screen) {
                Screen.MAP -> Unit
                Screen.RIDE -> SubScreen("Map", onBack = { screen = Screen.MAP }) {
                    GroupScreen(
                        groups, myPosition,
                        onOpenChat = { screen = Screen.CHAT },
                        onShowOnMap = showOnMap,
                        onLoadPlan = { nav.loadPlan(it); screen = Screen.MAP },
                    )
                }
                Screen.CHAT -> SubScreen("Ride group", onBack = { screen = Screen.RIDE }) { ChatScreen(groups) }
                Screen.SETTINGS -> SubScreen("Map", onBack = { screen = Screen.MAP }) {
                    SettingsScreen(
                        palette = palette,
                        mode = mode,
                        onPalette = ThemeSettings::setPalette,
                        onMode = ThemeSettings::setMode,
                        onOfflineMaps = { cameFromSettings = true; screen = Screen.OFFLINE },
                        onRides = { screen = Screen.RIDES },
                        onSos = { screen = Screen.SOS },
                    )
                }
                Screen.SOS -> SubScreen("Profile", onBack = { screen = Screen.SETTINGS }) { SosScreen(sos) }
                Screen.RIDES -> SubScreen("Profile", onBack = { screen = Screen.SETTINGS }) { RideHistoryScreen(rides) }
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
    nav: NavSession,
    planEmpty: Boolean,
    navigating: Boolean,
    onSharePlan: ((RidePlan) -> Unit)?,
    sharedPlan: RidePlan?,
    myPosition: GeoPoint?,
    onSos: () -> Unit,
    onSearchPick: (SearchResult) -> Unit,
    onLink: (String) -> Unit,
    onDownloadArea: (String) -> Unit,
) {
    var askName by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        UIKitView(factory = { bridge.mapView() }, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)) {
            if (navigating) NavigationBanner(nav, Modifier.align(Alignment.TopCenter))
            if (!navigating) Surface(
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
                modifier = Modifier.align(Alignment.TopEnd).padding(top = if (navigating) 150.dp else 64.dp),
            )
            // Last, so its results show over the buttons.
            if (!navigating) SearchBox(
                near = myPosition,
                onPick = onSearchPick,
                onLink = onLink,
                prefill = LaunchArgs.demoSearch,
                modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().padding(end = 60.dp),
            )
            Column(
                Modifier.align(Alignment.BottomEnd),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.End,
            ) {
                SosButton(onClick = onSos)
                SmallFloatingActionButton(onClick = { if (navigating) bridge.setNavigating(true) else bridge.centerOnMe() }) {
                    Icon(Icons.Default.MyLocation, contentDescription = "My location")
                }
                when {
                    navigating -> Button(onClick = nav::stop) {
                        Icon(Icons.Default.Close, null); Spacer(Modifier.width(8.dp)); Text("End navigation")
                    }
                    !planEmpty -> RouteCard(nav, onSharePlan, sharedPlan)
                    else -> ExtendedFloatingActionButton(
                        onClick = { askName = true },
                        icon = { Icon(Icons.Default.CloudDownload, null) },
                        text = { Text("Download this area") },
                    )
                }
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

/** A sample ride for the CI screenshots: a loop north of Bengaluru, one fix a minute. */
private fun demoRide(): SavedRide {
    val start = 1_763_270_400_000L // Sun 16 Nov 2025, 7:30 AM IST
    val track = (0..90).map { i ->
        val a = i / 90.0 * 2 * kotlin.math.PI
        TrackPoint(13.10 + 0.12 * kotlin.math.sin(a), 77.62 + 0.08 * (1 - kotlin.math.cos(a)), start + i * 60_000L, 45.0 + 15 * kotlin.math.sin(3 * a))
    }
    return SavedRide(start, "Sunday breakfast loop", track)
}
