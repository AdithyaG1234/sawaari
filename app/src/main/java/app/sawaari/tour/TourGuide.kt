package app.sawaari.tour

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.automirrored.filled.Chat
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

/** Bottom tabs a tour step can jump to. Numbers match the tabs in MainActivity. */
enum class TourTab(val index: Int) { MAP(0), OFFLINE(1), GROUP(2), SOS(3), HISTORY(5), GARAGE(6) }

data class TourStep(
    val icon: ImageVector,
    val title: String,
    val points: List<String>,
    val tab: TourTab? = null,
    val action: String? = null,
)

object TourGuide {
    val steps = listOf(
        TourStep(
            Icons.Default.TwoWheeler, "Welcome to Sawaari",
            listOf(
                "Sawaari keeps a riding group together: offline maps and navigation, live locations, group chat and alerts, SOS with crash detection, and your ride history and bike service in one place.",
                "This short tour shows where everything is. Swipe or tap Next. You can open it again any time from the ? button on the map.",
            ),
        ),
        TourStep(
            Icons.Default.DownloadForOffline, "1. Save maps before you ride",
            listOf(
                "Open Settings, Offline maps and download the area you will ride in, or pan the map there and tap \"Save this map for offline use\".",
                "Ready-made areas cover Karnataka, Kerala, Tamil Nadu, Goa, Maharashtra, Hyderabad, Delhi and Uttarakhand, Himachal and Ladakh, Kashmir, Rajasthan, Sikkim and Meghalaya.",
                "Also download \"Offline navigation\" for that area so routes work with no signal.",
                "Do this on Wi-Fi at home. The maps stay on the phone.",
            ),
            TourTab.OFFLINE, "Open Offline",
        ),
        TourStep(
            Icons.Default.Route, "2. Plan the route",
            listOf(
                "Type a place in the search bar at the top of the map, or long-press anywhere on the map.",
                "Choose \"Start here\", \"Add as stop\" (breakfast, fuel, sights, up to 8) or \"End here\".",
                "Use the arrows on the route card to reorder stops, and turn on Round trip to finish back at the start.",
                "Tap Plan route to see distance and time, then Start for turn-by-turn voice navigation.",
            ),
            TourTab.MAP, "Go to the map",
        ),
        TourStep(
            Icons.Default.Restaurant, "3. Places along the way",
            listOf(
                "Once a route is planned, the chips above the route card list restaurants, fuel, mechanics, tyre shops and hospitals along it.",
                "Tap a place to see its rating, call it, check Google reviews, or rate it for other riders.",
            ),
            TourTab.MAP, "Go to the map",
        ),
        TourStep(
            Icons.Default.Groups, "4. Ride as a group",
            listOf(
                "Tap the round Ride button on the map (or Settings, Ride group). The leader taps Create group, adds the route (paste a Google Maps link or plan it on the map) and shares the 6-letter code. Already planned a route? Tap \"Create a group for this route\" on the route card.",
                "Riders enter the code to join, and the route loads on their map, even if they join later.",
                "Everyone shows live on the map, even with the screen off. Tap a rider to call them or see their emergency contact.",
                "The leader can set a meeting point with date and time, share the ride plan, and give riders roles: Sweep (the last rider), Marshal or Medic.",
            ),
            TourTab.GROUP, "Open Group",
        ),
        TourStep(
            Icons.Default.NotificationsActive, "5. Group alerts",
            listOf(
                "Tap \"Taking a break\" in the Ride window so everyone knows you stopped on purpose.",
                "Sawaari alerts the group when a rider stops unplanned, leaves the route, falls far behind, hits slow traffic, reroutes or loses signal.",
                "If the leader is in trouble, another rider can take over as leader.",
            ),
            TourTab.GROUP, "Open Group",
        ),
        TourStep(
            Icons.AutoMirrored.Filled.Chat, "6. Group chat and voice notes",
            listOf(
                "Open Group chat in the Ride window. Tap a quick message like \"Fuel stop\" or \"Puncture, need help\" without typing.",
                "Hold the mic button to record a voice note (up to 30 seconds) and let go to send. Nobody has to type while riding.",
            ),
            TourTab.GROUP, "Open Group",
        ),
        TourStep(
            Icons.Default.HealthAndSafety, "7. Set up SOS now",
            listOf(
                "In Settings, tap Set up SOS contacts. Add emergency contacts and your blood group or medical notes, and allow SMS.",
                "Press the red SOS button in the bottom bar, next to Settings. After a 5-second countdown, your location goes by SMS to your contacts and as an alert to the group, with no extra taps.",
                "Turn on crash detection there too. After a hard impact it beeps for 30 seconds, then sends the SOS by itself unless you tap I'm OK.",
            ),
            TourTab.SOS, "Set up SOS",
        ),
        TourStep(
            Icons.Default.Share, "8. Share routes",
            listOf(
                "On the route card, tap \"Share route (WhatsApp etc.)\" to send a map picture and a link that opens in Sawaari.",
                "Got a Google Maps route on WhatsApp? Share it to Sawaari or paste it into the search bar to open it here.",
            ),
            TourTab.MAP, "Go to the map",
        ),
        TourStep(
            Icons.Default.Timeline, "9. Your rides and your vehicles",
            listOf(
                "Every ride you navigate or ride with a group is saved with its distance, riding time and speeds. See them in Settings, Ride history and stats.",
                "Add your motorcycle, car or bicycle in Settings, Vehicles and service. Rides add to its odometer, and Sawaari reminds you when the oil, chain or other service is due and before your insurance or PUC expires.",
            ),
            TourTab.GARAGE, "Open garage",
        ),
        TourStep(
            Icons.Default.Flag, "You're ready to ride",
            listOf(
                "Quick checklist: maps downloaded, SOS contacts added, crash detection on, group joined.",
                "Like different colours? Pick a colour theme below, or later in the Settings tab.",
                "Ride safe. Tap ? on the map whenever you want this tour again.",
            ),
            action = "Choose app colours",
        ),
    )

    private const val PREFS = "tour"
    private const val SEEN = "seen_v1"

    fun shouldShow(context: Context): Boolean =
        !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(SEEN, false)

    fun markSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(SEEN, true).apply()
    }
}

/**
 * Full-screen, swipeable tour of the app. [onGoTo] switches to a tab and closes the tour;
 * [onClose] closes it. The caller marks the tour as seen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TourGuideDialog(onClose: () -> Unit, onGoTo: (TourTab) -> Unit, onTheme: () -> Unit) {
    val steps = TourGuide.steps
    val pager = rememberPagerState { steps.size }
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)
    // Drawn over the app (not in a separate dialog window) so the system bar padding is exact
    // and the buttons never sit under the phone's navigation bar.
    run {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().navigationBarsPadding().padding(16.dp)) {
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
                        if (s.action != null) {
                            Spacer(Modifier.height(16.dp))
                            OutlinedButton(onClick = { if (s.tab != null) onGoTo(s.tab) else onTheme() }) { Text(s.action) }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    repeat(steps.size) { i ->
                        Box(
                            Modifier.padding(4.dp).size(if (i == pager.currentPage) 10.dp else 8.dp).clip(CircleShape)
                                .background(
                                    if (i == pager.currentPage) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                ),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (pager.currentPage > 0) {
                        TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) { Text("Back") }
                    }
                    Spacer(Modifier.weight(1f))
                    val last = pager.currentPage == steps.lastIndex
                    Button(onClick = {
                        if (last) onClose() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    }) { Text(if (last) "Start riding" else "Next") }
                }
            }
        }
    }
}
