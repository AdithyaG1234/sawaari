package app.sawaari.group

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.sawaari.geo.GeoPoint
import app.sawaari.geo.distanceMeters
import app.sawaari.nav.formatDistance
import app.sawaari.sos.EmergencyContact
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun dial(context: Context, phone: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun formatMeetTime(time: Long): String =
    SimpleDateFormat("EEE d MMM, h:mm a", Locale.ENGLISH).format(Date(time))

/** Tapping a rider (on the map or in the list): call them or their emergency contact, or ride to them. */
@Composable
fun RiderDetailsDialog(rider: Rider, myPosition: GeoPoint?, onNavigate: (() -> Unit)?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(rider.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val where = if (myPosition != null && rider.lat != null && rider.lon != null)
                    "${formatDistance(distanceMeters(myPosition, GeoPoint(rider.lat, rider.lon)))} away" else "Location not shared yet"
                Text("${Role.label(rider.role)} · $where", style = MaterialTheme.typography.bodySmall)
                if (rider.phone.isNotBlank()) {
                    FilledTonalButton(onClick = { dial(context, rider.phone) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Call, null); Spacer(Modifier.width(8.dp)); Text("Call ${rider.name}")
                    }
                }
                if (rider.icePhone.isNotBlank()) {
                    FilledTonalButton(onClick = { dial(context, rider.icePhone) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Call, null); Spacer(Modifier.width(8.dp))
                        Text("Emergency contact: ${rider.iceName.ifBlank { rider.icePhone }}")
                    }
                }
                if (rider.phone.isBlank() && rider.icePhone.isBlank()) {
                    Text("${rider.name} hasn't shared a phone number or emergency contact with the group.", style = MaterialTheme.typography.bodySmall)
                }
                if (onNavigate != null && rider.lat != null) {
                    OutlinedButton(onClick = { onNavigate(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Directions, null); Spacer(Modifier.width(8.dp)); Text("Ride to ${rider.name}")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Leader sets the meeting point: optional date and time, and a note like "Petrol pump near the toll". */
@Composable
fun MeetingPointDialog(point: GeoPoint, onSave: (time: Long, note: String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var note by remember { mutableStateOf("") }
    var time by remember { mutableLongStateOf(0L) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Meeting point") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("%.5f, %.5f".format(Locale.US, point.lat, point.lon), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = note, onValueChange = { note = it.take(120) },
                    label = { Text("Note, e.g. Shell petrol pump") }, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { pickDateTime(context, time) { time = it } }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (time == 0L) "Set date and time" else formatMeetTime(time))
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(time, note); onDismiss() }) { Text("Share with group") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun pickDateTime(context: Context, current: Long, onPicked: (Long) -> Unit) {
    val cal = Calendar.getInstance().apply {
        if (current > 0) timeInMillis = current
        else { add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 6); set(Calendar.MINUTE, 0) }
    }
    DatePickerDialog(context, { _, y, m, d ->
        cal.set(y, m, d)
        TimePickerDialog(context, { _, h, min ->
            cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min); cal.set(Calendar.SECOND, 0)
            onPicked(cal.timeInMillis)
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
}

/** Shows the meeting point in the Group tab, with ride-there and share (e.g. to WhatsApp). */
@Composable
fun MeetingCard(meet: MeetingPoint, groupName: String, myPosition: GeoPoint?, iAmLeader: Boolean, onNavigate: () -> Unit, onClear: () -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Meeting point", style = MaterialTheme.typography.titleMedium)
            if (meet.time > 0) Text(formatMeetTime(meet.time), style = MaterialTheme.typography.titleLarge)
            if (meet.note.isNotBlank()) Text(meet.note)
            myPosition?.let { Text("${formatDistance(distanceMeters(it, meet.point))} from you", style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onNavigate) { Icon(Icons.Default.Directions, null); Spacer(Modifier.width(6.dp)); Text("Ride there") }
                OutlinedButton(onClick = {
                    val link = "https://maps.google.com/?q=%.6f,%.6f".format(Locale.US, meet.point.lat, meet.point.lon)
                    val text = buildString {
                        append("Meeting point for \"$groupName\"")
                        if (meet.time > 0) append(", ${formatMeetTime(meet.time)}")
                        if (meet.note.isNotBlank()) append(": ${meet.note}")
                        append("\n$link")
                    }
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share meeting point"))
                }) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("Share") }
            }
            if (iAmLeader) TextButton(onClick = onClear) { Text("Remove meeting point") }
            else Text("Set by ${meet.by.ifBlank { "the leader" }}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Opt-in: share my number and one emergency contact so riders can call them from the group. */
@Composable
fun ContactSharingCard(me: Rider, sosContacts: List<EmergencyContact>, onSave: (phone: String, iceName: String, icePhone: String) -> Unit) {
    var phone by remember(me.phone) { mutableStateOf(me.phone) }
    var ice by remember(me.icePhone) { mutableStateOf(sosContacts.firstOrNull { it.phone == me.icePhone } ?: if (me.icePhone.isNotBlank()) EmergencyContact(me.iceName, me.icePhone) else null) }
    val sharing = me.phone.isNotBlank() || me.icePhone.isNotBlank()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your contact details for the group", style = MaterialTheme.typography.titleMedium)
            Text("Optional. Riders can tap you to call you or your emergency contact. Only this group sees them.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = phone, onValueChange = { phone = it.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(20) },
                label = { Text("Your phone number") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(),
            )
            if (sosContacts.isEmpty()) {
                Text("Add emergency contacts in the SOS tab to share one here.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Emergency contact to share", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sosContacts.take(3).forEach { c ->
                        FilterChip(selected = ice?.phone == c.phone, onClick = { ice = if (ice?.phone == c.phone) null else c }, label = { Text(c.name) })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(phone, ice?.name.orEmpty(), ice?.phone.orEmpty()) }) { Text(if (sharing) "Update" else "Share with group") }
                if (sharing) TextButton(onClick = { phone = ""; ice = null; onSave("", "", "") }) { Text("Stop sharing") }
            }
        }
    }
}
