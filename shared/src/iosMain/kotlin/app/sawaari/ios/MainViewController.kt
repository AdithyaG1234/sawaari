package app.sawaari.ios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import app.sawaari.routing.routingPacks
import app.sawaari.share.RouteLinks
import platform.UIKit.UIViewController

/** Entry point the iPhone app (iosApp/) shows full screen. */
@Suppress("unused", "FunctionName")
fun MainViewController(): UIViewController = ComposeUIViewController { SawaariIosApp() }

// Same "Saffron & Asphalt" colours as the Android app. Step 2 shares the theme code itself.
private val lightColors = lightColorScheme(
    primary = Color(0xFFE8711A), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBC8), onPrimaryContainer = Color(0xFF341100),
    secondary = Color(0xFF263238), onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFD8DC), onSecondaryContainer = Color(0xFF101C21),
    tertiary = Color(0xFF00897B),
)
private val darkColors = darkColorScheme(
    primary = Color(0xFFFF9A4D), onPrimary = Color(0xFF4A1C00),
    primaryContainer = Color(0xFF8A3D00), onPrimaryContainer = Color(0xFFFFDBC8),
    secondary = Color(0xFFB0BEC5), onSecondary = Color(0xFF1B262B),
    secondaryContainer = Color(0xFF37474F), onSecondaryContainer = Color(0xFFCFD8DC),
    tertiary = Color(0xFF4DB6AC),
)

/**
 * Step 1 of the iPhone app: proves the shared ride logic runs on iPhone. It reads route links
 * with the same code as Android and lists the offline ride areas. The map arrives in step 2.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SawaariIosApp(initialLink: String = DEMO_LINK) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColors else lightColors) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Sawaari", fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            },
        ) { padding ->
            var link by remember { mutableStateOf(initialLink) }
            val route = remember(link) { RouteLinks.findUrl(link)?.let { RouteLinks.parse(it) } ?: RouteLinks.parse(link.trim()) }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(16.dp).fillMaxWidth()) {
                            Text("iPhone preview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "Ride logic shared with the Android app. The map, groups and SOS come in the next steps.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                item {
                    Text("Open a route link", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = link,
                        onValueChange = { link = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Google Maps or Sawaari link") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (route == null) {
                        Text("Not a route link yet.", color = MaterialTheme.colorScheme.error)
                    } else {
                        route.points.forEachIndexed { i, p ->
                            val label = when (i) { 0 -> "Start"; route.points.lastIndex -> "Destination"; else -> "Stop $i" }
                            val where = p.name.ifBlank { p.point?.let { RouteLinks.fmt(it) } ?: "My location" }
                            Text("$label: $where", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                item { Text("Offline ride areas", style = MaterialTheme.typography.titleMedium) }
                items(routingPacks) { pack ->
                    Card(Modifier.fillMaxWidth()) {
                        Text(pack.name, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    }
                }
            }
        }
    }
}

private const val DEMO_LINK = "https://www.google.com/maps/dir/12.9716,77.5946/Nandi+Hills/13.3702,77.6835"
