package app.sawaari.ios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sawaari.geo.GeoPoint
import app.sawaari.geo.distanceMeters
import app.sawaari.group.GroupClient
import app.sawaari.group.RideGroup
import app.sawaari.group.Rider
import app.sawaari.group.Role
import app.sawaari.nav.RidePlan
import app.sawaari.nav.formatDistance
import app.sawaari.util.nowMillis
import kotlinx.coroutines.delay

/** The ride group: create or join with a code, the riders and their roles, break, meeting point and chat. Like Android's GroupScreen. */
@Composable
internal fun GroupScreen(
    repo: GroupClient,
    myPosition: GeoPoint?,
    onOpenChat: () -> Unit,
    onShowOnMap: (GeoPoint) -> Unit,
    onLoadPlan: (RidePlan) -> Unit,
) {
    val group by repo.group.collectAsState()
    val busy by repo.busy.collectAsState()
    val error by repo.error.collectAsState()
    val myName by repo.myName.collectAsState()
    val chat by repo.chat.collectAsState()
    var now by remember { mutableStateOf(nowMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = nowMillis() } }

    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!repo.configured) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Group rides are almost ready", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "This iPhone build isn't connected to the group server yet. Once the Firebase setup is added, " +
                                "this screen lets you create a group, invite riders with a code and see everyone live on the map.",
                        )
                    }
                }
            }
        }
        item {
            var nameDraft by remember(myName) { mutableStateOf(myName) }
            OutlinedTextField(
                value = nameDraft,
                onValueChange = { nameDraft = it.take(40) },
                label = { Text("Your name (shown to the group)") },
                singleLine = true,
                trailingIcon = {
                    if (nameDraft.trim() != myName && nameDraft.isNotBlank()) TextButton(onClick = { repo.setMyName(nameDraft) }) { Text("Save") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        error?.let { msg ->
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = repo::clearError) { Text("OK") }
                }
            }
        }
        val g = group
        if (g == null) {
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        var groupName by remember { mutableStateOf("") }
                        Text("Start a ride group", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "You'll be the leader. Share the code so others can join, on iPhone or Android.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = groupName, onValueChange = { groupName = it.take(60) },
                            label = { Text("Ride name, e.g. Coorg weekend") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = { repo.setMyName(myName.ifBlank { "Rider" }); repo.create(groupName) }, enabled = !busy) {
                            if (busy) CircularProgressIndicator(Modifier.width(18.dp), strokeWidth = 2.dp) else Text("Create group")
                        }
                    }
                }
            }
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        var code by remember { mutableStateOf("") }
                        Text("Join a ride group", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = code, onValueChange = { code = it.uppercase().filter(Char::isLetterOrDigit).take(GroupClient.CODE_LENGTH) },
                            label = { Text("6-letter code") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = { repo.join(code) }, enabled = !busy && code.length == GroupClient.CODE_LENGTH) { Text("Join") }
                    }
                }
            }
            return@LazyColumn
        }

        val me = repo.myUid
        val meRider = g.riders.firstOrNull { it.uid == me }
        val iAmLeader = g.leaderUid == me
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(g.name, style = MaterialTheme.typography.titleLarge)
                    Text("Invite code", style = MaterialTheme.typography.labelMedium)
                    Text(g.code, fontSize = 36.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 6.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            shareText("Join my Sawaari ride \"${g.name}\": open Sawaari, tap Ride, and enter code ${g.code}")
                        }) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text("Invite") }
                        OutlinedButton(onClick = repo::leave, enabled = !busy) { Text("Leave group") }
                    }
                    Text(
                        "Your location is shared with the group, even with the screen off, until you leave the group.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Group chat", style = MaterialTheme.typography.titleMedium)
                        val last = chat.lastOrNull()
                        Text(
                            last?.let { "${it.name}: " + if (it.voiceMs > 0) "voice note" else it.text } ?: "Quick messages like \"Fuel stop\" in one tap.",
                            style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        )
                    }
                    Button(onClick = onOpenChat) { Text("Open") }
                }
            }
        }
        if (!iAmLeader && g.leaderInTrouble(now)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("The leader may need help", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (meRider?.role == Role.SWEEP) "As sweep, you can lead the group until they're back."
                            else "Their location hasn't updated for a while, or they sent an SOS. Anyone can take over.",
                        )
                        Button(onClick = repo::takeOverLead, enabled = !busy) { Text("Take over as leader") }
                    }
                }
            }
        }
        if (meRider != null) {
            item {
                val onBreak = meRider.breakAt > 0
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (onBreak) "You're on a break" else "Stopping for tea or fuel?", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (onBreak) "The group knows. It ends when you ride off." else "Let the group know so nobody worries.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (onBreak) OutlinedButton(onClick = { repo.setBreak(false) }) { Text("Back on the road") }
                        else Button(onClick = { repo.setBreak(true) }) { Text("Taking a break") }
                    }
                }
            }
        }
        g.meet?.let { m ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Meeting point", style = MaterialTheme.typography.titleMedium)
                        if (m.note.isNotBlank()) Text(m.note)
                        myPosition?.let { Text("${formatDistance(distanceMeters(it, m.point))} away", style = MaterialTheme.typography.bodySmall) }
                        Row {
                            TextButton(onClick = { onShowOnMap(m.point) }) { Text("Show on map") }
                            if (iAmLeader) TextButton(onClick = repo::clearMeetingPoint) { Text("Remove") }
                        }
                    }
                }
            }
        }
        g.plan?.let { plan ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Ride plan from ${g.planBy.ifBlank { "the leader" }}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${plan.stops.size} stop${if (plan.stops.size == 1) "" else "s"} and a destination.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row {
                            TextButton(onClick = { onLoadPlan(plan) }) { Text("Ride this route") }
                            plan.end?.let { end -> TextButton(onClick = { onShowOnMap(end) }) { Text("Show on map") } }
                            if (iAmLeader) TextButton(onClick = repo::clearSharedPlan) { Text("Remove") }
                        }
                    }
                }
            }
        }
        item { Text("Riders (${g.riders.size})", style = MaterialTheme.typography.titleMedium) }
        items(g.riders, key = { it.uid }) { r ->
            RiderRow(r, isMe = r.uid == me, iAmLeader = iAmLeader, myPosition = myPosition, now = now, repo = repo, onShowOnMap = onShowOnMap)
        }
    }
}

