package app.sawaari.sos

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.sawaari.MainActivity
import app.sawaari.R
import app.sawaari.SawaariApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Crash detection (opt-in, in SOS settings). Listens to the accelerometer and GPS speed; after a
 * likely crash it beeps and counts down [COUNTDOWN_S] seconds, then sends the usual SOS text and
 * group alert unless the rider taps "I'm OK" in the app or on the notification.
 */
object CrashWatch : SensorEventListener {
    const val COUNTDOWN_S = 30
    private const val PREFS = "sos"
    private const val KEY_ENABLED = "crash_detection"
    private const val CHANNEL = "crash_detection"
    private const val NOTIF_ID = 7_001
    const val ACTION_OK = "app.sawaari.CRASH_OK"

    private val logic = CrashLogic()
    private var appContext: Context? = null
    private var listening = false
    private var lastLocation: Location? = null
    private var job: Job? = null

    /** Seconds left before the SOS goes out, or null when nothing is happening. */
    private val _secondsLeft = MutableStateFlow<Int?>(null)
    val secondsLeft: StateFlow<Int?> = _secondsLeft.asStateFlow()

    /** What happened after the last countdown, for the app to show once. */
    val result = MutableStateFlow<String?>(null)

    fun isEnabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) start(context) else stop()
    }

    /** Start listening if the rider turned crash detection on. Safe to call often. */
    fun start(context: Context) {
        appContext = context.applicationContext
        if (listening || !isEnabled(context)) return
        val sm = context.getSystemService(SensorManager::class.java) ?: return
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        listening = sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        val ctx = appContext ?: return
        ctx.getSystemService(SensorManager::class.java)?.unregisterListener(this)
        listening = false
        logic.reset()
    }

    /** Every GPS fix, from the map screen and from the background ride service. */
    fun onLocation(context: Context, loc: Location) {
        lastLocation = loc
        if (!listening) return
        val kmh = if (loc.hasSpeed()) loc.speed * 3.6 else return
        if (logic.onSpeed(System.currentTimeMillis(), kmh) && _secondsLeft.value == null) trigger(context.applicationContext)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        val g = sqrt((x * x + y * y + z * z).toDouble()) / SensorManager.GRAVITY_EARTH
        logic.onAccel(System.currentTimeMillis(), g)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun trigger(context: Context) {
        val app = context as? SawaariApp ?: (context.applicationContext as? SawaariApp) ?: return
        job?.cancel()
        _secondsLeft.value = COUNTDOWN_S
        notifyCountdown(context, COUNTDOWN_S)
        job = app.appScope.launch {
            val tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 100) }.getOrNull()
            try {
                for (left in COUNTDOWN_S downTo 1) {
                    _secondsLeft.value = left
                    if (left % 5 == 0) notifyCountdown(context, left)
                    tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400)
                    delay(1_000)
                }
                send(app)
            } finally {
                tone?.release()
            }
        }
    }

    /** The rider is fine: stop the countdown. */
    fun cancel(context: Context) {
        job?.cancel()
        job = null
        _secondsLeft.value = null
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_ID) }
    }

    /** Skip the countdown. */
    fun sendNow(context: Context) {
        val app = context.applicationContext as? SawaariApp ?: return
        job?.cancel()
        job = app.appScope.launch { send(app) }
    }

    private fun send(app: SawaariApp) {
        _secondsLeft.value = null
        val settings = SosSettings(app)
        val contacts = settings.contacts.value
        val text = "Possible crash detected. " + SosSender.message(app.groups.myName.value, lastLocation, settings.medical.value)
        val sent = SosSender.send(app, contacts, text)
        val group = app.groups.sendSos(lastLocation)
        val msg = buildString {
            append(
                when {
                    contacts.isEmpty() -> "Crash SOS: no emergency contacts saved."
                    sent > 0 -> "Crash SOS sent to $sent contact${if (sent > 1) "s" else ""}."
                    sent < 0 -> "Crash SOS text NOT sent: Sawaari isn't allowed to send SMS."
                    else -> "Crash SOS text could not be sent. Check your SIM's signal and balance."
                }
            )
            if (group) append(" Your ride group has been alerted.")
        }
        result.value = msg
        notify(app, "Crash SOS", msg, withOk = false)
    }

    private fun notifyCountdown(context: Context, left: Int) =
        notify(context, "Crash detected", "Sending SOS in $left seconds. Tap I'm OK if you're fine.", withOk = true)

    private fun notify(context: Context, title: String, text: String, withOk: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Crash detection", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 800, 300, 800, 300, 800)
                }
            )
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ride)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(open)
            .setOnlyAlertOnce(withOk)
            .setAutoCancel(!withOk)
        if (withOk) {
            val ok = PendingIntent.getBroadcast(
                context, 1, Intent(context, CrashOkReceiver::class.java).setAction(ACTION_OK), PendingIntent.FLAG_IMMUTABLE,
            )
            b.addAction(0, "I'm OK", ok).setOngoing(true)
        }
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, b.build()) }
    }
}

/** "I'm OK" on the crash notification. */
class CrashOkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == CrashWatch.ACTION_OK) CrashWatch.cancel(context)
    }
}
