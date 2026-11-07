package app.sawaari

import android.Manifest
import android.os.Build
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
import app.sawaari.geo.GeoPoint
import app.sawaari.group.GroupScreen
import app.sawaari.map.MapScreen
import app.sawaari.offline.DownloadsScreen
import app.sawaari.offline.OfflineRegionRepository
import app.sawaari.ui.theme.SawaariTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SawaariTheme {
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
                LaunchedEffect(Unit) { repo.refresh() }

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
                                selected = tab == 3, onClick = { tab = 3 },
                                icon = { Icon(Icons.Default.HealthAndSafety, null) }, label = { Text("SOS") },
                            )
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
                            onLocation = { lastLocation = it; groups.publish(it) },
                            onPositionChanged = { myPosition = it },
                            sosAlerts = sosAlerts,
                            onDismissSos = groups::dismissAlert,
                            groupPlan = group?.plan,
                            groupPlanBy = group?.planBy.orEmpty(),
                            iAmLeader = group != null && group?.leaderUid == groups.myUid,
                            onSharePlan = { groups.sharePlan(it) },
                            onSosPressed = {
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
                            },
                        )
                        if (tab == 3) Surface(Modifier.fillMaxSize()) {
                            SosScreen(sosSettings, Modifier.fillMaxSize())
                        }
                        if (tab == 2) Surface(Modifier.fillMaxSize()) {
                            GroupScreen(groups, myPosition, Modifier.fillMaxSize())
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