@Composable
private fun RiderRow(
    r: Rider,
    isMe: Boolean,
    iAmLeader: Boolean,
    myPosition: GeoPoint?,
    now: Long,
    repo: GroupClient,
    onShowOnMap: (GeoPoint) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val at = r.lat?.let { lat -> r.lon?.let { lon -> GeoPoint(lat, lon) } }
    ListItem(
        headlineContent = { Text(if (isMe) "${r.name} (you)" else r.name) },
        supportingContent = { Text(riderStatus(r, myPosition, now, isMe)) },
        leadingContent = { AssistChip(onClick = {}, label = { Text(Role.label(r.role)) }) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isMe && at != null) TextButton(onClick = { onShowOnMap(at) }) { Text("Map") }
                if (iAmLeader && !isMe) {
                    androidx.compose.foundation.layout.Box {
                        TextButton(onClick = { menu = true }) { Text("Role") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Make leader") }, onClick = { menu = false; repo.makeLeader(r.uid) })
                            if (r.role != Role.SWEEP) DropdownMenuItem(text = { Text("Make sweep (last rider)") }, onClick = { menu = false; repo.makeSweep(r.uid) })
                            if (r.role != Role.MARSHAL) DropdownMenuItem(text = { Text("Make marshal (keeps the group together)") }, onClick = { menu = false; repo.setRole(r.uid, Role.MARSHAL) })
                            if (r.role != Role.MEDIC) DropdownMenuItem(text = { Text("Make medic (first aid)") }, onClick = { menu = false; repo.setRole(r.uid, Role.MEDIC) })
                            if (r.role != Role.RIDER) DropdownMenuItem(text = { Text("Make rider") }, onClick = { menu = false; repo.setRole(r.uid, Role.RIDER) })
                        }
                    }
                }
            }
        },
    )
}

/** Same wording as Android's rider list. */
internal fun riderStatus(r: Rider, me: GeoPoint?, now: Long, isMe: Boolean): String {
    val flags = buildList {
        if (r.sosAt > 0 && now - r.sosAt < RideGroup.SOS_TAKEOVER_MS) add("SOS sent")
        if (r.breakAt > 0) add("on a break")
    }.joinToString(" · ").let { if (it.isEmpty()) "" else "$it · " }
    val lat = r.lat
    val lon = r.lon
    if (lat == null || lon == null || r.updatedAt == 0L) return flags + "No location yet"
    val ageMin = ((now - r.updatedAt) / 60_000).coerceAtLeast(0)
    val age = if (ageMin < 1L) "just now" else "$ageMin min ago"
    val parts = mutableListOf<String>()
    if (!isMe && me != null) parts += "${formatDistance(distanceMeters(me, GeoPoint(lat, lon)))} away"
    if (r.speedKmh >= 3) parts += "${r.speedKmh.toInt()} km/h"
    parts += age
    return flags + parts.joinToString(" · ")
}

/** One-tap messages that are quick to send at a stop, without typing. Same as Android. */
private val QUICK = listOf("On my way", "Fuel stop", "Wait for me", "All good", "Slow down", "Reached", "Puncture, need help")

/** The group's chat: text and one-tap quick messages. Voice notes from Android riders show as notes. */
@Composable
internal fun ChatScreen(repo: GroupClient) {
    val chat by repo.chat.collectAsState()
    val me = repo.myUid
    val list = rememberLazyListState()
    LaunchedEffect(chat.size) { if (chat.isNotEmpty()) list.animateScrollToItem(chat.lastIndex) }
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = list,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (chat.isEmpty()) item {
                Text("No messages yet. Tap a quick message below, or type one.", style = MaterialTheme.typography.bodyMedium)
            }
            items(chat, key = { it.id }) { m ->
                val mine = m.uid == me
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            if (!mine) Text(m.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text(if (m.voiceMs > 0) "🎤 Voice note (${(m.voiceMs / 1000).coerceAtLeast(1)} s)" else m.text)
                        }
                    }
                }
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(QUICK) { q -> AssistChip(onClick = { repo.sendText(q) }, label = { Text(q) }) }
        }
        var text by remember { mutableStateOf("") }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(500) },
                placeholder = { Text("Message") }, modifier = Modifier.weight(1f), maxLines = 3,
            )
            IconButton(onClick = { repo.sendText(text); text = "" }, enabled = text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send")
            }
        }
    }
}
