package app.sawaari.ios

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sawaari.geo.GeoPoint
import app.sawaari.geo.distanceMeters
import app.sawaari.nav.formatDistance
import app.sawaari.places.SearchResult
import app.sawaari.share.RouteLinks
import app.sawaari.sos.EmergencyContact
import app.sawaari.sos.MedicalInfo
import app.sawaari.sos.SosStore
import kotlinx.coroutines.delay

internal val SOS_RED = Color(0xFFD32F2F)

/** Emergency contacts, medical info and crash detection for the SOS button. Like SosScreen on Android. */
@Composable
internal fun SosScreen(sos: SosStore) {
    val contacts by sos.contacts.collectAsState()
    val medical by sos.medical.collectAsState()
    val crashOn by sos.crashDetection.collectAsState()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("SOS", style = MaterialTheme.typography.titleLarge)
            Text(
                "Press the red SOS button on the map. After a 5-second countdown, everyone in your ride group is alerted " +
                    "with your location, and Messages opens with an SOS text to your contacts. On iPhone, tap Send to text them: " +
                    "Apple doesn't let apps send SMS on their own.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Emergency contacts (${contacts.size}/${SosStore.MAX_CONTACTS})", style = MaterialTheme.typography.titleMedium)
                    if (contacts.isEmpty()) Text("Add at least one person to text in an emergency.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        items(contacts, key = { it.phone }) { c ->
            ListItem(
                headlineContent = { Text(c.name) },
                supportingContent = { Text(c.phone) },
                trailingContent = { IconButton(onClick = { sos.removeContact(c.phone) }) { Icon(Icons.Default.Delete, "Remove") } },
            )
        }
        if (contacts.size < SosStore.MAX_CONTACTS) {
            item {
                var name by remember { mutableStateOf("") }
                var phone by remember { mutableStateOf("") }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(40) }, label = { Text("Name") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = phone, onValueChange = { phone = it.take(20) }, label = { Text("Phone, e.g. +91 98450 12345") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { sos.addContact(EmergencyContact(name, phone)); name = ""; phone = "" },
                            enabled = SosStore.normalizePhone(phone).length >= 8,
                        ) { Text("Add") }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Crash detection", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Switch(checked = crashOn, onCheckedChange = sos::setCrashDetection)
                    }
                    Text(
                        "After a hard jolt while riding, if your bike then stays still, Sawaari beeps and counts down " +
                            "$CRASH_COUNTDOWN_S seconds. Tap I'm OK to stop it, otherwise your ride group is alerted and the SOS text opens. " +
                            "Works with the app open or during a group ride or navigation. Keep your phone on the bike or in a jacket pocket.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Medical info (added to the SOS text)", style = MaterialTheme.typography.titleMedium)
                    var blood by remember(medical) { mutableStateOf(medical.bloodGroup) }
                    var notes by remember(medical) { mutableStateOf(medical.notes) }
                    OutlinedTextField(
                        value = blood, onValueChange = { blood = it.take(6) }, label = { Text("Blood group, e.g. O+") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = notes, onValueChange = { notes = it.take(120) }, label = { Text("Allergies or conditions") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (blood != medical.bloodGroup || notes != medical.notes) {
                        Button(onClick = { sos.setMedical(MedicalInfo(blood, notes)) }) { Text("Save") }
                    }
                }
            }
        }
    }
}

const val SOS_COUNTDOWN_S = 5
const val CRASH_COUNTDOWN_S = 30

/** The red countdown before SOS goes out, for the SOS button and after a crash. */
@Composable
internal fun SosCountdownDialog(left: Int, crash: Boolean, onSendNow: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        containerColor = SOS_RED,
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = { Text(if (crash) "Crash detected. Sending SOS in $left" else "Sending SOS in $left", fontWeight = FontWeight.Bold) },
        text = {
            Text(
                "Your ride group gets your location straight away. Then Messages opens with the SOS text for your " +
                    "emergency contacts: tap Send."
            )
        },
        confirmButton = { TextButton(onClick = onSendNow) { Text("SEND NOW", color = Color.White, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(if (crash) "I'M OK" else "CANCEL", color = Color.White) } },
    )
}

/** The red SOS button on the map. */
@Composable
internal fun SosButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = SOS_RED,
        contentColor = Color.White,
        shadowElevation = 6.dp,
        modifier = modifier.size(width = 72.dp, height = 56.dp),
    ) {
        Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SOS", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * The search box on the map: places by name (online), coordinates, or a pasted route link
 * (Sawaari or Google Maps, short links too). Like SearchBar on Android.
 */
@Composable
internal fun SearchBox(
    near: GeoPoint?,
    onPick: (SearchResult) -> Unit,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    prefill: String? = null,
) {
    var query by remember { mutableStateOf(prefill.orEmpty()) }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val link = RouteLinks.findUrl(query) ?: query.trim().takeIf { it.startsWith("sawaari://") }
    // Search again once the location is known, so nearby places come first (and a Nandi Hills in Kenya doesn't).
    LaunchedEffect(query, link, near != null) {
        error = null
        if (link != null || query.trim().length < 2) { results = emptyList(); loading = false; return@LaunchedEffect }
        delay(400) // wait for the rider to stop typing
        loading = true
        try {
            results = IosPlaceSearch.search(query, near)
            if (results.isEmpty()) error = "Nothing found. Try another name."
        } catch (e: Exception) {
            results = emptyList()
            error = e.message ?: "Search needs internet."
        } finally {
            loading = false
        }
    }
    Column(modifier) {
        Surface(shape = MaterialTheme.shapes.extraLarge, shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surface) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                Icon(Icons.Default.Search, null)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(500) },
                    placeholder = { Text("Search places or paste a link", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (link != null) { onLink(link); query = "" } }),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color.Transparent, focusedBorderColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f),
                )
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear") }
                } else {
                    IconButton(onClick = { clipboardText()?.let { query = it.take(500) } }) { Icon(Icons.Default.ContentPaste, "Paste a link") }
                }
            }
        }
        if (link != null || results.isNotEmpty() || error != null) {
            Spacer(Modifier.size(6.dp))
            Card(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                LazyColumn {
                    if (link != null) item {
                        ListItem(
                            leadingContent = { Icon(Icons.Default.Link, null) },
                            headlineContent = { Text("Open this route link") },
                            supportingContent = { Text(link, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.clickable { onLink(link); query = "" },
                        )
                    }
                    error?.let { e -> item { Text(e, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) } }
                    items(results) { r ->
                        ListItem(
                            leadingContent = { Icon(Icons.Default.Place, null) },
                            headlineContent = { Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                val away = near?.let { " · " + formatDistance(distanceMeters(it, r.point)) } ?: ""
                                Text(r.detail + away, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            modifier = Modifier.clickable { onPick(r); query = ""; results = emptyList() },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/** Helper so both the button and the crash countdown send the same way. */
internal data class SosRun(val left: Int, val crash: Boolean)
