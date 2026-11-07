package app.sawaari

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Icon
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
                var tab by rememberSaveable { mutableIntStateOf(0) }
                LaunchedEffect(Unit) { repo.refresh() }

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
                                icon = { Icon(Icons.Default.DownloadForOffline, null) }, label = { Text("Offline maps") },
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
                            onLocation = { groups.publish(it) },
                            onPositionChanged = { myPosition = it },
                        )
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
            }
        }
    }
}
