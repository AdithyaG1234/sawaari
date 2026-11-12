package app.sawaari.group

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.sawaari.geo.GeoPoint
import app.sawaari.sos.EmergencyContact

/**
 * A floating "Ride" button on the map that opens the whole ride in one window: plan the trip,
 * create or join a group, the route, chat, break and the rider list. Tapping outside the window
 * (or Back, or the arrow) minimises it back to the button. The Group tab stays as it is.
 */
@Composable
fun RideHub(
    repo: GroupRepository,
    myPosition: GeoPoint?,
    sosContacts: List<EmergencyContact>,
    onPlanOnMap: () -> Unit,
    onImportLink: (String) -> Unit,
    onShowRoute: () -> Unit,
    onRideTo: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val group by repo.group.collectAsState()
    val chat by repo.chat.collectAsState()
    val alerts by repo.alerts.collectAsState()
    val context = LocalContext.current
    val seen = remember(chat.size, open) { ChatSeen.last(context) }
    val unread = if (group == null) 0 else chat.count { it.ts > seen && it.uid != repo.myUid }
    val minimise = { open = false }

    Box(modifier.fillMaxSize()) {
        if (!open) {
            BadgedBox(
                badge = { if (unread + alerts.size > 0) Badge { Text("${unread + alerts.size}") } },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 204.dp, end = 16.dp),
            ) {
                ExtendedFloatingActionButton(
                    onClick = { open = true },
                    icon = { Icon(Icons.Default.Groups, null) },
                    text = { Text(group?.let { "${it.riders.size} riding" } ?: "Ride") },
                    expanded = group != null,
                )
            }
        }
        AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
            // Tapping the dimmed map outside the window minimises it.
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = minimise),
            )
        }
        AnimatedVisibility(
            open,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BackHandler(onBack = minimise)
            Surface(
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                tonalElevation = 6.dp, shadowElevation = 12.dp,
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f).imePadding()
                    // Swallow taps inside the window so they don't reach the scrim.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(group?.name ?: "Your ride", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = minimise) { Icon(Icons.Default.KeyboardArrowDown, "Minimise") }
                    }
                    GroupScreen(
                        repo, myPosition, Modifier.weight(1f).fillMaxWidth(),
                        sosContacts = sosContacts,
                        onRideTo = { onRideTo(it); minimise() },
                        onPlanOnMap = { onPlanOnMap(); minimise() },
                        onImportLink = { onImportLink(it); minimise() },
                        onShowRoute = { onShowRoute(); minimise() },
                        showPlanTrip = true,
                    )
                }
            }
        }
    }
}
