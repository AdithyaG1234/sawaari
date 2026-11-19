package app.sawaari.plans

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** What made the rider open the Pro window, so it can lead with that. */
enum class ProReason { GROUP, GARAGE, SETTINGS }

/** Sawaari Pro: what it unlocks, the two plans, and Restore for riders on a new phone. */
@Composable
fun ProDialog(reason: ProReason, onDismiss: () -> Unit) {
    val state by Pro.state.collectAsState()
    val activity = LocalContext.current.findActivity()
    LaunchedEffect(Unit) { Pro.refresh() }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.WorkspacePremium, null) },
        title = { Text(if (state.active) "You have Sawaari Pro" else "Sawaari Pro") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (reason) {
                    ProReason.GROUP -> Text("Ride groups of up to ${Plans.FREE_GROUP_RIDERS} are free. A bigger group needs Sawaari Pro for every rider in it, including the first ${Plans.FREE_GROUP_RIDERS}. Once you have it, join again with the same code.")
                    ProReason.GARAGE -> Text("Your ${Plans.GARAGE_TRIAL_DAYS}-day garage trial has ended. Your vehicles and service history are safe on this phone; Pro opens them again.")
                    ProReason.SETTINGS -> Unit
                }
                Perk(Icons.Default.Groups, "Bigger ride groups", "Ride in groups of more than ${Plans.FREE_GROUP_RIDERS}, up to ${Plans.PRO_GROUP_RIDERS} riders, when everyone in the group has Pro.")
                Perk(Icons.Default.TwoWheeler, "Garage", "Vehicles, service reminders, and insurance and PUC expiry alerts.")
                Perk(Icons.Default.CheckCircle, "Everything else stays free", "Maps, offline navigation, SOS, crash detection, and groups of up to ${Plans.FREE_GROUP_RIDERS} riders.")
                if (!state.active) state.offers.forEach { o ->
                    val best = o.basePlan == Plans.PLAN_YEARLY
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = if (best) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${o.price} for ${o.title}", fontWeight = FontWeight.Bold)
                                Text(o.note, style = MaterialTheme.typography.bodySmall)
                            }
                            val buy = { activity?.let { Pro.buy(it, o.basePlan) } ?: Unit }
                            if (best) Button(onClick = buy) { Text("Choose") } else OutlinedButton(onClick = buy) { Text("Choose") }
                        }
                    }
                }
                if (!state.active) Text(
                    "Paid through Google Play. Cancel any time in Play Store → Payments and subscriptions.",
                    style = MaterialTheme.typography.bodySmall,
                )
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = { TextButton(onClick = { Pro.clearMessage(); onDismiss() }) { Text(if (state.active) "Done" else "Not now") } },
        dismissButton = if (!state.active) { { TextButton(onClick = { Pro.refresh() }) { Text("Restore") } } } else null,
    )
}

@Composable
private fun Perk(icon: ImageVector, title: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Shown instead of the garage once the free trial is over. */
@Composable
fun GarageLocked(onGetPro: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Default.TwoWheeler, null, tint = MaterialTheme.colorScheme.primary)
        Text("Garage is part of Sawaari Pro", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Your free ${Plans.GARAGE_TRIAL_DAYS}-day trial has ended. Your vehicles and service history are still saved on this phone. " +
                "Get Pro (${Plans.PRICE_QUARTERLY} for 3 months or ${Plans.PRICE_YEARLY} a year) to see them and get service and paper reminders again."
        )
        Button(onClick = onGetPro, modifier = Modifier.fillMaxWidth()) { Text("See Sawaari Pro") }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
