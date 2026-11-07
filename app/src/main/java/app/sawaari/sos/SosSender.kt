package app.sawaari.sos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds the SOS text and sends it by SMS from the rider's own SIM (works with no mobile data). */
object SosSender {

    fun message(riderName: String, location: Location?, medical: MedicalInfo, now: Long = System.currentTimeMillis()): String {
        val who = riderName.ifBlank { "a Sawaari rider" }
        val sb = StringBuilder("SOS from $who: I need help.")
        if (location != null) {
            val lat = "%.5f".format(Locale.US, location.latitude)
            val lon = "%.5f".format(Locale.US, location.longitude)
            val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(location.time.takeIf { it > 0 } ?: now))
            val ageMin = ((now - location.time) / 60_000).coerceAtLeast(0)
            sb.append(" Location: https://maps.google.com/?q=$lat,$lon")
            if (location.hasAccuracy()) sb.append(" (±${location.accuracy.toInt()} m")
            else sb.append(" (")
            sb.append(if (ageMin >= 2) ", from $time)" else ")")
        } else {
            sb.append(" My location is not available right now.")
        }
        if (medical.bloodGroup.isNotBlank()) sb.append(" Blood group: ${medical.bloodGroup}.")
        if (medical.notes.isNotBlank()) sb.append(" Medical: ${medical.notes}.")
        return sb.toString()
    }

    fun canSendDirectly(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    /**
     * Sends [text] to every contact in the background, with no tap needed.
     * Returns how many contacts it went to, or -1 when Android hasn't given Sawaari SMS permission.
     * It never opens the SMS app: in an emergency the rider may not be able to tap Send.
     */
    fun send(context: Context, contacts: List<EmergencyContact>, text: String): Int {
        if (contacts.isEmpty()) return 0
        if (!canSendDirectly(context)) return -1
        val managers = smsManagers(context)
        var sent = 0
        contacts.forEach { c ->
            // Dual-SIM phones set to "ask every time" have no default SIM, so try each SIM in turn.
            val ok = managers.any { sms ->
                runCatching { sms.sendMultipartTextMessage(c.phone, null, sms.divideMessage(text), null, null) }.isSuccess
            }
            if (ok) sent++
        }
        return sent
    }

    private fun smsManagers(context: Context): List<SmsManager> {
        val base: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()
        val ids = linkedSetOf(
            SubscriptionManager.getDefaultSmsSubscriptionId(),
            SubscriptionManager.getDefaultVoiceSubscriptionId(),
            SubscriptionManager.getDefaultSubscriptionId(),
        ).filter { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
        val perSim = ids.mapNotNull { id ->
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) base.createForSubscriptionId(id)
                else @Suppress("DEPRECATION") SmsManager.getSmsManagerForSubscriptionId(id)
            }.getOrNull()
        }
        return listOf(base) + perSim
    }
}
