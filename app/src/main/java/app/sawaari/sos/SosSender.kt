package app.sawaari.sos

import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds the SOS text and opens it in the rider's messaging app, sent from their own SIM (works with no mobile data). */
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

    /** True when the phone has an app that can write a text message. */
    fun canCompose(context: Context) = smsIntent(emptyList(), "").resolveActivity(context.packageManager) != null

    /**
     * Opens the rider's messaging app with [text] filled in for every contact, the same as on iPhone.
     * Sawaari never sends SMS by itself: the rider taps Send. Returns false when no messaging app could open.
     */
    fun compose(context: Context, contacts: List<EmergencyContact>, text: String): Boolean {
        if (contacts.isEmpty()) return false
        return runCatching { context.startActivity(smsIntent(contacts.map { it.phone }, text)) }.isSuccess
    }

    /** The intent [compose] opens, also used for the "tap to text your contacts" notification after a crash. */
    fun smsIntent(phones: List<String>, text: String): Intent {
        // Samsung Messages splits recipients on commas; most other messaging apps on semicolons.
        val sep = if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) "," else ";"
        return Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(phones.joinToString(sep), ",;+")))
            .putExtra("sms_body", text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
