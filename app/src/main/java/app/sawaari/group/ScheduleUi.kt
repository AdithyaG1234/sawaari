package app.sawaari.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sawaari.ride.RidePhase
import app.sawaari.ride.Schedule
import app.sawaari.ride.ScheduleAlarms

/**
 * The ride's timetable and its Start / End button. The leader sets a time (and optional name) for
 * the start, each pit stop and the destination; every rider gets a notification when each time
 * comes. Any rider can start or end the ride, and the whole group is told.
 */
@Composable
fun ScheduleCard(
    g: RideGroup, iAmLeader: Boolean, now: Long, busy: Boolean,
    onSave: (RideSchedule?) -> Unit, onStart: () -> Unit, onEnd: () -> Unit,
) {
    val schedule = g.schedule ?: RideSchedule()
    val rows = Schedule.rows(g.plan, g.schedule)
    val entries = Schedule.entries(g)
    val next = Schedule.next(entries, now)
    val phase = Schedule.phase(g.ride)
    var editing by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }

    Card(
        Modifier.fillMaxWidth(),
        colors = if (phase == RidePhase.IN_PROGRESS) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ride schedule", style = MaterialTheme.typography.titleMedium)
            val ride = g.ride
            Text(
                when (phase) {
                    RidePhase.PLANNED -> "Planned. Not started yet."
                    RidePhase.IN_PROGRESS -> "In progress since ${ScheduleAlarms.time(ride!!.at)}, started by ${ride.by.ifBlank { "a rider" }}."
                    RidePhase.ENDED -> "Ended at ${ScheduleAlarms.time(ride!!.at)} by ${ride.by.ifBlank { "a rider" }}."
                },
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            )

            rows.forEach { (key, t) ->
                val isNext = next?.key == key && phase != RidePhase.ENDED
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = iAmLeader) { editing = key }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        when (key) { Schedule.START -> Icons.Default.PlayArrow; Schedule.END -> Icons.Default.Flag; else -> Icons.Default.Schedule },
                        null, tint = if (isNext) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            Schedule.label(key) + (t?.name?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""),
                            fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal,
                        )
                        Text(
                            when {
                                t == null -> if (iAmLeader) "Tap to set a time" else "No time set"
                                isNext -> "${formatMeetTime(t.at)} · next"
                                else -> formatMeetTime(t.at)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isNext) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (iAmLeader) TextButton(onClick = { editing = key }) { Text(if (t == null) "Set" else "Edit") }
                }
            }

            Text(
                if (entries.isEmpty()) {
                    if (iAmLeader) "Set times for the start, pit stops and destination. Every rider gets a notification when each time comes."
                    else "The leader hasn't set times yet. You'll get a notification at each scheduled time."
                } else "Every rider gets a notification when each time comes.",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (phase) {
                    RidePhase.PLANNED -> Button(onClick = { confirm = RideStatus.STARTED }, enabled = !busy) {
                        Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Start ride")
                    }
                    RidePhase.IN_PROGRESS -> OutlinedButton(onClick = { confirm = RideStatus.ENDED }, enabled = !busy) {
                        Icon(Icons.Default.Stop, null); Spacer(Modifier.width(6.dp)); Text("End ride")
                    }
                    RidePhase.ENDED -> OutlinedButton(onClick = { confirm = RideStatus.STARTED }, enabled = !busy) {
                        Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Start again")
                    }
                }
            }
            if (phase == RidePhase.PLANNED) Text("Any rider can start or end the ride.", style = MaterialTheme.typography.bodySmall)
        }
    }

    editing?.let { key ->
        val current = rows.firstOrNull { it.first == key }?.second
        // Suggest a time an hour after the stop before it, so pit stops are quick to fill in.
        val before = rows.takeWhile { it.first != key }.mapNotNull { it.second?.at }.maxOrNull()
        StopTimeDialog(
            label = Schedule.label(key), current = current, suggested = before?.let { it + 60 * 60_000L } ?: 0L,
            onSave = { t -> onSave(schedule.with(key, t)); editing = null },
            onDismiss = { editing = null },
        )
    }
    confirm?.let { state ->
        val start = state == RideStatus.STARTED
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (start) "Start the ride?" else "End the ride?") },
            text = {
                Text(
                    if (start) "Everyone in ${g.name} gets a notification that the ride has started."
                    else "Everyone in ${g.name} gets a notification, and schedule reminders stop."
                )
            },
            confirmButton = { Button(onClick = { if (start) onStart() else onEnd(); confirm = null }) { Text(if (start) "Start ride" else "End ride") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

/** The schedule with [key]'s time set to [t], or removed when [t] is null. */
private fun RideSchedule.with(key: String, t: StopTime?): RideSchedule = when {
    key == Schedule.START -> copy(start = t)
    key == Schedule.END -> copy(end = t)
    else -> {
        val i = key.removePrefix("stop").toIntOrNull() ?: return this
        copy(stops = if (t == null) stops - i else stops + (i to t))
    }
}

@Composable
private fun StopTimeDialog(label: String, current: StopTime?, suggested: Long, onSave: (StopTime?) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(current?.name.orEmpty()) }
    var time by remember { mutableLongStateOf(current?.at ?: 0L) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$label time") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40) },
                    label = { Text("Name (optional), e.g. Breakfast at Highway Inn") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { pickDateTime(context, if (time > 0) time else suggested) { time = it } }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (time == 0L) "Pick date and time" else formatMeetTime(time))
                }
            }
        },
        confirmButton = { Button(enabled = time > 0, onClick = { onSave(StopTime(time, name.trim())) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onSave(null) }) { Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
