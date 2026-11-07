package app.sawaari.ride

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.sawaari.MainActivity
import app.sawaari.R
import app.sawaari.SawaariApp
import app.sawaari.geo.GeoPoint
import app.sawaari.location.locationUpdates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps sharing the rider's location with the group while the screen is off or the app is
 * in the background, and turns group events (SOS, fell behind, stopped) into notifications.
 */
class RideService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val watcher = GroupWatcher()
    private var me: GeoPoint? = null
    private val notifiedSos = mutableSetOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasLocationPermission(this)) { stopSelf(); return START_NOT_STICKY }
        createChannels(this)
        ServiceCompat.startForeground(
            this, ONGOING_ID, ongoingNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        if (!started) {
            started = true
            val app = application as SawaariApp
            val groups = app.groups
            scope.launch {
                locationUpdates(this@RideService, intervalMs = 5_000L).collect { loc ->
                    me = GeoPoint(loc.latitude, loc.longitude)
                    groups.publish(loc)
                }
            }
            scope.launch {
                groups.group.collect { g ->
                    if (g == null) { stopSelf(); return@collect }
                    watcher.check(g.riders, groups.myUid, me, System.currentTimeMillis()).forEach { a ->
                        notify(a.key.hashCode(), a.title, a.text, urgent = false)
                    }
                }
            }
            scope.launch {
                groups.alerts.collect { alerts ->
                    alerts.filter { it.id !in notifiedSos }.forEach { a ->
                        notifiedSos += a.id
                        notify(a.id.hashCode(), "SOS from ${a.name}", "Open Sawaari to see where they are and go to them.", urgent = true)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        started = false
        scope.cancel()
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ongoingNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RideService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_SHARING)
            .setSmallIcon(R.drawable.ic_stat_ride)
            .setContentTitle("Sharing your location with the group")
            .setContentText("Keeps working with the screen off.")
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Stop sharing", stop)
            .build()
    }

    private fun notify(id: Int, title: String, text: String, urgent: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(this, if (urgent) CHANNEL_SOS else CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_ride)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(if (urgent) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (urgent) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(id, n) }
    }

    companion object {
        private const val ACTION_STOP = "app.sawaari.STOP_SHARING"
        private const val ONGOING_ID = 42
        private const val CHANNEL_SHARING = "ride_sharing"
        private const val CHANNEL_ALERTS = "ride_alerts"
        private const val CHANNEL_SOS = "ride_sos"
        @Volatile private var started = false

        fun hasLocationPermission(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun start(context: Context) {
            if (started || !hasLocationPermission(context)) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, RideService::class.java)) }
        }

        fun stop(context: Context) {
            if (!started) return
            context.stopService(Intent(context, RideService::class.java))
        }

        private fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_SHARING, "Location sharing", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Group ride alerts", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_SOS, "SOS from your group", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 600, 200, 600, 200, 600)
                }
            )
        }
    }
}
