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
import app.sawaari.offline.DownloadedRegion
import app.sawaari.offline.regionPresets
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIViewController

/** Entry point the iPhone app (iosApp/) shows full screen, with the Swift MapLibre map passed in. */
@Suppress("unused", "FunctionName")
fun MainViewController(bridge: IosMapBridge): UIViewController = ComposeUIViewController { SawaariIosApp(bridge) }

private enum class Screen { MAP, SETTINGS, OFFLINE }

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
fun SawaariIosApp(bridge: IosMapBridge) {
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
    var cameFromSettings by remember { mutableStateOf(LaunchArgs.screen == Screen.OFFLINE) }

    SawaariTheme(palette, mode) {
        Box(Modifier.fillMaxSize()) {
            // The map stays in place under the other screens, like on Android.
            MapHome(
                bridge = bridge,
                onProfile = { screen = Screen.SETTINGS },
                onDownloadArea = { name ->
                    bridge.download(name, bridge.visibleBounds(), MIN_ZOOM, MAX_ZOOM)
                    cameFromSettings = false
                    screen = Screen.OFFLINE
                },
            )
            when (screen) {
                Screen.MAP -> Unit
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
private fun MapHome(bridge: IosMapBridge, onProfile: () -> Unit, onDownloadArea: (String) -> Unit) {
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
