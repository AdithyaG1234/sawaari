package app.sawaari.group

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.sawaari.ride.PackItem
import app.sawaari.ride.Packing

/** Each rider's packing list, kept on this phone per ride group (so it works with no signal). */
object PackingStore {
    private const val PREFS = "packing"
    fun load(context: Context, code: String): List<PackItem> =
        Packing.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(code, null)) ?: Packing.defaults()

    fun save(context: Context, code: String, items: List<PackItem>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(code, Packing.encode(items)).apply()
    }
}

/**
 * "What to carry": my own checklist (only I see it) plus what other riders said they're carrying
 * and can share, like a puncture kit or a first-aid kit.
 */
@Composable
fun PackingCard(g: RideGroup, repo: GroupRepository) {
    val context = LocalContext.current
    var items by remember(g.code) { mutableStateOf(PackingStore.load(context, g.code)) }
    var open by remember { mutableStateOf(false) }
    val update = { next: List<PackItem> ->
        val sharedBefore = Packing.sharedForGroup(items)
        items = next
        PackingStore.save(context, g.code, next)
        val shared = Packing.sharedForGroup(next)
        if (shared != sharedBefore) repo.shareGear(shared)
    }
    val others = g.sharedGear.filterKeys { it != repo.myUid }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("What to carry", style = MaterialTheme.typography.titleMedium)
            val packed = items.count { it.packed }
            val shared = items.count { it.shared }
            Text(
                "Your list: $packed of ${items.size} packed" + if (shared > 0) " · sharing $shared with the group" else ". Only you see it.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (others.isNotEmpty()) {
                Text("Riders can share", style = MaterialTheme.typography.labelMedium)
                others.forEach { (uid, gear) ->
                    val name = g.riders.firstOrNull { it.uid == uid }?.name ?: "A rider"
                    Text("$name: ${gear.sorted().joinToString(", ")}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            OutlinedButton(onClick = { open = true }) { Text("Open my checklist") }
        }
    }

    if (open) PackingDialog(items, update, onDismiss = { open = false })
}

@Composable
private fun PackingDialog(items: List<PackItem>, onChange: (List<PackItem>) -> Unit, onDismiss: () -> Unit) {
    var newItem by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("My packing checklist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Tick what you've packed. Tap Share on things others can use too, so the group knows you have them.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    items.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Checkbox(checked = item.packed, onCheckedChange = { onChange(Packing.toggle(items, item.id)) })
                            Text(
                                item.name, Modifier.weight(1f),
                                textDecoration = if (item.packed) TextDecoration.LineThrough else null,
                                color = if (item.packed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            )
                            FilterChip(selected = item.shared, onClick = { onChange(Packing.toggleShared(items, item.id)) }, label = { Text(if (item.shared) "Shared" else "Share") })
                            IconButton(onClick = { onChange(Packing.remove(items, item.id)) }) { Icon(Icons.Default.Close, "Remove ${item.name}") }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newItem, onValueChange = { newItem = it.take(Packing.MAX_NAME) },
                        label = { Text("Add an item") }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                    TextButton(enabled = newItem.isNotBlank(), onClick = {
                        onChange(Packing.add(items, newItem, "u${System.currentTimeMillis()}")); newItem = ""
                    }) { Text("Add") }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Done") } },
    )
}
