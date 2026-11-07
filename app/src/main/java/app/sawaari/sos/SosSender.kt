package app.sawaari.sos

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
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
     * Sends [text] to every contact. Returns how many were sent directly;
     * if SMS permission is missing, opens the SMS app pre-filled instead and returns 0.
     */
    fun send(context: Context, contacts: List<EmergencyContact>, text: String): Int {
        if (contacts.isEmpty()) return 0
        if (!canSendDirectly(context)) {
            openSmsApp(context, contacts, text)
            return 0
        }
        val sms: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()
        var sent = 0
        contacts.forEach { c ->
            runCatching {
                val parts = sms.divideMessage(text)
                sms.sendMultipartTextMessage(c.phone, null, parts, null, null)
                sent++
            }
        }
        if (sent == 0) openSmsApp(context, contacts, text)
        return sent
    }

    private fun openSmsApp(context: Context, contacts: List<EmergencyContact>, text: String) {
        val to = contacts.joinToString(";") { it.phone }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$to"))
            .putExtra("sms_body", text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
