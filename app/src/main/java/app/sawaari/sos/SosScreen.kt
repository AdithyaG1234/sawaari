package app.sawaari.sos

import android.app.Activity
import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Emergency contacts, medical info and crash detection for the SOS button. */
@Composable
fun SosScreen(settings: SosSettings, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val contacts by settings.contacts.collectAsState()
    val medical by settings.medical.collectAsState()
    val canText = remember { SosSender.canCompose(context) }
    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                    null, null, null,
                )?.use { c -> if (c.moveToFirst()) settings.addContact(EmergencyContact(c.getString(0).orEmpty(), c.getString(1).orEmpty())) }
            }
        }
    }

    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("SOS", style = MaterialTheme.typography.titleLarge)
            Text(
                "Press the red SOS button on the map. After a 5-second countdown, everyone in your ride group is alerted, " +
                    "and your messaging app opens with an SOS text and your location for your contacts: tap Send. " +
                    "It goes by SMS, so no mobile data is needed.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Emergency contacts (${contacts.size}/${SosSettings.MAX_CONTACTS})", style = MaterialTheme.typography.titleMedium)
                    if (contacts.isEmpty()) Text("Add at least one person to text in an emergency.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        items(contacts, key = { it.phone }) { c ->
            ListItem(
                headlineContent = { Text(c.name) },
                supportingContent = { Text(c.phone) },
                trailingContent = { IconButton(onClick = { settings.removeContact(c.phone) }) { Icon(Icons.Default.Delete, "Remove") } },
            )
        }
        if (contacts.size < SosSettings.MAX_CONTACTS) {
            item {
                var name by remember { mutableStateOf("") }
                var phone by remember { mutableStateOf("") }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                    }) { Text("Pick from contacts") }
                    Text("or type a number", style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = phone, onValueChange = { phone = it.take(20) }, label = { Text("Phone, e.g. +91 98450 12345") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { settings.addContact(EmergencyContact(name, phone)); name = ""; phone = "" },
                            enabled = SosSettings.normalizePhone(phone).length >= 8,
                        ) { Text("Add") }
                    }
                }
            }
        }

        item {
            var crashOn by remember { mutableStateOf(CrashWatch.isEnabled(context)) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Crash detection", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        androidx.compose.material3.Switch(checked = crashOn, onCheckedChange = { crashOn = it; CrashWatch.setEnabled(context, it) })
                    }
                    Text(
                        "After a hard jolt while riding, if your bike then stays still, Sawaari beeps and counts down " +
                            "${CrashWatch.COUNTDOWN_S} seconds. Tap I'm OK to stop it, otherwise your SOS goes to your contacts and group. " +
                            "Works with the app open or during a group ride. Keep your phone on the bike or in a jacket pocket.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (!canText) {
            item {
                Text(
                    "This phone has no messaging app, so SOS can only alert your ride group.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Medical info (added to the SOS text)", style = MaterialTheme.typography.titleMedium)
                    var blood by remember(medical) { mutableStateOf(medical.bloodGroup) }
                    var notes by remember(medical) { mutableStateOf(medical.notes) }
                    OutlinedTextField(value = blood, onValueChange = { blood = it.take(6) }, label = { Text("Blood group, e.g. O+") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = notes, onValueChange = { notes = it.take(120) }, label = { Text("Allergies or conditions") }, modifier = Modifier.fillMaxWidth())
                    if (blood != medical.bloodGroup || notes != medical.notes) {
                        Button(onClick = { settings.setMedical(MedicalInfo(blood, notes)) }) { Text("Save") }
                    }
                }
            }
        }
    }
}
