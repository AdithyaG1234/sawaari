package app.sawaari.garage

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.sawaari.MainActivity
import app.sawaari.R

/** Everything that is due or due soon across the garage, for a badge or a reminder. */
data class Reminder(val key: String, val bike: String, val what: String, val status: Status)

/**
 * Tells the rider when a service job or a paper comes due. Checked when the app starts and after
 * every ride; each job is mentioned once when it becomes "soon" and once more when it's due.
 */
object GarageReminders {
    private const val CHANNEL = "garage"
    private const val NOTIF_ID = 7_101
    private const val PREFS = "garage"
    private const val KEY_SENT = "reminded"

    fun all(state: GarageState = Garage.state.value, now: Long = System.currentTimeMillis()): List<Reminder> =
        state.bikes.flatMap { b ->
            val odo = Garage.odometer(b)
            b.items.map { i -> Reminder("${b.id}:${i.id}:${i.lastAt}", b.name, i.name, GarageLogic.status(i, odo, now)) } +
                b.papers.map { (p, exp) -> Reminder("${b.id}:$p:$exp", b.name, p, GarageLogic.paperStatus(exp, now)) }
        }.filter { it.status.level != Level.OK }

    fun check(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sent = prefs.getStringSet(KEY_SENT, emptySet()).orEmpty()
        val due = all()
        val fresh = due.filter { "${it.key}:${it.status.level}" !in sent }
        if (fresh.isEmpty()) return
        if (!notify(context, fresh)) return
        // Keep only keys that still matter, so the set doesn't grow forever.
        val keep = due.map { "${it.key}:${it.status.level}" }.toSet() + sent.filter { s -> due.any { s.startsWith(it.key + ":") } }
        prefs.edit().putStringSet(KEY_SENT, keep).apply()
    }

    private fun notify(context: Context, items: List<Reminder>): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Bike service reminders", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val title = if (items.size == 1) "${items[0].what}: ${items[0].status.text.lowercase()}" else "${items.size} bike jobs need attention"
        val text = items.joinToString("\n") { "${it.bike}: ${it.what}, ${it.status.text.lowercase()}" }
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_GARAGE, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ride)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        return runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, n) }.isSuccess
    }
}
