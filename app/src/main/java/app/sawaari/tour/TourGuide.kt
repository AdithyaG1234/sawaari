package app.sawaari.tour

import android.content.Context
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/** Bottom tabs a tour step can jump to. Numbers match the tabs in MainActivity. */
enum class TourTab(val index: Int) { MAP(0), OFFLINE(1), GROUP(2), SOS(3) }

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
                "Sawaari keeps a riding group together: offline maps and navigation, live locations, group alerts and SOS.",
                "This short tour shows where everything is. Swipe or tap Next. You can open it again any time from the ? button on the map.",
            ),
        ),
        TourStep(
            Icons.Default.DownloadForOffline, "1. Save maps before you ride",
            listOf(
                "Open the Offline tab and download the area you will ride in, or pan the map there and tap \"Download this area\".",
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
                "In the Group tab, the leader taps Create group and shares the 6-letter code. Others enter it to join.",
                "Everyone shows live on the map, even with the screen off. Tap a rider to call them or see their emergency contact.",
                "The leader can set a meeting point with date and time, share the ride plan, and pick a sweep (the last rider).",
            ),
            TourTab.GROUP, "Open Group",
        ),
        TourStep(
            Icons.Default.NotificationsActive, "5. Group alerts",
            listOf(
                "Tap \"Taking a break\" in the Group tab so everyone knows you stopped on purpose.",
                "Sawaari alerts the group when a rider stops unplanned, leaves the route, falls far behind, hits slow traffic, reroutes or loses signal.",
                "If the leader is in trouble, another rider can take over as leader.",
            ),
            TourTab.GROUP, "Open Group",
        ),
        TourStep(
            Icons.Default.HealthAndSafety, "6. Set up SOS now",
            listOf(
                "In the SOS tab, add emergency contacts and your blood group or medical notes, and allow SMS.",
                "Press the red SOS button on the map. After a 5-second countdown, your location goes by SMS to your contacts and as an alert to the group, with no extra taps.",
            ),
            TourTab.SOS, "Open SOS",
        ),
        TourStep(
            Icons.Default.Share, "7. Share routes",
            listOf(
                "On the route card, tap \"Share route (WhatsApp etc.)\" to send a map picture and a link that opens in Sawaari.",
                "Got a Google Maps route on WhatsApp? Share it to Sawaari or paste it into the search bar to open it here.",
            ),
            TourTab.MAP, "Go to the map",
        ),
        TourStep(
            Icons.Default.Flag, "You're ready to ride",
            listOf(
                "Quick checklist: maps downloaded, SOS contacts added, group joined.",
                "Ride safe. Tap ? on the map whenever you want this tour again.",
            ),
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
fun TourGuideDialog(onClose: () -> Unit, onGoTo: (TourTab) -> Unit) {
    val steps = TourGuide.steps
    val pager = rememberPagerState { steps.size }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
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
                        if (s.tab != null && s.action != null) {
                            Spacer(Modifier.height(16.dp))
                            OutlinedButton(onClick = { onGoTo(s.tab) }) { Text(s.action) }
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
