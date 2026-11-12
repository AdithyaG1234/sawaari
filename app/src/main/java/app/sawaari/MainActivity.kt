package app.sawaari

import android.Manifest
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import app.sawaari.ride.RideService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay
import app.sawaari.sos.SosScreen
import app.sawaari.sos.SosSender
import app.sawaari.sos.SosPermissions
import app.sawaari.sos.SosSettings
import app.sawaari.ride.RideRecorder
import app.sawaari.ride.RideHistoryScreen
import app.sawaari.garage.GarageReminders
import app.sawaari.group.RideHub
import app.sawaari.garage.GarageScreen
import app.sawaari.sos.CrashWatch
import androidx.compose.material3.Surface
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import app.sawaari.geo.GeoPoint
import app.sawaari.group.GroupScreen
import app.sawaari.map.MapScreen
import app.sawaari.offline.DownloadsScreen
import app.sawaari.offline.OfflineRegionRepository
import app.sawaari.ui.theme.SawaariTheme
import app.sawaari.ui.SettingsScreen
import app.sawaari.ui.theme.ThemeSettings
import app.sawaari.ui.theme.ThemePickerDialog
import app.sawaari.tour.TourGuide
import app.sawaari.tour.TourGuideDialog

class MainActivity : ComponentActivity() {
    /** A route link that opened the app (Sawaari or Google Maps link, or text shared to Sawaari). */
    private val incomingLink = mutableStateOf<String?>(null)
    /** Set when a bike service reminder was tapped. */
    private val openGarage = mutableStateOf(false)

    private fun linkFrom(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.dataString
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
        else -> null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkFrom(intent)?.let { incomingLink.value = it }
        if (intent.getBooleanExtra(EXTRA_OPEN_GARAGE, false)) openGarage.value = true
    }

