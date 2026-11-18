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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import platform.Foundation.NSUserDefaults

/** Where a tour step's button goes. */
internal enum class TourPlace { MAP, OFFLINE, GROUP, SOS, RIDES, GARAGE, COLOURS }

internal class TourStep(val icon: ImageVector, val title: String, val points: List<String>, val place: TourPlace? = null, val action: String? = null)

/** The Android tour, worded for the iPhone app (online search and routing, SOS by Messages, no places chips yet). */
internal object Tour {
    private const val SEEN = "tour.seen_v1"

    val steps = listOf(
        TourStep(
            Icons.Default.TwoWheeler, "Welcome to Sawaari",
            listOf(
                "Sawaari keeps a riding group together: offline maps and navigation, live locations, group chat and alerts, SOS with crash detection, and your ride history and bike service in one place.",
                "This short tour shows where everything is. Swipe or tap Next. You can open it again any time from Profile, Tour guide.",
            ),
        ),
        TourStep(
            Icons.Default.DownloadForOffline, "1. Save maps before you ride",
            listOf(
                "Tap the profile button at the top right and open Offline maps to download the area you will ride in, or pan the map there and tap \"Download this area\".",
                "Ready-made areas cover the popular riding regions of India.",
                "Do this on Wi-Fi at home. The maps stay on the phone.",
            ),
            TourPlace.OFFLINE, "Open offline maps",
        ),
        TourStep(
            Icons.Default.Route, "2. Plan the route",
            listOf(
                "Type a place in the search bar at the top of the map, or long-press anywhere on the map.",
                "Choose \"Start here\", \"Add as stop\" or \"End here\". Use the arrows on the route card to reorder stops, and turn on Round trip to finish back at the start.",
                "Tap Plan route to see distance and time, then Start for turn-by-turn voice navigation.",
                "On iPhone, searching and planning a route need signal. Once planned, navigation keeps working with no signal.",
            ),
            TourPlace.MAP, "Go to the map",
        ),
        TourStep(
            Icons.Default.Groups, "3. Ride as a group",
            listOf(
                "Tap the Ride button on the map. The leader taps Create group, plans the route and shares the 6-letter code.",
                "Riders enter the code to join, and the route loads on their map, even if they join later.",
                "Everyone shows live on the map, even with the screen off. Android and iPhone riders can be in the same group.",
                "The leader can set a meeting point, share the ride plan, and give riders roles: Sweep (the last rider), Marshal or Medic.",
            ),
            TourPlace.GROUP, "Open the ride group",
        ),
        TourStep(
            Icons.Default.NotificationsActive, "4. Group alerts",
            listOf(
                "Tap \"Taking a break\" in the ride group so everyone knows you stopped on purpose.",
                "Sawaari alerts the group when a rider stops unplanned, leaves the route, falls far behind, reroutes or loses signal.",
                "If the leader is in trouble, another rider can take over as leader.",
            ),
            TourPlace.GROUP, "Open the ride group",
        ),
        TourStep(
            Icons.AutoMirrored.Filled.Chat, "5. Group chat and voice notes",
            listOf(
                "Open Group chat in the ride group. Tap a quick message like \"Fuel stop\" or \"Puncture, need help\" without typing.",
                "Hold the mic button to record a voice note (up to 30 seconds) and let go to send. Nobody has to type while riding.",
            ),
            TourPlace.GROUP, "Open the ride group",
        ),
        TourStep(
            Icons.Default.HealthAndSafety, "6. Set up SOS now",
            listOf(
                "In Profile, open SOS and emergency contacts. Add emergency contacts and your blood group or medical notes.",
                "Press the red SOS button on the map. After a 5-second countdown your group is alerted and Messages opens with your location for your contacts. Tap Send.",
                "Turn on crash detection there too. After a hard impact it beeps for 30 seconds, then starts the SOS unless you tap I'm OK.",
            ),
            TourPlace.SOS, "Set up SOS",
        ),
        TourStep(
            Icons.Default.Share, "7. Share routes",
            listOf(
                "On the route card, tap Share route to send a link that opens in Sawaari.",
                "Got a Google Maps route on WhatsApp? Paste it into the search bar to open it here.",
            ),
            TourPlace.MAP, "Go to the map",
        ),
        TourStep(
            Icons.Default.Timeline, "8. Your rides and your vehicles",
            listOf(
                "Every ride you navigate or ride with a group is saved with its distance, riding time and speeds. See them in Profile, Your rides.",
                "Add your motorcycle, car or bicycle in Profile, Vehicles and service. Rides add to its odometer, and Sawaari reminds you when the oil, chain or other service is due and before your insurance or PUC expires.",
            ),
            TourPlace.GARAGE, "Open the garage",
        ),
        TourStep(
            Icons.Default.Flag, "You're ready to ride",
            listOf(
                "Quick checklist: maps downloaded, SOS contacts added, crash detection on, group joined.",
                "Like different colours? Pick a colour theme in Profile.",
                "Ride safe. Open Profile, Tour guide whenever you want this tour again.",
            ),
            TourPlace.COLOURS, "Choose app colours",
        ),
    )

    fun seen(): Boolean = NSUserDefaults.standardUserDefaults.boolForKey(SEEN)
    fun markSeen() = NSUserDefaults.standardUserDefaults.setBool(true, SEEN)
}

/** The full-screen, swipeable tour. [onGoTo] closes it and opens a place in the app. */
@Composable
internal fun TourScreen(onClose: () -> Unit, onGoTo: (TourPlace) -> Unit) {
    val steps = Tour.steps
    val pager = rememberPagerState { steps.size }
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Tour guide", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Skip") }
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
                val s = steps[page]
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) { Icon(s.icon, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                    Spacer(Modifier.height(24.dp))
                    Text(s.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    s.points.forEach {
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text("•  ", style = MaterialTheme.typography.bodyLarge)
                            Text(it, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    if (s.action != null && s.place != null) {
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = { onGoTo(s.place) }) { Text(s.action) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
                repeat(steps.size) { i ->
                    Box(
                        Modifier.padding(4.dp).size(if (i == pager.currentPage) 10.dp else 8.dp).clip(CircleShape)
                            .background(if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (pager.currentPage > 0) {
                    TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) { Text("Back") }
                }
                Spacer(Modifier.weight(1f))
                val last = pager.currentPage == steps.lastIndex
                Button(onClick = { if (last) onClose() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) {
                    Text(if (last) "Start riding" else "Next")
                }
            }
        }
    }
}
