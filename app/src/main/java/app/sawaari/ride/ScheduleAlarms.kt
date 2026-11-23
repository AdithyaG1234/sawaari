package app.sawaari.ride

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.sawaari.MainActivity
import app.sawaari.R
import app.sawaari.group.RideGroup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ride schedule reminders. Every rider's phone sets an alarm for each scheduled stop time of the
 * group (start, pit stops, destination), so everyone gets the notification at that time, even with
 * no signal on the road. The ride service also checks while it runs, in case Android delays an alarm;
 * each reminder is shown once. Starting and ending the ride are announced to the group too.
 */
object ScheduleAlarms {
    private const val CHANNEL = "ride_schedule"
    private const val PREFS = "ride_schedule"
    private const val KEY_SET = "alarms"
    private const val KEY_SHOWN = "shown"
    private const val KEY_RIDE_SEEN = "ride_seen"
    private const val EXTRA_ID = "id"
    private const val EXTRA_TITLE = "title"
    private const val EXTRA_TEXT = "text"

    private var lastSignature: String? = null

    fun time(t: Long): String = SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date(t))

    /** Call on every group update: keeps the alarms in step with the schedule and announces start/end. */
    fun onGroup(context: Context, g: RideGroup?, myUid: String?) {
        announceRideState(context, g, myUid)
        val now = System.currentTimeMillis()
        val reminders = g?.let { reminders(it, now) }.orEmpty()
        // Location updates change the group every few seconds; only touch alarms when the schedule changes.
        val signature = reminders.joinToString("|") { "${it.id}:${it.title}:${it.text}" }
        if (signature == lastSignature) return
        lastSignature = signature

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val am = context.getSystemService(AlarmManager::class.java)
        val wanted = reminders.filter { it.at > now }
        val old = prefs.getStringSet(KEY_SET, emptySet()).orEmpty()
        (old - wanted.map { it.id }.toSet()).forEach { am.cancel(pending(context, it, null)) }
        wanted.forEach { r ->
            val pi = pending(context, r.id, r)
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            runCatching {
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
            }
        }
        prefs.edit().putStringSet(KEY_SET, wanted.map { it.id }.toSet()).apply()
    }

    /** From the ride service, every half minute or so: show any reminder whose time has come. */
    fun checkDue(context: Context, g: RideGroup?) {
        val now = System.currentTimeMillis()
        g?.let { reminders(it, now) }.orEmpty().filter { it.at <= now }.forEach { show(context, it.id, it.title, it.text) }
    }

    private data class Reminder(val id: String, val at: Long, val title: String, val text: String)

    private fun reminders(g: RideGroup, now: Long): List<Reminder> {
        val all = Schedule.entries(g)
        val phase = Schedule.phase(g.ride)
        return Schedule.upcoming(g, now).map { e ->
            val next = all.filter { it.at > e.at }.minByOrNull { it.at }
            val (title, text) = Schedule.reminderText(g.name, e, next, phase, ::time)
            Reminder(Schedule.reminderId(g.code, e), e.at, title, text)
        }
    }

    private fun pending(context: Context, id: String, r: Reminder?): PendingIntent {
        val i = Intent(context, ScheduleAlarmReceiver::class.java).setAction("app.sawaari.SCHEDULE:$id").putExtra(EXTRA_ID, id)
        if (r != null) i.putExtra(EXTRA_TITLE, r.title).putExtra(EXTRA_TEXT, r.text)
        return PendingIntent.getBroadcast(context, id.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    internal fun onAlarm(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        show(context, id, intent.getStringExtra(EXTRA_TITLE) ?: return, intent.getStringExtra(EXTRA_TEXT).orEmpty())
    }

    /** Someone started or ended the ride: tell this rider, unless they did it themselves. */
    private fun announceRideState(context: Context, g: RideGroup?, myUid: String?) {
        val ride = g?.ride ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "${g.code}:${ride.state}:${ride.at}"
        val seen = prefs.getString(KEY_RIDE_SEEN, null)
        if (seen == key) return
        prefs.edit().putString(KEY_RIDE_SEEN, key).apply()
        // Don't announce old news, e.g. when the app opens long after the ride started.
        if (ride.byUid == myUid || System.currentTimeMillis() - ride.at > Schedule.LATE_WINDOW_MS) return
        val who = ride.by.ifBlank { "A rider" }
        if (ride.started) {
            val next = Schedule.next(Schedule.entries(g), System.currentTimeMillis())
            show(context, "ride:$key", "${g.name}: ride started", "$who started the ride at ${time(ride.at)}." +
                (next?.let { " Next: ${it.title} at ${time(it.at)}." } ?: ""))
        } else {
            show(context, "ride:$key", "${g.name}: ride ended", "$who ended the ride at ${time(ride.at)}.")
        }
    }

    private fun show(context: Context, id: String, title: String, text: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val shown = prefs.getStringSet(KEY_SHOWN, emptySet()).orEmpty()
        if (id in shown) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Ride schedule", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 200, 400)
                }
            )
        }
        val open = PendingIntent.getActivity(
            context, 3, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ride)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        if (runCatching { NotificationManagerCompat.from(context).notify(id.hashCode(), n) }.isSuccess) {
            // Keep the last few ids only, so the set doesn't grow forever.
            prefs.edit().putStringSet(KEY_SHOWN, (shown.toList().takeLast(40) + id).toSet()).apply()
        }
    }
}

/** Fires at a scheduled stop time (set by [ScheduleAlarms]). */
class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = ScheduleAlarms.onAlarm(context, intent)
}
