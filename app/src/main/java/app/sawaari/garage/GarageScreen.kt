package app.sawaari.garage

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.sawaari.ride.RideRecorder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The rider's bikes: odometer (rides add to it), service jobs with reminders, and papers' expiry dates. */
@Composable
fun GarageScreen(modifier: Modifier = Modifier) {
    val state by Garage.state.collectAsState()
    val rides by RideRecorder.rides.collectAsState()
    var shownId by remember { mutableLongStateOf(0L) }
    var addBike by remember { mutableStateOf(false) }
    var editOdo by remember { mutableStateOf(false) }
    var editItem by remember { mutableStateOf<ServiceItem?>(null) }
    var editPaper by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val bike = state.bikes.firstOrNull { it.id == shownId } ?: state.current
    val now = System.currentTimeMillis()
    val odo = bike?.let { Garage.odometer(it, rides) } ?: 0.0

    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Your garage", style = MaterialTheme.typography.titleLarge)
            Text(
                "Service reminders and papers for your bikes, kept only on this phone. Rides you record add to the odometer of the bike you're riding.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (bike == null) {
            item {
                Button(onClick = { addBike = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null); Text("  Add your bike")
                }
            }
            return@LazyColumn
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.bikes, key = { it.id }) { b ->
                    FilterChip(selected = b.id == bike.id, onClick = { shownId = b.id }, label = { Text(b.name) })
                }
                item { FilterChip(selected = false, onClick = { addBike = true }, label = { Text("+ Add bike") }) }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(bike.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("%,d km".format(Locale.ENGLISH, odo.toLong()), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { editOdo = true }) { Text("Update odometer") }
                    }
                    if (bike.id == state.current?.id) {
                        Text("You're riding this bike. New rides add to its odometer.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        OutlinedButton(onClick = { Garage.ride(bike.id) }) { Text("Ride this bike") }
                    }
                }
            }
        }
        item { Text("Service", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        items(bike.items, key = { "item-" + it.id }) { item ->
            val st = GarageLogic.status(item, odo, now)
            StatusRow(
                title = item.name,
                subtitle = st.text + every(item),
                level = st.level,
                onClick = { editItem = item },
                action = {
                    TextButton(onClick = { Garage.markDone(bike.id, item.id) }) { Icon(Icons.Default.Check, null); Text(" Done") }
                },
            )
        }
        item {
            TextButton(onClick = { editItem = ServiceItem("c" + System.currentTimeMillis(), "", 1000, null, odo, now) }) {
                Icon(Icons.Default.Add, null); Text(" Add a service job")
            }
        }
        item { Text("Papers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        items(Papers.ALL, key = { "paper-$it" }) { paper ->
            val exp = bike.papers[paper]
            val st = exp?.let { GarageLogic.paperStatus(it, now) }
            StatusRow(
                title = paper,
                subtitle = if (exp == null || st == null) "Tap to add the expiry date" else "${st.text} · ${date(exp)}",
                level = st?.level ?: Level.OK,
                onClick = { editPaper = paper },
                action = { IconButton(onClick = { editPaper = paper }) { Icon(Icons.Default.Edit, "Set expiry date") } },
            )
        }
        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TextButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, null); Text(" Remove ${bike.name}") }
        }
    }

    if (addBike) BikeDialog(
        onDismiss = { addBike = false },
        onSave = { name, km ->
            Garage.addBike(name, km); addBike = false; shownId = 0L
            if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
    )
    if (editOdo && bike != null) NumberDialog(
        title = "Odometer reading", label = "km on the meter now", initial = odo.toLong().toString(),
        onDismiss = { editOdo = false }, onSave = { Garage.setOdometer(bike.id, it); editOdo = false },
    )
    editItem?.let { item ->
        if (bike != null) ItemDialog(
            item, isNew = bike.items.none { it.id == item.id },
            onDismiss = { editItem = null },
            onSave = { Garage.saveItem(bike.id, it); editItem = null },
            onDelete = { Garage.deleteItem(bike.id, item.id); editItem = null },
        )
    }
    editPaper?.let { paper ->
        if (bike != null) PaperDialog(
            paper, bike.papers[paper],
            onDismiss = { editPaper = null },
            onSave = { Garage.setPaper(bike.id, paper, it); editPaper = null },
        )
    }
    if (confirmDelete && bike != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Remove ${bike.name}?") },
        text = { Text("Its service history and paper dates will be deleted from this phone. Your rides stay.") },
        confirmButton = { TextButton(onClick = { Garage.deleteBike(bike.id); confirmDelete = false; shownId = 0L }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
private fun StatusRow(title: String, subtitle: String, level: Level, onClick: () -> Unit, action: @Composable () -> Unit) {
    val color = when (level) {
        Level.DUE -> MaterialTheme.colorScheme.error
        Level.SOON -> Color(0xFFE65100)
        Level.OK -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = color)
            }
            action()
        }
    }
}

private fun every(i: ServiceItem): String {
    val parts = listOfNotNull(i.everyKm?.let { "%,d km".format(Locale.ENGLISH, it) }, i.everyDays?.let { months(it) })
    return if (parts.isEmpty()) "" else " · every " + parts.joinToString(" or ")
}

private fun months(days: Int) = if (days % 30 == 0 && days >= 30) "${days / 30} month${if (days == 30) "" else "s"}" else "$days days"

private fun date(ms: Long) = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(Date(ms))

private fun String.toKm(): Double? = filter { it.isDigit() || it == '.' }.toDoubleOrNull()

@Composable
private fun BikeDialog(onDismiss: () -> Unit, onSave: (String, Double) -> Unit) {
    var name by remember { mutableStateOf("") }
    var km by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a bike") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name, e.g. Himalayan 450") }, singleLine = true)
                OutlinedTextField(
                    km, { km = it.take(7) }, label = { Text("Odometer now (km)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text("Service reminders start from today. You can change them or mark jobs you did recently.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, km.toKm() ?: 0.0) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NumberDialog(title: String, label: String, initial: String, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                v, { v = it.take(7) }, label = { Text(label) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = { TextButton(onClick = { v.toKm()?.let(onSave) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ItemDialog(item: ServiceItem, isNew: Boolean, onDismiss: () -> Unit, onSave: (ServiceItem) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(item.name) }
    var km by remember { mutableStateOf(item.everyKm?.toString().orEmpty()) }
    var months by remember { mutableStateOf(item.everyDays?.let { (it / 30).coerceAtLeast(1).toString() }.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add a service job" else "Edit ${item.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Job, e.g. Coolant top-up") }, singleLine = true)
                OutlinedTextField(
                    km, { km = it.filter(Char::isDigit).take(6) }, label = { Text("Every … km (blank for none)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    months, { months = it.filter(Char::isDigit).take(3) }, label = { Text("Every … months (blank for none)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (!isNew) TextButton(onClick = onDelete) { Icon(Icons.Default.Delete, null); Text(" Remove this job") }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && (km.isNotBlank() || months.isNotBlank()),
                onClick = {
                    onSave(item.copy(
                        name = name.trim(),
                        everyKm = km.toIntOrNull()?.takeIf { it > 0 },
                        everyDays = months.toIntOrNull()?.takeIf { it > 0 }?.let { it * 30 },
                    ))
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaperDialog(paper: String, current: Long?, onDismiss: () -> Unit, onSave: (Long?) -> Unit) {
    val picker = rememberDatePickerState(initialSelectedDateMillis = current ?: System.currentTimeMillis())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(picker.selectedDateMillis) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onSave(null) }) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    ) {
        DatePicker(picker, title = { Text("$paper expires on", Modifier.padding(start = 24.dp, top = 16.dp)) })
    }
}