    companion object {
        const val EXTRA_OPEN_GARAGE = "open_garage"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            incomingLink.value = linkFrom(intent)
            if (intent?.getBooleanExtra(EXTRA_OPEN_GARAGE, false) == true) openGarage.value = true
        }
        enableEdgeToEdge()
        ThemeSettings.load(applicationContext)
        setContent {
            val palette by ThemeSettings.palette.collectAsState()
            val darkMode by ThemeSettings.mode.collectAsState()
            SawaariTheme(palette, darkMode) {
                val repo = remember { OfflineRegionRepository(applicationContext) }
                val downloads by repo.downloads.collectAsState()
                val routing = remember { (application as SawaariApp).routing }
                val routingStates by routing.states.collectAsState()
                val groups = remember { (application as SawaariApp).groups }
                val group by groups.group.collectAsState()
                var myPosition by remember { mutableStateOf<GeoPoint?>(null) }
                var lastLocation by remember { mutableStateOf<android.location.Location?>(null) }
                val sosSettings = remember { SosSettings(applicationContext) }
                val sosContacts by sosSettings.contacts.collectAsState()
                val sosAlerts by groups.alerts.collectAsState()
                val myName by groups.myName.collectAsState()
                var sosCountdown by remember { mutableStateOf<Int?>(null) }
                var sosResult by remember { mutableStateOf<String?>(null) }
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var rideTo by remember { mutableStateOf<GeoPoint?>(null) }
                val app = application as SawaariApp
                var showThemes by rememberSaveable { mutableStateOf(false) }
                var showTour by rememberSaveable { mutableStateOf(TourGuide.shouldShow(applicationContext)) }
                // A route link opening the app on first launch goes straight to the route, not the tour.
                LaunchedEffect(Unit) { if (incomingLink.value != null) { showTour = false; TourGuide.markSeen(applicationContext) } }
                LaunchedEffect(Unit) { repo.refresh() }
                // A group ride counts as a ride for the history, from joining until leaving.
                LaunchedEffect(group?.code) { RideRecorder.setActive("group", group != null, group?.name) }
                LaunchedEffect(Unit) { CrashWatch.start(applicationContext) }
                val crashLeft by CrashWatch.secondsLeft.collectAsState()
                val crashResult by CrashWatch.result.collectAsState()
                // Once, after the tour: ask new riders to add SOS contacts, so SOS works on the first ride.
                val sosPrefs = remember { applicationContext.getSharedPreferences("sos", MODE_PRIVATE) }
                var askSosSetup by remember { mutableStateOf(!sosPrefs.getBoolean("setup_prompted", false)) }
                LaunchedEffect(incomingLink.value) { if (incomingLink.value != null) tab = 0 }
                LaunchedEffect(openGarage.value) { if (openGarage.value) { tab = 6; openGarage.value = false } }
                LaunchedEffect(Unit) { GarageReminders.check(applicationContext) }

                // While in a ride group, keep sharing location with the screen off (ongoing notification).
                val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                var sosNeedsSettings by remember { mutableStateOf(false) }
                // With emergency contacts saved, get SMS permission up front so SOS never waits on a tap.
                LaunchedEffect(sosContacts.isNotEmpty()) {
                    if (sosContacts.isNotEmpty() && !SosSender.canSendDirectly(applicationContext)) smsPermission.launch(Manifest.permission.SEND_SMS)
                }
                LaunchedEffect(group != null, myPosition != null) {
                    if (group != null) {
                        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        RideService.start(applicationContext)
                    } else RideService.stop(applicationContext)
                }

                val pressSos: () -> Unit = {
                    if (sosContacts.isEmpty() && group == null) {
                        sosResult = "Add an emergency contact or join a ride group first, so SOS has someone to alert."
                        tab = 3
                    } else {
                        // Ask for SMS now, during the countdown, so the text can go out without a tap.
                        if (sosContacts.isNotEmpty() && !SosSender.canSendDirectly(applicationContext)) {
                            smsPermission.launch(Manifest.permission.SEND_SMS)
                        }
                        sosCountdown = 5
                    }
                }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = tab == 0, onClick = { tab = 0 },
                                icon = { Icon(Icons.Default.Map, null) }, label = { Text("Map") },
                            )
                            NavigationBarItem(
                                selected = tab == 2, onClick = { tab = 2 },
                                icon = { Icon(Icons.Default.Groups, null) }, label = { Text("Group") },
                            )
                            NavigationBarItem(
                                selected = tab == 1, onClick = { tab = 1 },
                                icon = { Icon(Icons.Default.DownloadForOffline, null) }, label = { Text("Offline") },
                            )
                            NavigationBarItem(
                                selected = tab == 4, onClick = { tab = 4 },
                                icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") },
                            )
                            // The emergency button lives in the bar, next to Settings, on every screen.
                            Box(Modifier.weight(1f).height(80.dp), contentAlignment = Alignment.Center) {
                                Surface(
                                    onClick = pressSos,
                                    shape = RoundedCornerShape(16.dp),
                                    color = Color(0xFFD32F2F),
                                    contentColor = Color.White,
                                    shadowElevation = 3.dp,
                                    modifier = Modifier.size(width = 64.dp, height = 48.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("SOS", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                            }
                        }
                    },
                ) { padding ->
                    // The map stays composed under the other tab so navigation keeps running.
                    Box(Modifier.padding(padding).fillMaxSize()) {
                        MapScreen(
                            routing = routing,
                            onDownloadArea = { name, bounds -> repo.download(name, bounds); tab = 1 },
                            groupRiders = group?.riders.orEmpty(),
                            myUid = groups.myUid,
                            onLocation = { lastLocation = it; groups.publish(it); CrashWatch.onLocation(applicationContext, it); RideRecorder.onLocation(it) },
                            onPositionChanged = { myPosition = it },
                            sosAlerts = sosAlerts,
                            onDismissSos = groups::dismissAlert,
                            groupPlan = group?.plan,
                            groupPlanBy = group?.planBy.orEmpty(),
                            iAmLeader = group != null && group?.leaderUid == groups.myUid,
                            onSharePlan = { groups.sharePlan(it) },
                            meet = group?.meet,
                            onSetMeetingPoint = if (group != null && group?.leaderUid == groups.myUid)
                                ({ p: GeoPoint, time: Long, note: String -> groups.setMeetingPoint(p, time, note) }) else null,
                            onReroute = groups::reportReroute,
                            onGroupRoute = { app.groupRoute.value = it },
                            rideTo = rideTo,
                            onRideToHandled = { rideTo = null },
                            places = app.places,
                            importLink = incomingLink.value,
                            onImportHandled = { incomingLink.value = null },
                            onTourGuide = { showTour = true },
                            inGroup = group != null,
                            onCreateGroup = { name ->
                                groups.setMyName(myName.ifBlank { "Rider" })
                                groups.create(name.ifBlank { "Weekend ride" })
                                tab = 2
                            },
                        )
                        // Floating "Ride" button on the map: plan, group, route, chat, break and riders in one window.
                        if (tab == 0) RideHub(
                            groups, myPosition, sosContacts,
                            onPlanOnMap = {},
                            onImportLink = { incomingLink.value = it },
                            onShowRoute = {},
                            onRideTo = { rideTo = it },
                        )
                        if (tab == 4) Surface(Modifier.fillMaxSize()) {
                            SettingsScreen(
                                palette = palette, mode = darkMode,
                                onPalette = { ThemeSettings.setPalette(applicationContext, it) },
                                onMode = { ThemeSettings.setMode(applicationContext, it) },
                                onTourGuide = { showTour = true },
                                onSosSetup = { tab = 3 },
                                onRideHistory = { tab = 5 },
                                onGarage = { tab = 6 },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        androidx.activity.compose.BackHandler(enabled = tab == 6) { tab = 4 }
                        if (tab == 6) Surface(Modifier.fillMaxSize()) {
                            GarageScreen(Modifier.fillMaxSize())
                        }
                        androidx.activity.compose.BackHandler(enabled = tab == 5) { tab = 4 }
                        if (tab == 5) Surface(Modifier.fillMaxSize()) {
                            RideHistoryScreen(Modifier.fillMaxSize())
                        }
                        // SOS setup opens from Settings, so Back returns there.
                        androidx.activity.compose.BackHandler(enabled = tab == 3) { tab = 4 }
                        if (tab == 3) Surface(Modifier.fillMaxSize()) {
                            SosScreen(sosSettings, Modifier.fillMaxSize())
                        }
                        if (tab == 2) Surface(Modifier.fillMaxSize()) {
                            GroupScreen(
                                groups, myPosition, Modifier.fillMaxSize(),
                                sosContacts = sosContacts,
                                onRideTo = { rideTo = it; tab = 0 },
                                onPlanOnMap = { tab = 0 },
                                onImportLink = { incomingLink.value = it; tab = 0 },
                                onShowRoute = { tab = 0 },
                            )
                        }
                        if (tab == 1) Surface(Modifier.fillMaxSize()) {
                            DownloadsScreen(
                                downloads = downloads,
                                onDownloadPreset = repo::download,
                                onDelete = repo::delete,
                                onResume = { repo.resume(it) },
                                routingStates = routingStates,
                                onDownloadRoutingPack = routing::download,
                                onDeleteRoutingPack = routing::delete,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }

                if (showTour) TourGuideDialog(
                    onClose = { showTour = false; TourGuide.markSeen(applicationContext) },
                    onGoTo = { showTour = false; TourGuide.markSeen(applicationContext); tab = it.index },
                    onTheme = { showThemes = true },
                )
                if (showThemes) ThemePickerDialog(
                    palette = palette, mode = darkMode,
                    onPalette = { ThemeSettings.setPalette(applicationContext, it) },
                    onMode = { ThemeSettings.setMode(applicationContext, it) },
                    onDismiss = { showThemes = false },
                )

                sosCountdown?.let { left ->
                    LaunchedEffect(left) {
                        if (left > 0) { delay(1000); sosCountdown = left - 1 }
                        else {
                            sosCountdown = null
                            val text = SosSender.message(myName, lastLocation, sosSettings.medical.value)
                            val sent = SosSender.send(applicationContext, sosContacts, text)
                            val groupAlerted = groups.sendSos(lastLocation)
                            sosNeedsSettings = sent < 0
                            sosResult = buildString {
                                append(
                                    when {
                                        sosContacts.isEmpty() -> "No emergency contacts saved."
                                        sent > 0 -> "SOS text sent to $sent contact${if (sent > 1) "s" else ""}."
                                        sent < 0 -> "SOS text NOT sent: Sawaari isn't allowed to send SMS. Tap Open settings, then " +
                                            "⋮ → Allow restricted settings → Permissions → SMS → Allow."
                                        else -> "SOS text could not be sent. Check that your SIM has signal and SMS balance."
                                    }
                                )
                                if (groupAlerted) append(" Your ride group has been alerted.")
                            }
                        }
                    }
                    AlertDialog(
                        onDismissRequest = {},
                        containerColor = Color(0xFFD32F2F),
                        titleContentColor = Color.White,
                        textContentColor = Color.White,
                        title = { Text("Sending SOS in $left", fontWeight = FontWeight.Bold) },
                        text = { Text("Your location goes to your emergency contacts by SMS and to your ride group.") },
                        confirmButton = {
                            TextButton(onClick = { sosCountdown = 0 }) { Text("SEND NOW", color = Color.White, fontWeight = FontWeight.Bold) }
                        },
                        dismissButton = {
                            TextButton(onClick = { sosCountdown = null }) { Text("CANCEL", color = Color.White) }
                        },
                    )
                }
                if (askSosSetup && !showTour && sosContacts.isEmpty() && incomingLink.value == null) AlertDialog(
                    onDismissRequest = { askSosSetup = false; sosPrefs.edit().putBoolean("setup_prompted", true).apply() },
                    title = { Text("Set up SOS before you ride") },
                    text = {
                        Text(
                            "Add one or two people to text in an emergency. SOS sends them your location by SMS, " +
                                "so it works even with no mobile data. You can also turn on crash detection there."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            askSosSetup = false; sosPrefs.edit().putBoolean("setup_prompted", true).apply(); tab = 3
                        }) { Text("Set up now") }
                    },
                    dismissButton = {
                        TextButton(onClick = { askSosSetup = false; sosPrefs.edit().putBoolean("setup_prompted", true).apply() }) { Text("Later") }
                    },
                )
                crashLeft?.let { left ->
                    AlertDialog(
                        onDismissRequest = {},
                        containerColor = Color(0xFFD32F2F),
                        titleContentColor = Color.White,
                        textContentColor = Color.White,
                        title = { Text("Crash detected. SOS in $left", fontWeight = FontWeight.Bold) },
                        text = { Text("Your location will go to your emergency contacts and your ride group. Tap I'M OK if you're fine.") },
                        confirmButton = {
                            TextButton(onClick = { CrashWatch.cancel(applicationContext) }) {
                                Text("I'M OK", color = Color.White, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { CrashWatch.sendNow(applicationContext) }) { Text("SEND NOW", color = Color.White) }
                        },
                    )
                }
                crashResult?.let { msg ->
                    AlertDialog(
                        onDismissRequest = { CrashWatch.result.value = null },
                        title = { Text("Crash SOS") },
                        text = { Text(msg) },
                        confirmButton = { TextButton(onClick = { CrashWatch.result.value = null }) { Text("OK") } },
                    )
                }
                sosResult?.let { msg ->
                    AlertDialog(
                        onDismissRequest = { sosResult = null },
                        title = { Text("SOS") },
                        text = { Text(msg) },
                        confirmButton = { TextButton(onClick = { sosResult = null }) { Text("OK") } },
                        dismissButton = if (sosNeedsSettings) {
                            { TextButton(onClick = { sosResult = null; SosPermissions.openAppSettings(applicationContext) }) { Text("Open settings") } }
                        } else null,
                    )
                }
            }
        }
    }
}
