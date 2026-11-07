package app.sawaari.group

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sawaari.geo.GeoPoint
import app.sawaari.geo.distanceMeters
import app.sawaari.nav.formatDistance
import kotlinx.coroutines.delay

/** Create or join a ride group, share the code, and see who's riding. */
@Composable
fun GroupScreen(repo: GroupRepository, myPosition: GeoPoint?, modifier: Modifier = Modifier) {
    val group by repo.group.collectAsState()
    val busy by repo.busy.collectAsState()
    val error by repo.error.collectAsState()
    val myName by repo.myName.collectAsState()
    val context = LocalContext.current

    // Tick so "updated x min ago" stays fresh.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }

    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!repo.configured) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Group rides are almost ready", style = MaterialTheme.typography.titleMedium)
                        Text("This build isn't connected to the group server yet. Once the Firebase setup is added, this screen lets you create a group, invite riders with a code and see everyone live on the map.")
                    }
                }
            }
            return@LazyColumn
        }

        item {
            var nameDraft by remember(myName) { mutableStateOf(myName) }
            OutlinedTextField(
                value = nameDraft,
                onValueChange = { nameDraft = it.take(40) },
                label = { Text("Your name (shown to the group)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                trailingIcon = {
                    if (nameDraft.trim() != myName) TextButton(onClick = { repo.setMyName(nameDraft) }) { Text("Save") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        error?.let { msg ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        TextButton(onClick = { repo.clearError() }) { Text("OK") }
                    }
                }
            }
        }

        val g = group
        if (g == null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Start a ride group", style = MaterialTheme.typography.titleMedium)
                        Text("You'll be the leader. Share the code so others can join.", style = MaterialTheme.typography.bodySmall)
                        var groupName by remember { mutableStateOf("") }
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
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Join a ride group", style = MaterialTheme.typography.titleMedium)
                        var code by remember { mutableStateOf("") }
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.uppercase().filter(Char::isLetterOrDigit).take(GroupRepository.CODE_LENGTH) },
                            label = { Text("6-letter code") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = { repo.join(code) }, enabled = !busy && code.length == GroupRepository.CODE_LENGTH) { Text("Join") }
                    }
                }
            }
            return@LazyColumn
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(g.name, style = MaterialTheme.typography.titleLarge)
                    Text("Invite code", style = MaterialTheme.typography.labelMedium)
                    Text(g.code, fontSize = 36.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 6.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val text = "Join my ride \"${g.name}\" on Sawaari. Open the Group tab and enter code ${g.code}.\n" +
                                "Get the app: https://github.com/AdithyaG1234/sawaari/releases/download/latest-debug/sawaari-debug.apk"
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Invite riders"))
                        }) {
                            Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text("Invite")
                        }
                        OutlinedButton(onClick = { repo.leave() }, enabled = !busy) { Text("Leave group") }
                    }
                    Text("Your location is shared with the group while Sawaari is open.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item { Text("Riders (${g.riders.size})", style = MaterialTheme.typography.titleMedium) }

        val iAmLeader = g.leaderUid == repo.myUid
        items(g.riders, key = { it.uid }) { r ->
            val isMe = r.uid == repo.myUid
            ListItem(
                headlineContent = { Text(if (isMe) "${r.name} (you)" else r.name) },
                supportingContent = { Text(riderStatus(r, myPosition, now, isMe)) },
                leadingContent = { AssistChip(onClick = {}, label = { Text(r.role.replaceFirstChar { it.uppercase() }) }) },
                trailingContent = {
                    if (iAmLeader && !isMe && r.role != Role.SWEEP) TextButton(onClick = { repo.makeSweep(r.uid) }) { Text("Make sweep") }
                },
            )
        }
    }
}

private fun riderStatus(r: Rider, me: GeoPoint?, now: Long, isMe: Boolean): String {
    if (r.lat == null || r.lon == null || r.updatedAt == 0L) return "No location yet"
    val ageMin = ((now - r.updatedAt) / 60_000).coerceAtLeast(0)
    val age = if (ageMin < 1) "just now" else "$ageMin min ago"
    val parts = mutableListOf<String>()
    if (!isMe && me != null) parts += "${formatDistance(distanceMeters(me, GeoPoint(r.lat, r.lon)))} away"
    if (r.speedKmh >= 3) parts += "${r.speedKmh.toInt()} km/h"
    parts += "updated $age"
    return parts.joinToString(" · ")
}
